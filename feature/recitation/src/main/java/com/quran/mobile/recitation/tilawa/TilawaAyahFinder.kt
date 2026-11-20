package com.quran.mobile.recitation.tilawa

import android.Manifest
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import com.quran.data.model.SuraAyah
import com.quran.mobile.recitation.R
import com.quran.mobile.recitation.tilawa.TilawaListeningIndicator.Phase
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
 *
 * Der Dialog zeigt jederzeit, was gerade passiert: Laden → bereit → höre zu →
 * werte aus → gefunden (siehe [TilawaListeningIndicator]).
 */
class TilawaAyahFinder @Inject constructor(
  private val engineHolder: TilawaEngineHolder,
  private val modePresenter: RecitationModePresenter,
) : TilawaEngine.Listener {

  private var activity: Activity? = null
  private var dialog: AlertDialog? = null
  private var indicator: TilawaListeningIndicator? = null
  private var statusView: TextView? = null
  private var detailView: TextView? = null
  private var progressView: ProgressBar? = null
  private var scope: CoroutineScope? = null
  private var isListening = false
  private var hasError = false
  private var onFound: ((SuraAyah) -> Unit)? = null

  /** Zeitpunkt der letzten hörbaren Sprache; 0 = noch nichts gehört. */
  private var lastSpeechAt = 0L
  private var heardSomething = false
  private var candidateText: String? = null

  private val handler = Handler(Looper.getMainLooper())
  private val ticker = object : Runnable {
    override fun run() {
      updateListeningPhase()
      handler.postDelayed(this, TICK_MS)
    }
  }

  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  fun start(activity: Activity, onFound: (SuraAyah) -> Unit) {
    cancel()
    this.activity = activity
    this.onFound = onFound
    engineHolder.addListener(this)
    engineHolder.obtain(activity)

    val density = activity.resources.displayMetrics.density
    val padding = (20 * density).toInt()
    val indicator = TilawaListeningIndicator(activity, themeAccent(activity))
    val status = TextView(activity).apply {
      textSize = 17f
      gravity = Gravity.CENTER
      setPadding(0, padding / 2, 0, 0)
    }
    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 100
    }
    val detail = TextView(activity).apply {
      textSize = 15f
      gravity = Gravity.CENTER
      visibility = View.GONE
    }
    val content = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(padding, padding / 2, padding, 0)
      addView(indicator, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
      ))
      addView(status, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
      ))
      addView(progress, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
      ))
      addView(detail, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
      ))
    }
    this.indicator = indicator
    statusView = status
    progressView = progress
    detailView = detail

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
    hasError = false
    lastSpeechAt = 0L
    heardSomething = false
    candidateText = null
    handler.removeCallbacksAndMessages(null)
    scope?.cancel()
    scope = null
    engineHolder.removeListener(this)
    dialog?.setOnCancelListener(null)
    dialog?.dismiss()
    dialog = null
    indicator = null
    statusView = null
    detailView = null
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
        indicator?.phase = Phase.LOADING
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = status.percent < 0
        if (status.percent >= 0) progress.progress = status.percent
        statusView?.text = if (status.percent >= 0) {
          activity.getString(R.string.tilawa_status_loading, status.percent)
        } else {
          activity.getString(R.string.tilawa_status_preparing)
        }
      }
      EngineStatus.Ready -> {
        if (!isListening && !hasError) {
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
        if (isListening) {
          handler.removeCallbacks(ticker)
          handler.post(ticker)
        }
      }
      is EngineStatus.Error -> showError(status.message)
      EngineStatus.Idle -> Unit
    }
  }

  /** Wählt anhand von Pegel und Zeit seit der letzten Sprache den sichtbaren Zustand. */
  private fun updateListeningPhase() {
    val activity = activity ?: return
    if (!isListening || hasError) return
    val sinceSpeech = SystemClock.uptimeMillis() - lastSpeechAt
    val progress = progressView ?: return
    when {
      lastSpeechAt > 0 && sinceSpeech < HEARING_HOLD_MS -> {
        indicator?.phase = Phase.HEARING
        statusView?.setText(R.string.tilawa_finder_hearing)
        showIndeterminate(progress, true)
      }
      lastSpeechAt > 0 && sinceSpeech < PROCESSING_HOLD_MS -> {
        indicator?.phase = Phase.PROCESSING
        statusView?.setText(R.string.tilawa_finder_processing)
        showIndeterminate(progress, true)
      }
      else -> {
        indicator?.phase = Phase.READY
        statusView?.setText(
          if (heardSomething) R.string.tilawa_finder_not_found_yet else R.string.tilawa_finder_listening
        )
        showIndeterminate(progress, false)
      }
    }
    detailView?.let { detail ->
      val text = candidateText
      detail.visibility = if (text != null) View.VISIBLE else View.GONE
      if (text != null) detail.text = text
    }
  }

  private fun showIndeterminate(progress: ProgressBar, visible: Boolean) {
    progress.isIndeterminate = true
    progress.visibility = if (visible) View.VISIBLE else View.INVISIBLE
  }

  private fun showError(message: String) {
    val activity = activity ?: return
    hasError = true
    handler.removeCallbacks(ticker)
    indicator?.phase = Phase.ERROR
    progressView?.visibility = View.GONE
    statusView?.text = activity.getString(R.string.tilawa_error, message)
  }

  override fun onTilawaMessage(message: JSONObject) {
    when (message.optString("type")) {
      "level" -> if (isListening) onLevel(message.optDouble("rms", 0.0).toFloat())
      "error" -> if (isListening) {
        showError(message.optString("message").lineSequence().first())
        engineHolder.current()?.stopRecording(flush = false)
        isListening = false
      }
      "event" -> if (isListening) onEvent(message.getJSONObject("event"))
    }
  }

  private fun onLevel(rms: Float) {
    indicator?.setLevel(rms)
    if (rms >= SPEECH_RMS) {
      lastSpeechAt = SystemClock.uptimeMillis()
      heardSomething = true
    }
  }

  private fun onEvent(event: JSONObject) {
    when (event.optString("type")) {
      "verse_candidate" -> {
        val best = event.optJSONArray("candidates")?.optJSONObject(0) ?: return
        candidateText = activity?.getString(
          R.string.tilawa_finder_candidate, "${best.optInt("surah")}:${best.optInt("ayah")}"
        )
      }
      // Aʿūḏu billāh / Basmala: keine Suche, auf die eigentliche Rezitation warten
      "intro" -> candidateText = activity?.getString(R.string.tilawa_finder_intro)
      "verse_match" -> {
        val ayah = SuraAyah(event.getInt("surah"), event.getInt("ayah"))
        onMatch(ayah)
      }
    }
  }

  /**
   * Kurz „gefunden“ zeigen, dann schließen und springen. Der Leser startet danach direkt den
   * Rezitationsmodus ab dieser Aya und verfolgt weiter Wort für Wort (siehe RecitationPresenterImpl.bind).
   */
  private fun onMatch(ayah: SuraAyah) {
    val activity = activity ?: return
    val callback = onFound
    modePresenter.requestAutoStart(ayah)
    engineHolder.current()?.stopRecording(flush = false)
    isListening = false
    handler.removeCallbacks(ticker)
    indicator?.phase = Phase.FOUND
    progressView?.visibility = View.INVISIBLE
    detailView?.visibility = View.GONE
    statusView?.text = activity.getString(R.string.tilawa_finder_found, ayah.sura, ayah.ayah)
    handler.postDelayed({
      cancel()
      callback?.invoke(ayah)
    }, FOUND_DELAY_MS)
  }

  private fun themeAccent(activity: Activity): Int {
    val value = TypedValue()
    return if (activity.theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, value, true)) {
      value.data
    } else {
      0xFF1E88E5.toInt()
    }
  }

  companion object {
    /** Etwas über Tilawas Stille-Schwelle (0.005), damit Rauschen nicht als Sprache zählt. */
    private const val SPEECH_RMS = 0.012f
    private const val HEARING_HOLD_MS = 700L
    private const val PROCESSING_HOLD_MS = 4_000L
    private const val TICK_MS = 150L
    private const val FOUND_DELAY_MS = 700L
  }
}
