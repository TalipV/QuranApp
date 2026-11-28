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
 * QuranApp: Karten „Weiterlesen“ auf der Startseite – eine je laufender Rezitation.
 * Antippen springt zur gemerkten Aya (wird im Leser hervorgehoben), „⋮“ bietet Abschließen/Verwerfen.
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
    val active = store.active()
    container.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
    active.forEach { container.addView(card(it)) }
  }

  private fun card(progress: ReadingProgressStore.Progress): View {
    val density = activity.resources.displayMetrics.density
    val pad = (12 * density).toInt()
    val name = suraName(progress.sura)

    val title = TextView(activity).apply {
      text = activity.getString(R.string.quranapp_progress_continue, name)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    val details = buildString {
      append(activity.getString(R.string.quranapp_progress_details, progress.ayah, progress.page))
      append(" · ")
      append(
        DateUtils.getRelativeTimeSpanString(
          progress.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
      )
      if (progress.completedCount > 0) {
        append(" · ")
        append(activity.getString(R.string.quranapp_progress_completed_count, progress.completedCount))
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
      contentDescription = activity.getString(R.string.quranapp_progress_more)
      background = selectableBackground()
      setOnClickListener { showMenu(it, progress, name) }
    }

    return LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(pad, pad / 2, pad / 2, pad / 2)
      background = selectableBackground()
      isClickable = true
      setOnClickListener { open(SuraAyah(progress.sura, progress.ayah)) }
      addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      addView(more, LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()))
    }
  }

  private fun showMenu(anchor: View, progress: ReadingProgressStore.Progress, name: String) {
    PopupMenu(activity, anchor).apply {
      menu.add(0, MENU_COMPLETE, 0, R.string.quranapp_progress_complete)
      menu.add(0, MENU_DISCARD, 1, R.string.quranapp_progress_discard)
      setOnMenuItemClickListener { item ->
        when (item.itemId) {
          MENU_COMPLETE -> store.complete(progress.sura)?.let {
            Toast.makeText(
              activity,
              activity.getString(R.string.quranapp_progress_completed, name, it.completedCount),
              Toast.LENGTH_LONG
            ).show()
          }
          MENU_DISCARD -> store.discard(progress.sura)
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
    const val MENU_DISCARD = 2
  }
}
