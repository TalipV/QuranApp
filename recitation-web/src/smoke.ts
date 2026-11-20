/**
 * Smoke-Test der Brücke in Node (läuft im CI nach `npm run build`):
 * baut die Engine aus denselben Dateien wie die App und prüft die Übersetzung der Ereignisse.
 *
 * 1. Stille → keine Positions- oder Fehlermeldungen, kein Absturz.
 * 2. Echte Rezitation (Al-Fātiḥa 1:2–1:4, everyayah.com), falls Netz und ffmpeg da sind:
 *    a) freie Rezitation erkennt 1:2 (wie „Stelle finden“),
 *    b) Korrekturmodus mit Passage ab 1:1, Einsatz erst bei 1:2 (wie Fortsetzen nach Pause):
 *       keine Fehler vor der ersten erkannten Stelle,
 *    c) Korrekturmodus mit ausgelassener Aya 1:3 → Fehler wird gemeldet.
 */
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import * as ort from "onnxruntime-node";
import type { ReferenceAsset, TilawaSession } from "@tilawa/core";
import phrases from "@tilawa/core/assets/phrases-v1.json";
import { EventTranslator, SESSION_POLICY, buildEngine, wordLookup, type BridgeEvent } from "./engine";

const here = dirname(fileURLToPath(import.meta.url));
const assets = join(here, "..", "..", "app", "src", "main", "assets", "recitation");

const model = new Uint8Array(readFileSync(join(assets, "model.onnx")));
const reference = JSON.parse(readFileSync(join(assets, "ref-v2.json"), "utf8")) as ReferenceAsset;
const words = wordLookup(reference);

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
const AYAHS = ["001002", "001003", "001004"];
/** Länge jeder geladenen Aya in Samples (für den Test mit ausgelassener Aya). */
const ayahLengths: number[] = [];

async function loadRecitation(): Promise<Float32Array | null> {
  try {
    const parts: Buffer[] = [];
    for (const ayah of AYAHS) {
      const res = await fetch(`https://everyayah.com/data/Alafasy_128kbps/${ayah}.mp3`);
      if (!res.ok) throw new Error(`everyayah ${ayah}: HTTP ${res.status}`);
      const mp3 = Buffer.from(await res.arrayBuffer());
      parts.push(execFileSync("ffmpeg", [
        "-loglevel", "error", "-i", "pipe:0", "-f", "f32le", "-ac", "1", "-ar", "16000", "pipe:1",
      ], { input: mp3, maxBuffer: 64 * 1024 * 1024 }));
    }
    for (const p of parts) ayahLengths.push(p.byteLength / 4);
    const all = Buffer.concat(parts);
    return new Float32Array(all.buffer, all.byteOffset, all.byteLength / 4).slice();
  } catch (e) {
    console.warn("Rezitation nicht verfügbar (Netz/ffmpeg) – Teil 2 übersprungen:", (e as Error).message.split("\n")[0]);
    return null;
  }
}

const audio = await loadRecitation();
if (audio) {
  const started = Date.now();
  const free = await run(engine.startFreeRecitation(SESSION_POLICY), new EventTranslator(words, false), audio);
  const seconds = audio.length / 16_000;
  console.log(`Freie Rezitation: ${seconds.toFixed(1)} s Audio in ${((Date.now() - started) / 1000).toFixed(1)} s ausgewertet`);
  const matches = free.filter((e) => e.type === "verse_match").map((e) => `${e.surah}:${e.ayah}`);
  console.log("  erkannte Ayat:", matches.join(", ") || "(keine)");
  check(matches[0] === "1:2", "freie Rezitation: erste erkannte Aya ist 1:2");
  check(matches.includes("1:4"), "freie Rezitation: folgt bis 1:4");
  const progress = free.filter((e) => e.type === "word_progress");
  check(progress.every((e) => typeof e.total_words === "number" && (e.total_words as number) > 0), "word_progress hat total_words");

  const memo = await run(
    engine.startMemorization({ surah: 1, fromAyah: 1, toAyah: 7 }, SESSION_POLICY),
    new EventTranslator(words, true),
    audio,
  );
  const notes = memo.filter((e) => e.type === "correction_note");
  console.log("  Korrekturmodus, Fehler:", JSON.stringify(notes.map((e) => e.issue)));
  check(
    !notes.some((e) => (e.issue as { ayah: number }).ayah < 2),
    "Korrekturmodus ab 1:1, Einsatz bei 1:2: kein Fehler für 1:1",
  );
  check(memo.some((e) => e.type === "word_progress"), "Korrekturmodus: verfolgt die Position");

  // c) 1:3 auslassen: 1:2 direkt gefolgt von 1:4 → Fehler in 1:3 erwartet
  const [l2, l3] = ayahLengths;
  const skipped = new Float32Array(audio.length - l3);
  skipped.set(audio.subarray(0, l2), 0);
  skipped.set(audio.subarray(l2 + l3), l2);
  const skip = await run(
    engine.startMemorization({ surah: 1, fromAyah: 2, toAyah: 7 }, SESSION_POLICY),
    new EventTranslator(words, true),
    skipped,
  );
  const skipNotes = skip.filter((e) => e.type === "correction_note").map((e) => e.issue as { ayah: number; kind: string });
  console.log("  1:3 ausgelassen, Fehler:", JSON.stringify(skipNotes));
  check(skipNotes.some((i) => i.ayah === 3), "ausgelassene Aya 1:3 wird gemeldet");
}

if (failures > 0) {
  console.error(`${failures} Prüfung(en) fehlgeschlagen`);
  process.exit(1);
}
console.log("Smoke-Test bestanden");
process.exit(0);
