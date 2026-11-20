/**
 * Brücke zwischen der Android-App und @tilawa/core (1.x).
 *
 * Android → JS:  window.tilawa.feed(base64Float32Le), .stop(), .reset(), .configure(json)
 * JS → Android:  TilawaHost.postMessage(json)
 *
 * Alles läuft offline: Modell und Referenz liegen in den App-Assets (siehe build.mjs).
 * Die Übersetzung der Tilawa-Ereignisse steckt in engine.ts.
 */
import * as ort from "onnxruntime-web/wasm";
import { SessionEndedError, type Engine, type ReferenceAsset, type TilawaSession } from "@tilawa/core";
import phrases from "@tilawa/core/assets/phrases-v1.json";
import { EventTranslator, SESSION_POLICY, buildEngine, wordLookup, type BridgeEvent } from "./engine";

interface TilawaHostBridge {
  postMessage(json: string): void;
}

declare global {
  interface Window {
    TilawaHost?: TilawaHostBridge;
    tilawa: {
      feed(base64: string): void;
      stop(): void;
      reset(): void;
      configure(json: string): void;
    };
  }
}

function post(message: Record<string, unknown>): void {
  const json = JSON.stringify(message);
  if (window.TilawaHost) window.TilawaHost.postMessage(json);
  else console.log("[tilawa]", json);
}

function errorText(e: unknown): string {
  return e instanceof Error ? `${e.message}\n${e.stack ?? ""}` : String(e);
}

function decodeFloat32(base64: string): Float32Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Float32Array(bytes.buffer);
}

/** Lädt eine Datei und meldet den Fortschritt (Content-Length kommt aus der App). */
async function loadBuffer(path: string): Promise<Uint8Array> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
  const total = Number(res.headers.get("Content-Length") ?? 0);
  if (!res.body || !total) return new Uint8Array(await res.arrayBuffer());

  const reader = res.body.getReader();
  const buffer = new Uint8Array(total);
  let received = 0;
  let lastPercent = -1;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer.set(value, received);
    received += value.length;
    const percent = Math.floor((received / total) * 100);
    if (percent !== lastPercent) {
      lastPercent = percent;
      post({ type: "loading", stage: "model", percent });
    }
  }
  return buffer;
}

interface BridgeConfig {
  /** "tracking" (nur mitlesen) oder "correction" (mit Fehlererkennung) */
  mode?: "tracking" | "correction";
  /** Passage für den Korrekturmodus, z. B. ab der Start-Aya bis Surenende */
  expected?: { surah: number; ayah: number; ayahEnd?: number } | null;
  /** Aus 0.x; Tilawa 1.x braucht das nicht mehr (Fehler vor der ersten Erkennung werden ohnehin verworfen). */
  autoContinue?: boolean;
  strictOmissions?: boolean;
  relocate?: boolean;
}

let engine: Engine | null = null;
let words: ReturnType<typeof wordLookup> | null = null;
let session: TilawaSession | null = null;
let pendingConfig: BridgeConfig | null = null;

/**
 * Noch nicht ausgewertete Audio-Blöcke. Ist die Erkennung langsamer als die Aufnahme,
 * wächst der Rückstand – die App zeigt dann „wird verarbeitet“ an.
 */
let pendingFeeds = 0;
let reportedBacklog = -1;
const CHUNK_SECONDS = 0.3;

function reportBacklog(): void {
  const seconds = Math.round(pendingFeeds * CHUNK_SECONDS * 10) / 10;
  if (seconds === reportedBacklog) return;
  reportedBacklog = seconds;
  post({ type: "backlog", seconds });
}

function forward(events: BridgeEvent[]): void {
  for (const event of events) post({ type: "event", event });
}

function startSession(config: BridgeConfig): void {
  if (!engine || !words) {
    pendingConfig = config;
    return;
  }
  session?.dispose();
  const correction = config.mode === "correction";
  const expected = config.expected;
  // Korrekturmodus: bekannte Passage → Tilawa bleibt darin und prüft auf Fehler.
  // Nur mitlesen / Stelle finden: freie Rezitation, überall im Quran.
  const next = correction && expected
    ? engine.startMemorization(
      { surah: expected.surah, fromAyah: expected.ayah, toAyah: Math.max(expected.ayah, expected.ayahEnd ?? expected.ayah) },
      SESSION_POLICY,
    )
    : engine.startFreeRecitation(SESSION_POLICY);
  const translator = new EventTranslator(words, correction);
  next.subscribe((e) => forward(translator.translate(e)));
  session = next;
}

window.tilawa = {
  feed(base64) {
    const current = session;
    if (!current || current.state !== "active") return;
    const chunk = decodeFloat32(base64);
    pendingFeeds++;
    reportBacklog();
    current.pushAudio(chunk, 16_000)
      .catch((e) => {
        if (!(e instanceof SessionEndedError) && current === session) post({ type: "error", message: errorText(e) });
      })
      .finally(() => {
        pendingFeeds = Math.max(0, pendingFeeds - 1);
        reportBacklog();
      });
  },
  configure(json) {
    startSession(JSON.parse(json) as BridgeConfig);
  },
  stop() {
    const current = session;
    if (!current) return;
    current.end()
      .catch((e) => post({ type: "error", message: errorText(e) }))
      .finally(() => post({ type: "stopped" }));
  },
  reset() {
    session?.dispose();
    session = null;
    pendingConfig = null;
  },
};

async function init(): Promise<void> {
  try {
    ort.env.wasm.numThreads = 1;
    ort.env.wasm.simd = true;
    // Nur die .wasm-Datei liegt extern; der JS-Glue steckt im Bundle.
    ort.env.wasm.wasmPaths = {
      wasm: new URL("ort-wasm-simd-threaded.wasm", location.href).href,
    };
    post({ type: "loading" });
    const [model, reference] = await Promise.all([
      loadBuffer("model.onnx"),
      fetch("ref-v2.json").then((r) => {
        if (!r.ok) throw new Error(`ref-v2.json: HTTP ${r.status}`);
        return r.json() as Promise<ReferenceAsset>;
      }),
    ]);
    post({ type: "loading", stage: "init", percent: -1 });
    engine = await buildEngine({
      ort: ort as never,
      backendName: "onnxruntime-web/wasm",
      sessionOptions: { executionProviders: ["wasm"] },
      model,
      reference,
      phrases,
    });
    words = wordLookup(reference);
    post({ type: "ready" });
    const config = pendingConfig;
    pendingConfig = null;
    if (config) startSession(config);
  } catch (e) {
    post({ type: "error", message: errorText(e) });
  }
}

void init();
