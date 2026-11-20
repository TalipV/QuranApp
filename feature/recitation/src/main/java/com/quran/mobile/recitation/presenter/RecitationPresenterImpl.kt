package com.quran.mobile.recitation.presenter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.quran.data.core.QuranInfo
import com.quran.data.di.QuranScope
import com.quran.data.model.SuraAyah
import com.quran.mobile.recitation.R
import com.quran.mobile.recitation.tilawa.TilawaEngine
import com.quran.mobile.recitation.tilawa.TilawaEngineHolder
import com.quran.mobile.recitation.tilawa.TilawaRecitationState
import com.quran.recitation.common.Recitation
import com.quran.recitation.common.RecitationSession
import com.quran.recitation.common.RecitedAyah
import com.quran.recitation.common.RecitedSection
import com.quran.recitation.events.RecitationEventPresenter
import com.quran.recitation.events.RecitationModePresenter
import com.quran.recitation.presenter.RecitationPresenter
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject
import timber.log.Timber

/**
 * Live-Rezitation mit Tilawa im eigenen Rezitationsmodus des Lesers.
 *
 * Der Modus wird über die Audioleiste (unten rechts) ein- und ausgeschaltet. Beim Einschalten
 * wird das Modell vorgeladen; das Mikrofon startet/pausiert, die Leiste zeigt den Zustand.
 * Aya und Wort werden markiert, mögliche Fehler rot eingefärbt, die Seite blättert mit.
 * Langes Drücken auf das Mikrofon schaltet die Fehlererkennung an/aus.
 */
