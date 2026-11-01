package com.quran.mobile.recitation.presenter

import com.quran.data.di.AppScope
import com.quran.recitation.events.RecitationModePresenter
import com.quran.recitation.presenter.RecitationSettings
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
class RecitationSettingsImpl @Inject constructor(
  private val modePresenter: RecitationModePresenter,
) : RecitationSettings {
  override fun isRecitationEnabled() = true

  /** Buch-Symbol in der Rezitationsleiste: noch nicht rezitierte Ayat aus-/einblenden (Hifẓ). */
  override fun toggleAyahVisibility() = modePresenter.toggleHideAyahs()
}
