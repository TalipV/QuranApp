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

/**
 * Eine neu erkannte Stelle zählt erst, wenn die Rezitation dort so viele Wörter fortlaufend
 * weitergeht. Grund: Einleitungen wie „aʿūḏu bi-llāhi …“ (passt zu 2:67) oder die Basmala
 * (passt zu 1:1, 27:30) werden sonst als Quranstelle genommen. Die eigentliche Rezitation geht
 * danach woanders weiter, die Scheintreffer werden also nie bestätigt.
 */
const CONFIRM_WORDS = 2;
/** So weit darf ein Positionsschritt springen und trotzdem als „fortlaufend“ gelten (Wörter). */
const MAX_STEP = 4;

/** Wörter des Quran aus der Referenz: Wortlaut je Aya und fortlaufende Wortnummer über alle Ayat. */
export class QuranWords {
  private readonly ayahs = new Map<string, { words: string[]; first: number }>();

  constructor(reference: ReferenceAsset) {
    let index = 0;
    for (const a of reference.ayahs) {
      this.ayahs.set(`${a.s}:${a.a}`, { words: a.w, first: index });
      index += a.w.length;
    }
  }

  words(surah: number, ayah: number): string[] | undefined {
    return this.ayahs.get(`${surah}:${ayah}`)?.words;
  }

  /** Fortlaufende Nummer des Wortes (1-basiertes `word`), -1 wenn unbekannt. */
  index(w: WordRef): number {
    const a = this.ayahs.get(`${w.surah}:${w.ayah}`);
    return a ? a.first + w.word - 1 : -1;
  }
}

export type BridgeEvent = Record<string, unknown> & { type: string };

export interface Passage {
  surah: number;
  fromAyah: number;
  toAyah: number;
}

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
 * - Neue Stellen zählen erst nach [CONFIRM_WORDS] fortlaufenden Wörtern (siehe dort).
 * - Mit Passage (Korrekturmodus) zählen nur Stellen innerhalb der Passage.
 * - Fehler vor der ersten bestätigten Stelle werden verworfen: Wer nach einer Pause (oder mitten
 *   auf der Seite) einsetzt, hat die Wörter davor nicht „ausgelassen“.
 */
export class EventTranslator {
  private firstLock: WordRef | null = null;
  /** Fortlaufende Nummer des zuletzt bestätigten Wortes */
  private current: number | null = null;
  /** Neue Stelle, die noch bestätigt werden muss */
  private pending: { start: number; startWord: WordRef; last: number } | null = null;
  private lastWord: WordRef | null = null;
  private lastAyahKey: string | null = null;
  private readonly reported = new Set<string>();
  /** Bestätigte Fehler, die vor der ersten bestätigten Stelle ankamen – werden danach geprüft. */
  private held: CheckerEvent[] = [];

  constructor(
    private readonly quran: QuranWords,
    private readonly reportMistakes: boolean,
    private readonly passage: Passage | null = null,
  ) {}

  translate(event: EngineEvent): BridgeEvent[] {
    if (event.type === "position") return this.position(event);
    if (event.type === "checker") return this.checker(event);
    return [];
  }

  private inPassage(w: WordRef): boolean {
    const p = this.passage;
    return !p || (w.surah === p.surah && w.ayah >= p.fromAyah && w.ayah <= p.toAyah);
  }

