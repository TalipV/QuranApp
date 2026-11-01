package com.quran.mobile.recitation.presenter

import com.quran.data.core.QuranInfo
import com.quran.data.di.QuranPageScope
import com.quran.data.model.SuraAyah
import com.quran.data.model.highlight.HighlightInfo
import com.quran.mobile.recitation.tilawa.TilawaHighlightTypes
import com.quran.mobile.recitation.tilawa.TilawaRecitationState
import com.quran.recitation.presenter.RecitationHighlightsPresenter
import com.quran.recitation.presenter.RecitationHighlightsPresenter.HighlightAction
import com.quran.recitation.presenter.RecitationHighlightsPresenter.RecitationPage
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Überträgt den Tilawa-Zustand (Aya, Wort, Fehler, Ausblenden) auf die sichtbaren Seiten. */
@QuranPageScope
class RecitationHighlightsPresenterImpl @Inject constructor(
  private val quranInfo: QuranInfo,
  private val state: TilawaRecitationState,
) : RecitationHighlightsPresenter {

  private var page: RecitationPage? = null
  private var scope: CoroutineScope? = null

  override fun bind(what: RecitationPage) {
    scope?.cancel()
    page = what
    scope = MainScope().also { scope ->
      state.flow.onEach { apply(it) }.launchIn(scope)
    }
  }

  override fun unbind(what: RecitationPage) {
    if (page === what) {
      scope?.cancel()
      scope = null
      page = null
    }
  }

  override fun refresh() {
    apply(state.flow.value)
  }

  private fun apply(snapshot: TilawaRecitationState.Snapshot) {
    val page = page ?: return
    val actions = buildList {
      TilawaHighlightTypes.all.forEach { add(HighlightAction.UnhighlightAll(it)) }

      snapshot.ayah?.let { ayah ->
        add(HighlightAction.Highlight(HighlightInfo(ayah.sura, ayah.ayah, -1, TilawaHighlightTypes.AYAH, false)))
        if (snapshot.word > 0) {
          add(HighlightAction.Highlight(
            HighlightInfo(ayah.sura, ayah.ayah, snapshot.word, TilawaHighlightTypes.WORD, false)
          ))
        }
      }

      snapshot.mistakes.forEach { mistake ->
        add(HighlightAction.Highlight(
          HighlightInfo(mistake.ayah.sura, mistake.ayah.ayah, mistake.word, TilawaHighlightTypes.MISTAKE, false)
        ))
      }

      if (snapshot.hideUpcoming) {
        val lastRecited = snapshot.ayah ?: snapshot.sessionStart?.let { previous(it) }
        page.pageNumbers.forEach { pageNumber ->
          upcomingAyahsOnPage(pageNumber, lastRecited).forEach { ayah ->
            add(HighlightAction.Highlight(HighlightInfo(ayah.sura, ayah.ayah, -1, TilawaHighlightTypes.HIDDEN, false)))
          }
        }
      }
    }
    page.applyHighlights(actions)
  }

  /** Alle Ayat der Seite, die nach [lastRecited] kommen (alle, wenn noch nichts erkannt). */
  private fun upcomingAyahsOnPage(pageNumber: Int, lastRecited: SuraAyah?): List<SuraAyah> {
    val bounds = quranInfo.getPageBounds(pageNumber)
    val start = SuraAyah(bounds[0], bounds[1])
    val end = SuraAyah(bounds[2], bounds[3])
    val result = mutableListOf<SuraAyah>()
    var current = start
    while (current <= end) {
      if (lastRecited == null || current > lastRecited) result.add(current)
      current = next(current) ?: break
    }
    return result
  }

  private fun next(ayah: SuraAyah): SuraAyah? = when {
    ayah.ayah < quranInfo.getNumberOfAyahs(ayah.sura) -> SuraAyah(ayah.sura, ayah.ayah + 1)
    ayah.sura < 114 -> SuraAyah(ayah.sura + 1, 1)
    else -> null
  }

  private fun previous(ayah: SuraAyah): SuraAyah? = when {
    ayah.ayah > 1 -> SuraAyah(ayah.sura, ayah.ayah - 1)
    ayah.sura > 1 -> SuraAyah(ayah.sura - 1, quranInfo.getNumberOfAyahs(ayah.sura - 1))
    else -> null
  }
}
