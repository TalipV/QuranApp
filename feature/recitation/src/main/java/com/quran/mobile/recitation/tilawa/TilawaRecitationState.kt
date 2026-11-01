package com.quran.mobile.recitation.tilawa

import com.quran.data.di.AppScope
import com.quran.data.model.SuraAyah
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Gemeinsamer Zustand der laufenden Rezitation – gelesen von den Seiten-Markierungen. */
@SingleIn(AppScope::class)
class TilawaRecitationState @Inject constructor() {

  data class Mistake(
    val ayah: SuraAyah,
    /** 1-basierte Wortposition wie in der App, -1 = ganze Aya */
    val word: Int,
    val kind: String,
  )

  data class Snapshot(
    val ayah: SuraAyah? = null,
    /** 1-basierte Wortposition des gerade rezitierten Wortes, -1 = unbekannt */
    val word: Int = -1,
    val mistakes: List<Mistake> = emptyList(),
    /** Hifẓ-Modus: noch nicht rezitierte Ayat auf der Seite ausblenden */
    val hideUpcoming: Boolean = false,
    /** Ab hier wurde die Sitzung gestartet (für das Ausblenden vor der ersten Erkennung) */
    val sessionStart: SuraAyah? = null,
  )

  private val _flow = MutableStateFlow(Snapshot())
  val flow: StateFlow<Snapshot> = _flow.asStateFlow()

  fun startSession(start: SuraAyah) {
    _flow.update { Snapshot(sessionStart = start, hideUpcoming = it.hideUpcoming) }
  }

  fun setPosition(ayah: SuraAyah, word: Int) {
    _flow.update { if (it.ayah == ayah && it.word == word) it else it.copy(ayah = ayah, word = word) }
  }

  fun addMistakes(mistakes: List<Mistake>) {
    if (mistakes.isEmpty()) return
    _flow.update { state ->
      val known = state.mistakes.map { it.ayah to it.word }.toSet()
      val fresh = mistakes.filter { (it.ayah to it.word) !in known }
      if (fresh.isEmpty()) state else state.copy(mistakes = state.mistakes + fresh)
    }
  }

  fun toggleHideUpcoming() {
    _flow.update { it.copy(hideUpcoming = !it.hideUpcoming) }
  }

  fun clear() {
    _flow.update { Snapshot(hideUpcoming = it.hideUpcoming) }
  }
}