@QuranScope
class RecitationPresenterImpl @Inject constructor(
  private val quranInfo: QuranInfo,
  private val recitationEventPresenter: RecitationEventPresenter,
  private val modePresenter: RecitationModePresenter,
  private val engineHolder: TilawaEngineHolder,
  private val state: TilawaRecitationState,
) : RecitationPresenter, TilawaEngine.Listener {

  private var activity: Activity? = null
  private var scope: CoroutineScope? = null
  private var pendingStart: SuraAyah? = null
  /** Fortsetzen einer laufenden Sitzung: Stelle frisch erkennen statt erzwingen. */
  private var pendingRelocate = false
  private var session: TilawaSession? = null
  private var correctionMode = true

  /** Hörbare Sprache (ms) seit dem letzten erkannten Fortschritt – für die „kommt nicht mit“-Warnung. */
  private var speechSinceAdvanceMs = 0L
  /** Aya, zu deren Folgeseite schon geblättert wurde (Ende der letzten Aya einer Seite). */
  private var turnedPageAfter: SuraAyah? = null

  override fun bind(what: Activity) {
    activity = what
    engineHolder.addListener(this)
    if (modePresenter.isModeActive()) engineHolder.obtain(what)

    scope?.cancel()
    scope = MainScope().also { scope ->
      modePresenter.modeFlow
        .drop(1)
        .onEach { active -> if (active) onModeEntered() else endSession() }
        .launchIn(scope)
    }
  }

  override fun unbind(what: Activity) {
    if (activity !== what) return
    endSession()
    scope?.cancel()
    scope = null
    engineHolder.removeListener(this)
    engineHolder.detach(what)
    activity = null
  }

  /** Die Schnittstellen bleiben immer gebunden; sichtbar wird alles nur im Rezitationsmodus. */
  override fun isRecitationEnabled(): Boolean = true

  override fun isRecitationEnabledFlow(): StateFlow<Boolean> = modePresenter.modeFlow

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
    if (!modePresenter.isModeActive()) modePresenter.setMode(true)

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
    resetLag()
    turnedPageAfter = null
    if (recitationEventPresenter.isListening()) engineHolder.current()?.stopRecording(flush = false)
    keepScreenOn(false)
    session = null
    state.clear()
    recitationEventPresenter.onListeningStateChange(false)
    recitationEventPresenter.onRecitationSessionChange(null)
  }

  private fun onModeEntered() {
    activity?.let { engineHolder.obtain(it) }
  }

  private fun start(ayah: SuraAyah) {
    val activity = activity ?: return
    // Nach einer Pause kann man an anderer Stelle weitermachen (z. B. schon in der nächsten
    // Aya): die letzte Aya ist dann nur ein Hinweis, nicht die erzwungene Startposition.
    val relocate = session != null
    if (session == null) {
      session = TilawaSession(ayah)
      state.startSession(ayah)
      recitationEventPresenter.onRecitationSessionChange(session)
    }
    recitationEventPresenter.onListeningStateChange(true)

    val engine = engineHolder.obtain(activity)
    if (engineHolder.isReady) {
      beginRecording(engine, ayah, relocate)
    } else {
      // startet automatisch, sobald die Erkennung bereit ist (Fortschritt in der Leiste)
      pendingStart = ayah
      pendingRelocate = relocate
    }
  }

  @Suppress("MissingPermission") // wird in startOrStopRecitation geprüft
  private fun beginRecording(engine: TilawaEngine, ayah: SuraAyah, relocate: Boolean) {
    modePresenter.onBacklog(0f)
    resetLag()
    val config = JSONObject()
      .put("mode", if (correctionMode) "correction" else "tracking")
      .put("autoContinue", true)
      .put("strictOmissions", correctionMode)
      .put("relocate", relocate)
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
    resetLag()
    engineHolder.current()?.stopRecording()
    keepScreenOn(false)
    recitationEventPresenter.onListeningStateChange(false)
  }

  // TilawaEngine.Listener (über TilawaEngineHolder)

  override fun onTilawaMessage(message: JSONObject) {
    when (message.optString("type")) {
      "ready" -> {
        val start = pendingStart
        pendingStart = null
        val engine = engineHolder.current()
        if (start != null && engine != null && recitationEventPresenter.isListening()) {
          beginRecording(engine, start, pendingRelocate)
        }
      }
      "event" -> onTilawaEvent(message.getJSONObject("event"))
      "level" -> onLevel(message.optDouble("rms", 0.0).toFloat())
      "error" -> {
        val error = message.optString("message")
        Timber.e("Tilawa: %s", error)
        if (engineHolder.isReady && session != null) {
          activity?.let {
            Toast.makeText(it, it.getString(R.string.tilawa_error, error.lineSequence().first()), Toast.LENGTH_LONG).show()
          }
        }
      }
    }
  }

  private fun onTilawaEvent(event: JSONObject) {
    if (session == null) return
    when (event.optString("type")) {
      "word_progress" -> {
        val ayah = SuraAyah(event.getInt("surah"), event.getInt("ayah"))
        val word = event.getInt("word_index") + 1
        onPosition(ayah, word)
        val total = event.optInt("total_words", 0)
        if (total > 0 && word >= total) onAyahEndReached(ayah)
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
    val before = state.flow.value
    val changedAyah = before.ayah != ayah
    if (changedAyah || (word > 0 && before.word != word)) resetLag()
    state.setPosition(ayah, word)
    if (changedAyah) {
      session?.onAyah(ayah)
      // blättert automatisch zur Seite der aktuellen Aya
      recitationEventPresenter.onRecitationChange(ayah)
    }
  }

  /**
   * Letztes Wort einer Aya erreicht: liegt die nächste Aya auf einer anderen Seite,
   * schon jetzt umblättern (nicht erst, wenn die nächste Aya erkannt ist).
   */
  private fun onAyahEndReached(ayah: SuraAyah) {
    if (turnedPageAfter == ayah) return
    val next = when {
      ayah.ayah < quranInfo.getNumberOfAyahs(ayah.sura) -> SuraAyah(ayah.sura, ayah.ayah + 1)
      ayah.sura < 114 -> SuraAyah(ayah.sura + 1, 1)
      else -> return
    }
    if (quranInfo.getPageFromSuraAyah(next.sura, next.ayah) !=
      quranInfo.getPageFromSuraAyah(ayah.sura, ayah.ayah)
    ) {
      turnedPageAfter = ayah
      recitationEventPresenter.onRecitationChange(next)
    }
  }

  /** Mikrofonpegel alle 100 ms: zählt Sprache, solange die Erkennung nicht weiterkommt. */
  private fun onLevel(rms: Float) {
    if (!recitationEventPresenter.isListening() || session == null) return
    if (rms < SPEECH_RMS) return
    speechSinceAdvanceMs += LEVEL_INTERVAL_MS
    modePresenter.onTrackingLag(speechSinceAdvanceMs / 1000f)
  }

  private fun resetLag() {
    speechSinceAdvanceMs = 0L
    modePresenter.onTrackingLag(0f)
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
    val known = state.flow.value.mistakes.map { it.ayah to it.word }.toSet()
    if (mistakes.all { (it.ayah to it.word) in known }) return
    state.addMistakes(mistakes)

    val activity = activity ?: return
    activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    val label = activity.getString(kindLabel(kind))
    val wordText = issue.optString("word_text")
    val text = if (kind in AYAH_LEVEL_KINDS || wordText.isEmpty()) {
      val withText = if (wordText.isEmpty()) label else "$label („$wordText“)"
      activity.getString(R.string.tilawa_mistake, ayah.sura, ayah.ayah, withText)
    } else if (mistakes.size > 1) {
      activity.getString(
        R.string.tilawa_mistake_words, ayah.sura, ayah.ayah,
        mistakes.first().word, mistakes.last().word, wordText, label
      )
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
    /** Etwas über Tilawas Stille-Schwelle, damit Rauschen nicht als Sprache zählt. */
    const val SPEECH_RMS = 0.012f
    const val LEVEL_INTERVAL_MS = 100L
    val AYAH_LEVEL_KINDS = setOf("possible_skipped_ayah", "unclear_ayah")
  }
}
