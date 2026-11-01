package com.quran.mobile.recitation.presenter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.quran.data.core.QuranInfo
import com.quran.data.di.QuranScope
import com.quran.data.model.SuraAyah
import com.quran.mobile.recitation.R
import com.quran.mobile.recitation.tilawa.TilawaEngine
import com.quran.mobile.recitation.tilawa.TilawaRecitationState
import com.quran.recitation.common.Recitation
import com.quran.recitation.common.RecitationSession
import com.quran.recitation.common.RecitedAyah
import com.quran.recitation.common.RecitedSection
import com.quran.recitation.events.RecitationEventPresenter
import com.quran.recitation.presenter.RecitationPresenter
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import timber.log.Timber

/**
 * Live-Rezitation mit Tilawa: Mikrofon in der Audioleiste bzw. „Ab hier rezitieren“
 * startet die Erkennung. Aya und Wort werden markiert, mögliche Fehler rot eingefärbt,
 * und die Seite blättert automatisch mit.
 *
 * Langes Drücken auf das Mikrofon wechselt zwischen „Mitlesen mit Fehlererkennung“
 * und „nur Mitlesen“.
 */
@QuranScope
class RecitationPresenterImpl @Inject constructor(
  private val quranInfo: QuranInfo,
  private val recitationEventPresenter: RecitationEventPresenter,
  private val state: TilawaRecitationState,
) : RecitationPresenter, TilawaEngine.Listener {

  private val enabledFlow = MutableStateFlow(true)

  private var activity: Activity? = null
  private var engine: TilawaEngine? = null
  private var isEngineReady = false
  private var pendingStart: SuraAyah? = null
  private var session: TilawaSession? = null
  private var correctionMode = true

  override fun bind(what: Activity) {
    activity = what
  }

  override fun unbind(what: Activity) {
    if (activity !== what) return
    endSession()
    engine?.destroy()
    engine = null
    isEngineReady = false
    activity = null
  }

  override fun isRecitationEnabled(): Boolean = true

  override fun isRecitationEnabledFlow(): StateFlow<Boolean> = enabledFlow

  override fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
    if (requestCode != REQUEST_RECORD_AUDIO) return
    val start = pendingStart
    pendingStart = null
    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && start != null) {
      start(start)
    } else {
      toast(R.string.tilawa_permission_denied)
    }
  }

  override fun startOrStopRecitation(startAyah: () -> SuraAyah, showModeSelection: Boolean) {
    if (showModeSelection) {
      correctionMode = !correctionMode
      toast(if (correctionMode) R.string.tilawa_mode_correction else R.string.tilawa_mode_tracking)
      if (!recitationEventPresenter.isListening()) return
      pauseListening()
    }

    if (recitationEventPresenter.isListening()) {
      pauseListening()
      return
    }

    val activity = activity ?: return
    val ayah = session?.currentAyah() ?: startAyah()
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      pendingStart = ayah
      ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
      return
    }
    start(ayah)
  }

  override fun endSession() {
    pendingStart = null
    engine?.stopRecording(flush = false)
    keepScreenOn(false)
    session = null
    state.clear()
    recitationEventPresenter.onListeningStateChange(false)
    recitationEventPresenter.onRecitationSessionChange(null)
  }

  private fun start(ayah: SuraAyah) {
    if (session == null) {
      session = TilawaSession(ayah)
      state.startSession(ayah)
      recitationEventPresenter.onRecitationSessionChange(session)
    }
    recitationEventPresenter.onListeningStateChange(true)

    val engine = engine ?: createEngine() ?: return
    if (isEngineReady) {
      beginRecording(engine, ayah)
    } else {
      pendingStart = ayah
      toast(R.string.tilawa_loading)
    }
  }

  private fun createEngine(): TilawaEngine? {
    val activity = activity ?: return null
    return TilawaEngine(activity, this).also { engine ->
      // Unsichtbar ans Fenster hängen, damit die WebView nicht gedrosselt wird.
      (activity.window.decorView as? ViewGroup)?.addView(engine.webView, ViewGroup.LayoutParams(1, 1))
      engine.webView.alpha = 0f
      this.engine = engine
    }
  }

  @Suppress("MissingPermission") // wird in startOrStopRecitation geprüft
  private fun beginRecording(engine: TilawaEngine, ayah: SuraAyah) {
    val config = JSONObject()
      .put("mode", if (correctionMode) "correction" else "tracking")
      .put("autoContinue", true)
      .put(
        "expected", JSONObject()
          .put("surah", ayah.sura)
          .put("ayah", ayah.ayah)
          .put("ayahEnd", quranInfo.getNumberOfAyahs(ayah.sura))
      )
    engine.startRecording(config.toString())
    if (engine.recording) {
      keepScreenOn(true)
    } else {
      recitationEventPresenter.onListeningStateChange(false)
    }
  }

  private fun pauseListening() {
    pendingStart = null
    engine?.stopRecording()
    keepScreenOn(false)
    recitationEventPresenter.onListeningStateChange(false)
  }

  // TilawaEngine.Listener

  override fun onTilawaMessage(message: JSONObject) {
    when (message.optString("type")) {
      "ready" -> {
        isEngineReady = true
        val start = pendingStart
        pendingStart = null
        val engine = engine
        if (start != null && engine != null && recitationEventPresenter.isListening()) {
          beginRecording(engine, start)
        }
      }
      "event" -> onTilawaEvent(message.getJSONObject("event"))
      "error" -> {
        val error = message.optString("message")
        Timber.e("Tilawa: %s", error)
        activity?.let {
          Toast.makeText(it, it.getString(R.string.tilawa_error, error.lineSequence().first()), Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  private fun onTilawaEvent(event: JSONObject) {
    if (session == null) return
    when (event.optString("type")) {
      "word_progress" -> {
        val ayah = SuraAyah(event.getInt("surah"), event.getInt("ayah"))
        onPosition(ayah, event.getInt("word_index") + 1)
      }
      "verse_match" -> {
        val ayah = SuraAyah(event.getInt("surah"), event.getInt("ayah"))
        if (state.flow.value.ayah != ayah) onPosition(ayah, -1)
      }
      "correction" -> {
        val correctionState = event.optJSONObject("state") ?: return
        if (correctionState.optString("phase") == "error") {
          correctionState.optJSONObject("issue")?.let { onIssue(it) }
        }
      }
      "correction_note" -> event.optJSONObject("issue")?.let { onIssue(it) }
    }
  }

  private fun onPosition(ayah: SuraAyah, word: Int) {
    val changedAyah = state.flow.value.ayah != ayah
    state.setPosition(ayah, word)
    if (changedAyah) {
      session?.onAyah(ayah)
      // blättert automatisch zur Seite der aktuellen Aya
      recitationEventPresenter.onRecitationChange(ayah)
    }
  }

  private fun onIssue(issue: JSONObject) {
    val ayah = SuraAyah(issue.getInt("surah"), issue.getInt("ayah"))
    val kind = issue.optString("kind")
    val mistakes = if (kind in AYAH_LEVEL_KINDS) {
      listOf(TilawaRecitationState.Mistake(ayah, -1, kind))
    } else {
      val first = issue.getInt("word") + 1
      val count = issue.optInt("words", 1).coerceAtLeast(1)
      (first until first + count).map { TilawaRecitationState.Mistake(ayah, it, kind) }
    }
    state.addMistakes(mistakes)

    val activity = activity ?: return
    activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    val label = activity.getString(kindLabel(kind))
    val wordText = issue.optString("word_text")
    val text = if (kind in AYAH_LEVEL_KINDS || wordText.isEmpty()) {
      val withText = if (wordText.isEmpty()) label else "$label („$wordText“)"
      activity.getString(R.string.tilawa_mistake, ayah.sura, ayah.ayah, withText)
    } else {
      activity.getString(R.string.tilawa_mistake_word, ayah.sura, ayah.ayah, mistakes.first().word, wordText, label)
    }
    Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
  }

  private fun kindLabel(kind: String): Int = when (kind) {
    "possible_omission" -> R.string.tilawa_kind_omission
    "possible_substitution" -> R.string.tilawa_kind_substitution
    "possible_vowel" -> R.string.tilawa_kind_vowel
    "possible_repetition" -> R.string.tilawa_kind_repetition
    "possible_skipped_ayah" -> R.string.tilawa_kind_skipped_ayah
    else -> R.string.tilawa_kind_unclear_ayah
  }

  private fun keepScreenOn(on: Boolean) {
    val window = activity?.window ?: return
    if (on) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  private fun toast(resId: Int) {
    activity?.let { Toast.makeText(it, resId, Toast.LENGTH_SHORT).show() }
  }

  private class TilawaSession(private val start: SuraAyah) : RecitationSession {
    private val startTimeMillis = System.currentTimeMillis()
    private var current = start
    private val recited = linkedSetOf<SuraAyah>()

    fun onAyah(ayah: SuraAyah) {
      current = ayah
      recited.add(ayah)
    }

    override fun id(): String = startTimeMillis.toString()
    override fun startAyah(): SuraAyah = start
    override fun currentAyah(): SuraAyah = current
    override fun startedAt(): Long = startTimeMillis
    override fun recitation(): Recitation = object : Recitation {
      override fun recitedAyahs(): List<RecitedAyah> = recited.map { ayah ->
        object : RecitedAyah {
          override fun ayah(): SuraAyah = ayah
          override fun recitedSections(): List<RecitedSection> = emptyList()
        }
      }
    }
  }

  private companion object {
    const val REQUEST_RECORD_AUDIO = 4_711
    val AYAH_LEVEL_KINDS = setOf("possible_skipped_ayah", "unclear_ayah")
  }
}
