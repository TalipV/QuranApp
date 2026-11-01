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
    for (const note of omissionNotes(msg)) {
      enrich(note);
      post({ type: "event", event: note });
    }
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

/** Lädt eine Datei und meldet den Fortschritt (Content-Length kommt aus der App). */
async function loadBuffer(path: string): Promise<ArrayBuffer> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
  const total = Number(res.headers.get("Content-Length") ?? 0);
  if (!res.body || !total) return res.arrayBuffer();

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
  return buffer.buffer;
}

interface BridgeConfig {
  /** "tracking" (nur mitlesen) oder "correction" (mit Fehlererkennung) */
  mode?: "tracking" | "correction";
  /** Erwartete Passage, z. B. ab der Start-Aya bis Surenende */
  expected?: { surah: number; ayah: number; ayahEnd?: number } | null;
  /** Nach einem erkannten Fehler automatisch weiterverfolgen statt anzuhalten */
  autoContinue?: boolean;
  /** Nicht gehörte Wörter einer Aya als Fehler melden, sobald die nächste Aya beginnt */
  strictOmissions?: boolean;
}

let session: RecognitionSession | null = null;
let autoContinue = true;
let strictOmissions = true;

/**
 * Strenge Prüfung auf ausgelassene Wörter (Ergänzung zu Tilawa, das hier bewusst
 * zurückhaltend ist): Beginnt eine spätere Aya, gelten alle Wörter der vorherigen,
 * die nie gehört wurden, als möglicherweise ausgelassen.
 */
interface WordProgress { surah: number; ayah: number; total_words: number; matched_indices: number[] }
let lastProgress: WordProgress | null = null;
const checkedAyahs = new Set<string>();

function ayahOrder(surah: number, ayah: number): number {
  return surah * 1000 + ayah;
}

function omissionNotes(msg: { type: string; surah?: number; ayah?: number }): { type: string }[] {
  if (msg.type !== "word_progress" && msg.type !== "verse_match") return [];
  if (msg.surah === undefined || msg.ayah === undefined) return [];
  const previous = lastProgress;
  if (msg.type === "word_progress") lastProgress = msg as unknown as WordProgress;
  if (!strictOmissions || !previous) return [];
  if (ayahOrder(msg.surah, msg.ayah) <= ayahOrder(previous.surah, previous.ayah)) return [];

  const key = `${previous.surah}:${previous.ayah}`;
  if (checkedAyahs.has(key)) return [];
  checkedAyahs.add(key);

  const matched = new Set(previous.matched_indices);
  const notes: { type: string }[] = [];
  let start = -1;
  for (let w = 0; w <= previous.total_words; w++) {
    const missing = w < previous.total_words && !matched.has(w);
    if (missing && start < 0) start = w;
    if (!missing && start >= 0) {
      notes.push({
        type: "correction_note",
        issue: { surah: previous.surah, ayah: previous.ayah, word: start, words: w - start,
          kind: "possible_omission", source: "strict" },
      } as never);
      start = -1;
    }
  }
  return notes;
}

function resetTracking(): void {
  lastProgress = null;
  checkedAyahs.clear();
}
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
      if (config.strictOmissions !== undefined) strictOmissions = config.strictOmissions;
      if (!s.zipformer) return;
      if (config.expected !== undefined) s.zipformer.setExpected(config.expected);
      if (config.mode) forward(s.zipformer.setMode(config.mode));
    });
  },
  stop() {
    enqueue(async (s) => {
      forward(await s.stop());
      s.reset();
      resetTracking();
      post({ type: "stopped" });
    });
  },
  reset() {
    enqueue((s) => {
      s.reset();
      resetTracking();
    });
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
      model: async () => {
        const buffer = await loadBuffer("model.onnx");
        post({ type: "loading", stage: "init", percent: -1 });
        return buffer;
      },
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
