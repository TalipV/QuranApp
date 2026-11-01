/**
 * Brücke zwischen der Android-App und @tilawa/core.
 *
 * Android → JS:  window.tilawa.feed(base64Float32Le), .stop(), .reset()
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
    };
  }
}

const SKIPPED_EVENTS = new Set(["debug"]);

function post(message: Record<string, unknown>): void {
  const json = JSON.stringify(message);
  if (window.TilawaHost) window.TilawaHost.postMessage(json);
  else console.log("[tilawa]", json);
}

function forward(messages: { type: string }[]): void {
  for (const msg of messages) {
    if (!SKIPPED_EVENTS.has(msg.type)) post({ type: "event", event: msg });
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

let session: RecognitionSession | null = null;
// Alle Aufrufe strikt nacheinander ausführen – feed() ist asynchron.
let queue: Promise<unknown> = Promise.resolve();

function enqueue(job: (s: RecognitionSession) => Promise<unknown> | unknown): void {
  queue = queue
    .then(() => (session ? job(session) : undefined))
    .catch((e) => post({ type: "error", message: errorText(e) }));
}

window.tilawa = {
  feed(base64) {
    const chunk = decodeFloat32(base64);
    enqueue(async (s) => forward(await s.feed(chunk)));
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
      corpus: () => loadJson("corpus.json") as never,
      quran: () => loadJson("quran.json") as never,
    });
    post({ type: "ready" });
  } catch (e) {
    post({ type: "error", message: errorText(e) });
  }
}

void init();
