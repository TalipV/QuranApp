package com.quran.labs.androidquran.quranapp

import android.app.Activity
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.quran.labs.androidquran.R

/** QuranApp: Dialoge für die Fortschritts-Tracker. */
object ReadingProgressDialogs {

  /**
   * „Fortschritt merken in …“: bestehenden Tracker wählen oder mit „＋ Neuer Tracker“ einen
   * anlegen. Danach wird [onPicked] mit dem gewählten Tracker aufgerufen.
   */
  fun pickTracker(
    activity: Activity,
    store: ReadingProgressStore,
    suraName: (Int) -> String,
    onPicked: (ReadingProgressStore.Tracker) -> Unit,
  ) {
    val trackers = store.trackers()
    val labels = trackers.map { tracker -> label(activity, tracker, suraName) }.toTypedArray<CharSequence>()
    AlertDialog.Builder(activity)
      .setTitle(R.string.quranapp_tracker_pick_title)
      .setItems(labels) { _, which -> onPicked(trackers[which]) }
      .setNeutralButton(R.string.quranapp_tracker_new) { _, _ ->
        askName(activity, R.string.quranapp_tracker_new_title, "") { name -> onPicked(store.create(name)) }
      }
      .setNegativeButton(R.string.quranapp_tracker_cancel, null)
      .show()
  }

  /** Namenseingabe für einen neuen oder umbenannten Tracker. */
  fun askName(activity: Activity, titleRes: Int, initial: String, onName: (String) -> Unit) {
    val density = activity.resources.displayMetrics.density
    val input = EditText(activity).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      setSingleLine(true)
      setText(initial)
      setSelection(initial.length)
      hint = activity.getString(R.string.quranapp_tracker_name_hint)
    }
    val frame = FrameLayout(activity).apply {
      val pad = (20 * density).toInt()
      setPadding(pad, pad / 2, pad, 0)
      addView(input)
    }
    AlertDialog.Builder(activity)
      .setTitle(titleRes)
      .setView(frame)
      .setPositiveButton(R.string.quranapp_tracker_ok) { _, _ ->
        val name = input.text.toString().trim()
        if (name.isNotEmpty()) onName(name)
      }
      .setNegativeButton(R.string.quranapp_tracker_cancel, null)
      .show()
  }

  /** Zweizeilig: Name, darunter klein die gemerkte Stelle (falls vorhanden). */
  private fun label(
    activity: Activity,
    tracker: ReadingProgressStore.Tracker,
    suraName: (Int) -> String,
  ): CharSequence {
    val text = SpannableStringBuilder(tracker.name)
    if (tracker.hasPosition) {
      val start = text.length
      text.append("\n")
      text.append(activity.getString(R.string.quranapp_tracker_position, suraName(tracker.sura), tracker.ayah))
      text.setSpan(RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return text
  }
}
