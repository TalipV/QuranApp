package com.quran.labs.androidquran.ui.listener


interface AudioBarRecitationListener {
  fun onRecitationPressed()
  fun onRecitationLongPressed()
  fun onRecitationTranscriptPressed()
  fun onHideVersesPressed()
  fun onEndRecitationSessionPressed()
  fun onPlayRecitationPressed()
  fun onPauseRecitationPressed()

  /** QuranApp: Rezitationsmodus ein-/ausschalten (unten rechts in der Audioleiste) */
  fun onRecitationModeToggled()
}
