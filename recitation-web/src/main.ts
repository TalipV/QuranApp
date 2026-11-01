/**
 * Brücke zwischen der Android-App und @tilawa/core.
 *
 * Android → JS:  window.tilawa.feed(base64Float32Le), .stop(), .reset(),
 *                .configure(json)  // { mode, expected, autoContinue }
 * JS → Android:  TilawaHost.postMessage(json)
 *
 * Bewusst dünn gehalten: Updates von @tilawa/core sollen ohne Änderungen
 * hier funktionieren. Alle Events gehen unverändert an Android durch.
 */
import * as ort from "onnxruntime-web/wasm";
import { createRecognitionSession, type RecognitionSession } from "@tilawa/core";

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

const SKIPPED_EVENTS = new Set(["debug"]);

function post(message: Record<string, unknown>): void {
  const json = JSON.stringify(message);
  if (window.TilawaHost) window.TilawaHost.postMessage(json);
  else console.log("[tilawa]", json);
}

interface CorpusData {
  surahs: { ayahs: { w: string[][] }[] }[];
}
let corpusData: CorpusData | null = null;

/** Uthmani-Text der Wörter [word, word + count) einer Aya (0-basiert, ohne Basmala). */
function wordText(surah: number, ayah: number, word: number, count: number): string {
  const words = corpusData?.surahs[surah - 1]?.ayahs[ayah - 1]?.w;
  if (!words) return "";
  return words.slice(Math.max(0, word), Math.max(0, word) + Math.max(1, count)).map((w) => w[2] ?? w[1] ?? "").join(" ");
}

interface IssueLike { surah: number; ayah: number; word: number; words?: number; kind: string; word_text?: string }

/** Ergänzt Fehler-Meldungen um den betroffenen Wortlaut (für den Hinweis in der App). */
function enrich(msg: { type: string; state?: { issue?: IssueLike | null }; issue?: IssueLike }): void {
  const issue = msg.type === "correction" ? msg.state?.issue : msg.type === "correction_note" ? msg.issue : null;
  if (!issue || issue.word_text) return;
  const ayahLevel = issue.kind === "possible_skipped_ayah" || issue.kind === "unclear_ayah";
  issue.word_text = ayahLevel
    ? wordText(issue.surah, issue.ayah, 0, 3) + " …"
    : wordText(issue.surah, issue.ayah, issue.word, issue.words ?? 1);
}

function forward(messages: { type: string }[]): void {
  for (const msg of messages) {
    if (SKIPPED_EVENTS.has(msg.type)) continue;
    enrich(msg);
    post({ type: "event", event: msg });
  }
}

function decodeFloat32(base64: string): Float32Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Float32Array(bytes.buffer);
}

function errorText(e: unknown): string {
  return e instanceof Error ? `${e.message}\n${e.stack ?? ""}` : String(e);
}

async function loadJson(path: string): Promise<unknown> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
  return res.json();
}

async function loadBuffer(path: string): Promise<ArrayBuffer> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
  return res.arrayBuffer();
}

interface BridgeConfig {
  /** "tracking" (nur mitlesen) oder "correction" (mit Fehlererkennung) */
  mode?: "tracking" | "correction";
  /** Erwartete Passage, z. B. ab der Start-Aya bis Surenende */
  expected?: { surah: number; ayah: number; ayahEnd?: number } | null;
  /** Nach einem erkannten Fehler automatisch weiterverfolgen statt anzuhalten */
  autoContinue?: boolean;
}

let session: RecognitionSession | null = null;
let autoContinue = true;
// Alle Aufrufe strikt nacheinander ausführen – feed() ist asynchron.
let queue: Promise<unknown> = Promise.resolve();

function enqueue(job: (s: RecognitionSession) => Promise<unknown> | unknown): void {
  queue = queue
    .then(() => (session ? job(session) : undefined))
    .catch((e) => post({ type: "error", message: errorText(e) }));
}

/** Im Korrekturmodus hält Tilawa nach einem Fehler an; hier geht es direkt weiter. */
function continueAfterError(s: RecognitionSession, messages: { type: string; state?: { phase?: string } }[]): void {
  if (!autoContinue || !s.zipformer) return;
  const hasError = messages.some((m) => m.type === "correction" && m.state?.phase === "error");
  if (hasError) forward(s.zipformer.correct("review_later"));
}

window.tilawa = {
  feed(base64) {
    const chunk = decodeFloat32(base64);
    enqueue(async (s) => {
      const messages = await s.feed(chunk);
      forward(messages);
      continueAfterError(s, messages);
    });
  },
  configure(json) {
    const config = JSON.parse(json) as BridgeConfig;
    enqueue((s) => {
      if (config.autoContinue !== undefined) autoContinue = config.autoContinue;
      if (!s.zipformer) return;
      if (config.expected !== undefined) s.zipformer.setExpected(config.expected);
      if (config.mode) forward(s.zipformer.setMode(config.mode));
    });
  },
  stop() {
    enqueue(async (s) => {
      forward(await s.stop());
      s.reset();
      post({ type: "stopped" });
    });
  },
  reset() {
    enqueue((s) => s.reset());
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
    session = await createRecognitionSession({
      ort,
      model: () => loadBuffer("model.onnx"),
      corpus: async () => {
        corpusData = (await loadJson("corpus.json")) as CorpusData;
        return corpusData as never;
      },
      quran: () => loadJson("quran.json") as never,
    });
    post({ type: "ready" });
  } catch (e) {
    post({ type: "error", message: errorText(e) });
  }
}

void init();
