/**
 * Smoke-Test der Brücke in Node (läuft im CI nach `npm run build`):
 * baut die Engine aus denselben Dateien wie die App und prüft die Übersetzung der Ereignisse.
 *
 * 1. Stille → keine Positions- oder Fehlermeldungen, kein Absturz.
 * 2. Echte Rezitation (everyayah.com, Alafasy), falls Netz und ffmpeg da sind:
 *    a) freie Rezitation erkennt 1:2 (wie „Stelle finden“),
 *    b) Korrekturmodus mit Passage ab 1:1, Einsatz erst bei 1:2 (wie Fortsetzen nach Pause):
 *       keine Fehler vor der ersten erkannten Stelle,
 *    c) Korrekturmodus mit ausgelassener Aya 1:3 → Fehler wird gemeldet,
 *    d/e) Aʿūḏu billāh und Basmala vor der Rezitation gelten nicht als Quranstelle.
 */
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import * as ort from "onnxruntime-node";
import type { ReferenceAsset, TilawaSession } from "@tilawa/core";
import phrases from "@tilawa/core/assets/phrases-v1.json";
import { EventTranslator, SESSION_POLICY, buildEngine, QuranWords, type BridgeEvent } from "./engine";

const here = dirname(fileURLToPath(import.meta.url));
const assets = join(here, "..", "..", "app", "src", "main", "assets", "recitation");

const model = new Uint8Array(readFileSync(join(assets, "model.onnx")));
const reference = JSON.parse(readFileSync(join(assets, "ref-v2.json"), "utf8")) as ReferenceAsset;
const words = new QuranWords(reference);

let failures = 0;
function check(ok: boolean, what: string): void {
  console.log(`${ok ? "OK  " : "FAIL"} ${what}`);
  if (!ok) failures++;
}

const engine = await buildEngine({ ort: ort as never, backendName: "onnxruntime-node", model, reference, phrases });
console.log("Engine bereit:", JSON.stringify(engine.capabilities));

async function run(session: TilawaSession, translator: EventTranslator, audio: Float32Array): Promise<BridgeEvent[]> {
  const out: BridgeEvent[] = [];
  session.subscribe((e) => out.push(...translator.translate(e)));
  const chunk = 4_800; // 300 ms wie in der App
  for (let i = 0; i < audio.length; i += chunk) void session.pushAudio(audio.subarray(i, i + chunk), 16_000);
  await session.end();
  return out;
}

// 1. Stille
{
  const s = engine.startFreeRecitation(SESSION_POLICY);
  const out = await run(s, new EventTranslator(words, true), new Float32Array(16_000 * 3));
  check(!out.some((e) => e.type === "word_progress" || e.type === "correction_note"), "Stille: keine Position, keine Fehler");
}

// 2. Echte Rezitation
/** Lädt Aufnahmen von everyayah.com (Alafasy) als 16 kHz mono; eine Float32Array je Datei. */
async function load(names: string[]): Promise<Float32Array[]> {
  const out: Float32Array[] = [];
  for (const name of names) {
    const res = await fetch(`https://everyayah.com/data/Alafasy_128kbps/${name}.mp3`, { signal: AbortSignal.timeout(30_000) });
    if (!res.ok) throw new Error(`everyayah ${name}: HTTP ${res.status}`);
    const mp3 = Buffer.from(await res.arrayBuffer());
    const pcm = execFileSync("ffmpeg", [
      "-loglevel", "error", "-i", "pipe:0", "-f", "f32le", "-ac", "1", "-ar", "16000", "pipe:1",
    ], { input: mp3, maxBuffer: 64 * 1024 * 1024, timeout: 60_000 });
    out.push(new Float32Array(pcm.buffer, pcm.byteOffset, pcm.byteLength / 4).slice());
  }
  return out;
}

function concat(parts: Float32Array[]): Float32Array {
  const all = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    all.set(p, at);
    at += p.length;
  }
  return all;
}

const matchesOf = (events: BridgeEvent[]) => events.filter((e) => e.type === "verse_match").map((e) => `${e.surah}:${e.ayah}`);
const progressOf = (events: BridgeEvent[]) => events.filter((e) => e.type === "word_progress");
const notesOf = (events: BridgeEvent[]) =>
  events.filter((e) => e.type === "correction_note").map((e) => e.issue as { surah: number; ayah: number; kind: string });

let clips: Record<string, Float32Array> | null = null;
try {
  const names = ["001002", "001003", "001004", "audhubillah", "bismillah", "036001", "036002", "036003", "002001", "002002"];
  const loaded = await load(names);
  clips = Object.fromEntries(names.map((n, i) => [n, loaded[i]]));
} catch (e) {
  console.warn("Rezitation nicht verfügbar (Netz/ffmpeg) – Teil 2 übersprungen:", (e as Error).message.split("\n")[0]);
}

