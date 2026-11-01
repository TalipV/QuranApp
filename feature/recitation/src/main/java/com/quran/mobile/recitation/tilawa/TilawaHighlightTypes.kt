package com.quran.mobile.recitation.tilawa

import com.quran.data.model.highlight.HighlightType
import com.quran.mobile.recitation.R

/** Eigene Markierungstypen (IDs weit weg von denen der App). */
object TilawaHighlightTypes {
  /** Aktuell rezitierte Aya */
  val AYAH = HighlightType(9_001, R.color.tilawa_ayah, HighlightType.Mode.HIGHLIGHT, isSingle = true,
    nightColorResId = R.color.tilawa_ayah_night)

  /** Aktuell rezitiertes Wort */
  val WORD = HighlightType(9_002, R.color.tilawa_word, HighlightType.Mode.UNDERLINE, isSingle = true)

  /** Mögliche Fehler: Wort bzw. Aya wird rot eingefärbt */
  val MISTAKE = HighlightType(9_003, R.color.tilawa_mistake, HighlightType.Mode.COLOR,
    nightColorResId = R.color.tilawa_mistake_night)

  /** Hifẓ-Modus: noch nicht rezitierte Ayat ausblenden */
  val HIDDEN = HighlightType(9_004, R.color.tilawa_ayah, HighlightType.Mode.HIDE)

  val all = listOf(AYAH, WORD, MISTAKE, HIDDEN)
}
