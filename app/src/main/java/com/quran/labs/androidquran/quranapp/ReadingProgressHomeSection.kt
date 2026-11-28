package com.quran.labs.androidquran.quranapp

import android.app.Activity
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import com.quran.data.model.SuraAyah
import com.quran.labs.androidquran.R

/**
 * QuranApp: Karten „Weiterlesen“ auf der Startseite – eine je Tracker mit gemerkter Stelle.
 * Antippen springt zur gemerkten Aya (wird im Leser hervorgehoben), „⋮“ bietet
 * Runde abschließen / Zurücksetzen / Umbenennen / Löschen.
 */
class ReadingProgressHomeSection(
  private val activity: Activity,
  private val container: LinearLayout,
  private val store: ReadingProgressStore,
  private val suraName: (Int) -> String,
  private val open: (SuraAyah) -> Unit,
) {

  fun render() {
    container.removeAllViews()
    val trackers = store.withPosition()
    container.visibility = if (trackers.isEmpty()) View.GONE else View.VISIBLE
    trackers.forEach { container.addView(card(it)) }
  }

  private fun card(tracker: ReadingProgressStore.Tracker): View {
    val density = activity.resources.displayMetrics.density
    val pad = (12 * density).toInt()

    val title = TextView(activity).apply {
      text = tracker.name
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    val details = buildString {
      append(activity.getString(R.string.quranapp_tracker_position, suraName(tracker.sura), tracker.ayah))
      append(" · ")
      append(activity.getString(R.string.quranapp_tracker_page, tracker.page))
      append(" · ")
      append(
        DateUtils.getRelativeTimeSpanString(
          tracker.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
      )
      if (tracker.completedCount > 0) {
        append(" · ")
        append(activity.getString(R.string.quranapp_tracker_completed_count, tracker.completedCount))
      }
    }
    val subtitle = TextView(activity).apply {
      text = details
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      alpha = 0.75f
    }
    val texts = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      addView(title)
      addView(subtitle)
    }
    val more = ImageButton(activity).apply {
      setImageResource(R.drawable.ic_quranapp_more)
      contentDescription = activity.getString(R.string.quranapp_tracker_more)
      background = selectableBackground()
      setOnClickListener { showMenu(it, tracker) }
    }

    return LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(pad, pad / 2, pad / 2, pad / 2)
      background = selectableBackground()
      isClickable = true
      setOnClickListener { open(SuraAyah(tracker.sura, tracker.ayah)) }
      addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      addView(more, LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()))
    }
  }

  private fun showMenu(anchor: View, tracker: ReadingProgressStore.Tracker) {
    PopupMenu(activity, anchor).apply {
      menu.add(0, MENU_COMPLETE, 0, R.string.quranapp_tracker_complete)
      menu.add(0, MENU_RESET, 1, R.string.quranapp_tracker_reset)
      menu.add(0, MENU_RENAME, 2, R.string.quranapp_tracker_rename)
      if (!tracker.isDefault) menu.add(0, MENU_DELETE, 3, R.string.quranapp_tracker_delete)
      setOnMenuItemClickListener { item ->
        when (item.itemId) {
          MENU_COMPLETE -> store.complete(tracker.id)?.let {
            Toast.makeText(
              activity,
              activity.getString(R.string.quranapp_tracker_completed, it.name, it.completedCount),
              Toast.LENGTH_LONG
            ).show()
          }
          MENU_RESET -> store.reset(tracker.id)
          MENU_RENAME -> ReadingProgressDialogs.askName(
            activity, R.string.quranapp_tracker_rename, tracker.name
          ) { name ->
            store.rename(tracker.id, name)
            render()
          }
          MENU_DELETE -> store.delete(tracker.id)
        }
        render()
        true
      }
      show()
    }
  }

  private fun selectableBackground() = TypedValue().let { value ->
    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
    activity.getDrawable(value.resourceId)
  }

  private companion object {
    const val MENU_COMPLETE = 1
    const val MENU_RESET = 2
    const val MENU_RENAME = 3
    const val MENU_DELETE = 4
  }
}
