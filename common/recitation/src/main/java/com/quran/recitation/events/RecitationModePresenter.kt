package com.quran.recitation.events

import com.quran.data.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * QuranApp: Zustand des Rezitationsmodus, getrennt von der Standard-App.
 *
 * - [modeFlow]: Rezitationsmodus im Leser aktiv (Audioleiste zeigt nur Rezitations-Steuerung)
 * - [engineStatusFlow]: Ladezustand der Erkennung (für Fortschrittsanzeige)
 * - [hideAyahsFlow]: Hifẓ-Modus – noch nicht rezitierte Ayat ausblenden
 * - [backlogFlow]: Verarbeitungsrückstand der Erkennung (für „wird verarbeitet“)
 */
@SingleIn(AppScope::class)
class RecitationModePresenter @Inject constructor() {

  sealed interface EngineStatus {
    data object Idle : EngineStatus
    /** [percent] 0–100 beim Laden des Modells, -1 während der Initialisierung */
    data class Loading(val percent: Int) : EngineStatus
    data object Ready : EngineStatus
    data class Error(val message: String) : EngineStatus
  }

  private val _modeFlow = MutableStateFlow(false)
  private val _engineStatusFlow = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
  private val _hideAyahsFlow = MutableStateFlow(false)
  private val _backlogFlow = MutableStateFlow(0f)
  private val _trackingLagFlow = MutableStateFlow(0f)

  val modeFlow: StateFlow<Boolean> = _modeFlow.asStateFlow()
  val engineStatusFlow: StateFlow<EngineStatus> = _engineStatusFlow.asStateFlow()
  val hideAyahsFlow: StateFlow<Boolean> = _hideAyahsFlow.asStateFlow()

  /** Sekunden Audio, die die Erkennung noch nicht ausgewertet hat (0 = hält Schritt). */
  val backlogFlow: StateFlow<Float> = _backlogFlow.asStateFlow()

  fun onBacklog(seconds: Float) {
    _backlogFlow.value = seconds.coerceAtLeast(0f)
  }

  /**
   * Sekunden hörbarer Sprache seit dem letzten erkannten Fortschritt (neues Wort / neue Aya).
   * Wächst der Wert, kommt die Erkennung nicht mehr mit – die Leiste warnt dann.
   */
  val trackingLagFlow: StateFlow<Float> = _trackingLagFlow.asStateFlow()

  fun onTrackingLag(seconds: Float) {
    _trackingLagFlow.value = seconds.coerceAtLeast(0f)
  }

  fun isModeActive(): Boolean = modeFlow.value

  fun setMode(active: Boolean) {
    _modeFlow.value = active
    if (!active) _hideAyahsFlow.value = false
  }

  fun toggleMode() = setMode(!modeFlow.value)

  fun onEngineStatus(status: EngineStatus) {
    _engineStatusFlow.value = status
  }

  fun setHideAyahs(hide: Boolean) {
    _hideAyahsFlow.value = hide
  }

  fun toggleHideAyahs() = setHideAyahs(!hideAyahsFlow.value)
}