  private position(e: PositionEvent): BridgeEvent[] {
    const best = e.observed[0];
    if (!best) return [];
    const continues = e.state === "locked" && this.inPassage(best.word) && this.isContinuation(best.word);
    if (!continues && (e.state !== "locked" || best.confidence < MIN_LOCK_CONFIDENCE || !this.inPassage(best.word))) {
      if (this.current === null && (e.state === "acquiring" || e.state === "ambiguous")) {
        return [{
          type: "verse_candidate",
          stable: false,
          candidates: e.observed.filter((c) => this.inPassage(c.word)).slice(0, 3).map((c, rank) => ({
            surah: c.word.surah, ayah: c.word.ayah, confidence: c.confidence, rank,
          })),
        }];
      }
      return [];
    }

    const w = best.word;
    const g = this.quran.index(w);
    if (g < 0) return [];

    // fortlaufend zur bestätigten Stelle (ein Wort zurück erlaubt: Wiederholung des letzten Wortes)
    if (this.current !== null && g >= this.current - 1 && g <= this.current + MAX_STEP) {
      return this.accept(w, g);
    }
    // neue Stelle: erst nach ein paar fortlaufenden Wörtern bestätigen
    // Mit Passage (Korrekturmodus) schließt schon die Passage Einleitungen wie 2:67 / 1:1 aus.
    const confirmWords = this.passage ? 0 : CONFIRM_WORDS;
    const p = this.pending;
    if (p && g > p.last && g - p.last <= MAX_STEP) {
      p.last = g;
      if (g - p.start >= confirmWords) return this.accept(w, g, p.startWord);
    } else if (!p || g !== p.last) {
      this.pending = { start: g, startWord: { ...w }, last: g };
      if (confirmWords === 0) return this.accept(w, g);
    }
    return [];
  }

  /**
   * Setzt die bestätigte Stelle fort – dann zählt auch geringe Sicherheit (direkt nach einer
   * ausgelassenen Aya meldet Tilawa die Wörter unsicher). Im Korrekturmodus genügt auch die
   * noch offene Stelle, weil die Passage Einleitungen schon ausschließt.
   */
  private isContinuation(w: WordRef): boolean {
    const g = this.quran.index(w);
    if (g < 0) return false;
    if (this.current !== null && g >= this.current - 1 && g <= this.current + MAX_STEP) return true;
    const p = this.pending;
    return this.passage !== null && p !== null && g > p.last && g - p.last <= MAX_STEP;
  }

  /** [from]: Beginn einer gerade bestätigten neuen Stelle – ab dort gilt sie (rückwirkend). */
  private accept(w: WordRef, g: number, from?: WordRef): BridgeEvent[] {
    this.pending = null;
    this.current = g;
    if (!this.firstLock) this.firstLock = { ...(from ?? w) };
    const out: BridgeEvent[] = [];
    if (from && (from.surah !== w.surah || from.ayah !== w.ayah)) {
      // die Stelle begann schon in der Aya davor: diese zuerst melden (z. B. für „Stelle finden“)
      this.lastAyahKey = `${from.surah}:${from.ayah}`;
      out.push({ type: "verse_match", surah: from.surah, ayah: from.ayah });
    }
    const ayahKey = `${w.surah}:${w.ayah}`;
    if (ayahKey !== this.lastAyahKey) {
      this.lastAyahKey = ayahKey;
      out.push({ type: "verse_match", surah: w.surah, ayah: w.ayah });
    }
    const last = this.lastWord;
    if (!last || last.surah !== w.surah || last.ayah !== w.ayah || last.word !== w.word) {
      this.lastWord = { ...w };
      out.push({
        type: "word_progress",
        surah: w.surah,
        ayah: w.ayah,
        word_index: w.word - 1,
        total_words: this.quran.words(w.surah, w.ayah)?.length ?? 0,
      });
    }
    const held = this.held;
    this.held = [];
    for (const e of held) out.push(...this.checker(e));
    return out;
  }

  private checker(e: CheckerEvent): BridgeEvent[] {
    if (!this.reportMistakes || e.state !== "confirmed" || this.reported.has(e.id)) return [];
    const kind = KIND[e.errorClass];
    if (!kind) return [];
    if (!this.firstLock) {
      if (this.held.length < 20) this.held.push(e);
      return [];
    }
    if (before(e.location, this.firstLock) || !this.inPassage(e.location)) return [];
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
        word_text: this.quran.words(surah, ayah)?.[word - 1] ?? "",
        source: "tilawa-1",
      },
    }];
  }
}
