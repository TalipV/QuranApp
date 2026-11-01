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
    /** Startpunkt der Sitzung; null = keine Sitzung */
    val sessionStart: SuraAyah? = null,
    val ayah: SuraAyah? = null,
    /** 1-basierte Wortposition des gerade rezitierten Wortes, -1 = unbekannt */
    val word: Int = -1,
    val mistakes: List<Mistake> = emptyList(),
  )

  private val _flow = MutableStateFlow(Snapshot())
  val flow: StateFlow<Snapshot> = _flow.asStateFlow()

  fun startSession(start: SuraAyah) {
    _flow.value = Snapshot(sessionStart = start)
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

  fun clear() {
    _flow.value = Snapshot()
  }
}
