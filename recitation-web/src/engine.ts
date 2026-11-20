/**
 * Gemeinsamer Kern von WebView-Brücke (main.ts) und Smoke-Test (smoke.ts):
 * Tilawa-Engine aufbauen und ihre Ereignisse in das Nachrichtenformat der App übersetzen.
 *
 * Die App (Kotlin) kennt weiterhin die Nachrichten aus der 0.x-Zeit:
 *   verse_match, verse_candidate, word_progress, correction_note
 * So bleibt die Android-Seite stabil, wenn Tilawa sich wieder ändert – angepasst wird nur hier.
 */
import {
  MODEL_REGISTRY,
  createEngine,
  loadReferenceIndex,
  ortBackend,
  type CheckerEvent,
  type Engine,
  type EngineEvent,
  type ModelTier,
  type OrtModule,
  type PhraseAsset,
  type PositionEvent,
  type ReferenceAsset,
  type SessionPolicy,
  type WordRef,
} from "@tilawa/core";

import modelConfig from "./model.json";

/**
 * `lite` (25 MB) ist für Handys ohne WebGPU gedacht und deutlich schneller als `full` (68 MB).
 * Steht in src/model.json, damit build.mjs dasselbe Modell herunterlädt.
 */
export const MODEL_TIER = modelConfig.tier as "lite" | "full";

export function modelTier(): ModelTier {
  const tier = MODEL_REGISTRY.tiers.find((t) => t.tier === MODEL_TIER);
  if (!tier) throw new Error(`Tilawa-Modell „${MODEL_TIER}“ fehlt in der Registry`);
  return tier;
}

export interface EngineInput {
  ort: OrtModule;
  /** Nur zur Anzeige, z. B. "onnxruntime-web/wasm" */
  backendName: string;
  sessionOptions?: Record<string, unknown>;
  model: Uint8Array;
  reference: ReferenceAsset;
  phrases: PhraseAsset;
}

export async function buildEngine(input: EngineInput): Promise<Engine> {
  const tier = modelTier();
  const backend = await ortBackend(input.ort, input.model, input.backendName, input.sessionOptions);
  return createEngine({
    reference: loadReferenceIndex(input.reference),
    phrases: input.phrases,
    model: { manifest: tier.manifest, backend, supported: tier.supported },
    info: { tier: tier.tier, backend: input.backendName, modelRelease: tier.version, modelSha256: tier.sha256 },
  });
}

/** Fehler werden bestätigt, sobald das nächste Wort beginnt (Standard wäre 2,5 s später). */
export const SESSION_POLICY: SessionPolicy = { confirm: { after: "next-word" } };

/** Unter dieser Sicherheit gilt eine Position noch nicht als erkannt. */
const MIN_LOCK_CONFIDENCE = 0.5;

/** Uthmani-Wörter je Aya aus der Referenz (für Wortanzahl und Wortlaut im Fehlerhinweis). */
export function wordLookup(reference: ReferenceAsset): (surah: number, ayah: number) => string[] | undefined {
  const map = new Map<string, string[]>();
  for (const a of reference.ayahs) map.set(`${a.s}:${a.a}`, a.w);
  return (surah, ayah) => map.get(`${surah}:${ayah}`);
}

export type BridgeEvent = Record<string, unknown> & { type: string };

function before(a: WordRef, b: WordRef): boolean {
  if (a.surah !== b.surah) return a.surah < b.surah;
  if (a.ayah !== b.ayah) return a.ayah < b.ayah;
  return a.word < b.word;
}

/** Tilawa-Fehlerklasse → Fehlerart der App (Texte dazu in strings_tilawa.xml). */
const KIND: Record<string, string> = {
  wrong_word: "possible_substitution",
  omitted_word: "possible_omission",
  added_word: "possible_addition",
  skipped_passage: "possible_skipped_passage",
};

/**
 * Übersetzt die Ereignisse einer Sitzung. Eine Instanz pro Sitzung.
 *
 * Fehler, die vor der ersten sicher erkannten Stelle liegen, werden verworfen: Wer nach einer
 * Pause (oder mitten auf der Seite) einsetzt, hat die Wörter davor nicht „ausgelassen“.
 */
export class EventTranslator {
  private firstLock: WordRef | null = null;
  private lastWord: WordRef | null = null;
  private lastAyahKey: string | null = null;
  private readonly reported = new Set<string>();

  constructor(
    private readonly words: (surah: number, ayah: number) => string[] | undefined,
    private readonly reportMistakes: boolean,
  ) {}

  translate(event: EngineEvent): BridgeEvent[] {
    if (event.type === "position") return this.position(event);
    if (event.type === "checker") return this.checker(event);
    return [];
  }

  private position(e: PositionEvent): BridgeEvent[] {
    const best = e.observed[0];
    if (!best) return [];
    if (e.state !== "locked" || best.confidence < MIN_LOCK_CONFIDENCE) {
      if (e.state === "acquiring" || e.state === "ambiguous") {
        return [{
          type: "verse_candidate",
          stable: false,
          candidates: e.observed.slice(0, 3).map((c, rank) => ({
            surah: c.word.surah, ayah: c.word.ayah, confidence: c.confidence, rank,
          })),
        }];
      }
      return [];
    }

    const w = best.word;
    if (!this.firstLock) this.firstLock = { ...w };
    const out: BridgeEvent[] = [];
    const ayahKey = `${w.surah}:${w.ayah}`;
    if (ayahKey !== this.lastAyahKey) {
      this.lastAyahKey = ayahKey;
      out.push({ type: "verse_match", surah: w.surah, ayah: w.ayah, confidence: best.confidence });
    }
    const last = this.lastWord;
    if (!last || last.surah !== w.surah || last.ayah !== w.ayah || last.word !== w.word) {
      this.lastWord = { ...w };
      out.push({
        type: "word_progress",
        surah: w.surah,
        ayah: w.ayah,
        word_index: w.word - 1,
        total_words: this.words(w.surah, w.ayah)?.length ?? 0,
      });
    }
    return out;
  }

  private checker(e: CheckerEvent): BridgeEvent[] {
    if (!this.reportMistakes || e.state !== "confirmed" || this.reported.has(e.id)) return [];
    const kind = KIND[e.errorClass];
    if (!kind) return [];
    if (!this.firstLock || before(e.location, this.firstLock)) return [];
    this.reported.add(e.id);
    const { surah, ayah, word } = e.location;
    return [{
      type: "correction_note",
      issue: {
        surah,
        ayah,
        word: Math.max(0, word - 1),
        words: 1,
        kind,
        word_text: this.words(surah, ayah)?.[word - 1] ?? "",
        source: "tilawa-1",
      },
    }];
  }
}
