package com.quran.mobile.recitation.tilawa

import android.app.Activity
import android.view.ViewGroup
import com.quran.data.di.AppScope
import com.quran.recitation.events.RecitationModePresenter
import com.quran.recitation.events.RecitationModePresenter.EngineStatus
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.json.JSONObject

/**
 * Hält genau eine Tilawa-Engine für die ganze App, damit das Modell nur einmal geladen
 * wird (Suren-Liste und Leser teilen sie). Meldet den Ladezustand an [RecitationModePresenter].
 */
@SingleIn(AppScope::class)
class TilawaEngineHolder @Inject constructor(
  private val modePresenter: RecitationModePresenter,
) : TilawaEngine.Listener {

  private var engine: TilawaEngine? = null
  private val listeners = LinkedHashSet<TilawaEngine.Listener>()

  val isReady: Boolean
    get() = modePresenter.engineStatusFlow.value == EngineStatus.Ready

  /** Liefert die Engine (lädt sie beim ersten Aufruf) und hängt sie ans Fenster von [activity]. */
  fun obtain(activity: Activity): TilawaEngine {
    val engine = engine ?: TilawaEngine(activity.applicationContext, this).also {
      engine = it
      modePresenter.onEngineStatus(EngineStatus.Loading(0))
    }
    attach(activity, engine)
    return engine
  }

  fun current(): TilawaEngine? = engine

  fun detach(activity: Activity) {
    val webView = engine?.webView ?: return
    if (webView.parent === activity.window.decorView) {
      (webView.parent as ViewGroup).removeView(webView)
    }
  }

  fun addListener(listener: TilawaEngine.Listener) {
    listeners.add(listener)
  }

  fun removeListener(listener: TilawaEngine.Listener) {
    listeners.remove(listener)
  }

  /** Unsichtbar ans Fenster hängen, damit die WebView nicht gedrosselt wird. */
  private fun attach(activity: Activity, engine: TilawaEngine) {
    val decor = activity.window.decorView as? ViewGroup ?: return
    val webView = engine.webView
    if (webView.parent === decor) return
    (webView.parent as? ViewGroup)?.removeView(webView)
    webView.alpha = 0f
    decor.addView(webView, ViewGroup.LayoutParams(1, 1))
  }

  override fun onTilawaMessage(message: JSONObject) {
    when (message.optString("type")) {
      "loading" -> modePresenter.onEngineStatus(EngineStatus.Loading(message.optInt("percent", -1)))
      "ready" -> modePresenter.onEngineStatus(EngineStatus.Ready)
      "backlog" -> modePresenter.onBacklog(message.optDouble("seconds", 0.0).toFloat())
      "error" -> if (!isReady) {
        modePresenter.onEngineStatus(EngineStatus.Error(message.optString("message").lineSequence().first()))
      }
    }
    listeners.toList().forEach { it.onTilawaMessage(message) }
  }
}
