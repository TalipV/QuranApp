package com.quran.mobile.recitation.presenter

import com.quran.data.di.AppScope
import com.quran.mobile.recitation.tilawa.TilawaRecitationState
import com.quran.recitation.presenter.RecitationSettings
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
class RecitationSettingsImpl @Inject constructor(
  private val state: TilawaRecitationState,
) : RecitationSettings {
  override fun isRecitationEnabled() = true

  /** Buch-Symbol in der Audioleiste: noch nicht rezitierte Ayat aus-/einblenden (Hifẓ). */
  override fun toggleAyahVisibility() = state.toggleHideUpcoming()
}