if (clips) {
  const c = clips;
  const fatiha = concat([c["001002"], c["001003"], c["001004"]]);

  // a) freie Rezitation (wie „Stelle finden“)
  const started = Date.now();
  const free = await run(engine.startFreeRecitation(SESSION_POLICY), new EventTranslator(words, false), fatiha);
  console.log(`Freie Rezitation: ${(fatiha.length / 16_000).toFixed(1)} s Audio in ${((Date.now() - started) / 1000).toFixed(1)} s ausgewertet`);
  console.log("  erkannte Ayat:", matchesOf(free).join(", ") || "(keine)");
  check(matchesOf(free)[0] === "1:2", "freie Rezitation: erste erkannte Aya ist 1:2");
  check(matchesOf(free).includes("1:4"), "freie Rezitation: folgt bis 1:4");
  check(progressOf(free).every((e) => (e.total_words as number) > 0), "word_progress hat total_words");

  // b) Korrekturmodus ab 1:1, Einsatz erst bei 1:2 (wie Fortsetzen nach Pause)
  const p1 = { surah: 1, fromAyah: 1, toAyah: 7 };
  const memo = await run(engine.startMemorization(p1, SESSION_POLICY), new EventTranslator(words, true, p1), fatiha);
  console.log("  Korrekturmodus, Fehler:", JSON.stringify(notesOf(memo)));
  check(!notesOf(memo).some((i) => i.ayah < 2), "Korrekturmodus ab 1:1, Einsatz bei 1:2: kein Fehler für 1:1");
  check(progressOf(memo).length > 0, "Korrekturmodus: verfolgt die Position");

  // c) 1:3 auslassen → Fehler in 1:3
  const p2 = { surah: 1, fromAyah: 2, toAyah: 7 };
  const skip = await run(
    engine.startMemorization(p2, SESSION_POLICY),
    new EventTranslator(words, true, p2),
    concat([c["001002"], c["001004"]]),
  );
  console.log("  1:3 ausgelassen, Fehler:", JSON.stringify(notesOf(skip)));
  check(notesOf(skip).some((i) => i.ayah === 3), "ausgelassene Aya 1:3 wird gemeldet");

  // d) Aʿūḏu billāh + Basmala vor Yā-Sīn: Einleitung darf nicht als Stelle gelten (aʿūḏu passt zu 2:67)
  const yasin = concat([c.audhubillah, c.bismillah, c["036001"], c["036002"], c["036003"]]);
  const freeYs = await run(engine.startFreeRecitation(SESSION_POLICY), new EventTranslator(words, false), yasin);
  console.log("  Aʿūḏu + Basmala + 36:1–3, frei:", matchesOf(freeYs).join(", ") || "(keine)");
  check(matchesOf(freeYs).length > 0 && matchesOf(freeYs).every((m) => m.startsWith("36:")), "Einleitung frei: nur Yā-Sīn erkannt");
  const guesses = freeYs.filter((e) => e.type === "verse_candidate")
    .flatMap((e) => (e.candidates as { surah: number; ayah: number }[]).map((c) => `${c.surah}:${c.ayah}`));
  console.log("  Vermutungen:", [...new Set(guesses)].join(", ") || "(keine)", "| Einleitung gemeldet:", freeYs.some((e) => e.type === "intro"));
  check(!guesses.some((g) => ["2:67", "11:47", "19:18", "1:1", "27:30"].includes(g)), "Einleitung frei: keine Vermutung für aʿūḏu/Basmala-Ayat");
  check(freeYs.some((e) => e.type === "intro"), "Einleitung frei: als Einleitung gemeldet");
  const p36 = { surah: 36, fromAyah: 1, toAyah: 83 };
  const memoYs = await run(engine.startMemorization(p36, SESSION_POLICY), new EventTranslator(words, true, p36), yasin);
  console.log("  Aʿūḏu + Basmala + 36:1–3, Korrektur:", matchesOf(memoYs).join(", ") || "(keine)", JSON.stringify(notesOf(memoYs)));
  check(progressOf(memoYs).length > 0 && progressOf(memoYs).every((e) => e.surah === 36), "Einleitung Korrektur: nur Yā-Sīn verfolgt");

  // e) Basmala vor Al-Baqara: nicht als 1:1 erkennen
  const baqara = concat([c.bismillah, c["002001"], c["002002"]]);
  const freeBq = await run(engine.startFreeRecitation(SESSION_POLICY), new EventTranslator(words, false), baqara);
  console.log("  Basmala + 2:1–2, frei:", matchesOf(freeBq).join(", ") || "(keine)");
  check(matchesOf(freeBq).length > 0 && matchesOf(freeBq).every((m) => m.startsWith("2:")), "Basmala frei: nur Al-Baqara erkannt");
}

if (failures > 0) {
  console.error(`${failures} Prüfung(en) fehlgeschlagen`);
  process.exit(1);
}
console.log("Smoke-Test bestanden");
process.exit(0);
