package com.quran.mobile.recitation.tilawa

import android.Manifest
import android.app.Activity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import com.quran.data.model.SuraAyah
import com.quran.mobile.recitation.R
import com.quran.recitation.events.RecitationModePresenter
import com.quran.recitation.events.RecitationModePresenter.EngineStatus
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject

/**
 * „Stelle per Rezitation finden“: hört zu, bis eine Aya sicher erkannt ist,
 * und liefert sie zurück (z. B. zum Springen aus der Suren-Liste).
 */
class TilawaAyahFinder @Inject constructor(
  private val engineHolder: TilawaEngineHolder,
  private val modePresenter: RecitationModePresenter,
) : TilawaEngine.Listener {

  private var activity: Activity? = null
  private var dialog: AlertDialog? = null
  private var statusView: TextView? = null
  private var progressView: ProgressBar? = null
  private var scope: CoroutineScope? = null
  private var isListening = false
  private var onFound: ((SuraAyah) -> Unit)? = null

  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  fun start(activity: Activity, onFound: (SuraAyah) -> Unit) {
    cancel()
    this.activity = activity
    this.onFound = onFound
    engineHolder.addListener(this)
    engineHolder.obtain(activity)

    val padding = (20 * activity.resources.displayMetrics.density).toInt()
    val status = TextView(activity).apply { textSize = 16f }
    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 100
    }
    val content = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding / 2, padding, 0)
      addView(status)
      addView(progress)
    }
    statusView = status
    progressView = progress

    dialog = AlertDialog.Builder(activity)
      .setTitle(R.string.tilawa_finder_title)
      .setView(content)
      .setNegativeButton(R.string.tilawa_cancel) { _, _ -> cancel() }
      .setOnCancelListener { cancel() }
      .show()

    scope = MainScope().also { scope ->
      modePresenter.engineStatusFlow
        .onEach { onStatus(it) }
        .launchIn(scope)
    }
  }

  /** Beim Zerstören der Activity: Dialog schließen und WebView vom Fenster lösen. */
  fun release(activity: Activity) {
    cancel()
    engineHolder.detach(activity)
  }

  fun cancel() {
    if (isListening) engineHolder.current()?.stopRecording(flush = false)
    isListening = false
    scope?.cancel()
    scope = null
    engineHolder.removeListener(this)
    dialog?.setOnCancelListener(null)
    dialog?.dismiss()
    dialog = null
    statusView = null
    progressView = null
    activity = null
    onFound = null
  }

  @Suppress("MissingPermission") // Berechtigung wird vor start() geprüft
  private fun onStatus(status: EngineStatus) {
    val activity = activity ?: return
    val progress = progressView ?: return
    when (status) {
      is EngineStatus.Loading -> {
        progress.visibility = android.view.View.VISIBLE
        progress.isIndeterminate = status.percent < 0
        if (status.percent >= 0) progress.progress = status.percent
        statusView?.text = if (status.percent >= 0) {
          activity.getString(R.string.tilawa_status_loading, status.percent)
        } else {
          activity.getString(R.string.tilawa_status_preparing)
        }
      }
      EngineStatus.Ready -> {
        progress.visibility = android.view.View.GONE
        statusView?.setText(R.string.tilawa_finder_listening)
        if (!isListening) {
          val config = JSONObject()
            .put("mode", "tracking")
            .put("autoContinue", true)
            .put("strictOmissions", false)
            .put("expected", JSONObject.NULL)
          engineHolder.current()?.let { engine ->
            engine.startRecording(config.toString())
            isListening = engine.recording
          }
        }
      }
      is EngineStatus.Error -> {
        progress.visibility = android.view.View.GONE
        statusView?.text = activity.getString(R.string.tilawa_error, status.message)
      }
      EngineStatus.Idle -> Unit
    }
  }

  override fun onTilawaMessage(message: JSONObject) {
    if (!isListening || message.optString("type") != "event") return
    val event = message.getJSONObject("event")
    if (event.optString("type") != "verse_match") return
    val ayah = SuraAyah(event.getInt("surah"), event.getInt("ayah"))
    val callback = onFound
    cancel()
    callback?.invoke(ayah)
  }
}
