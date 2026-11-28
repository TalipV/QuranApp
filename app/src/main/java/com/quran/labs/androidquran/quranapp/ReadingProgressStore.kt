package com.quran.labs.androidquran.quranapp

import android.content.Context
import org.json.JSONObject

/**
 * QuranApp: „Laufende Rezitationen“ – gemerkte Stelle je Sure (z. B. Al-Kahf jeden Freitag).
 *
 * Pro Sure gibt es höchstens eine laufende Rezitation. Wer eine Stelle markiert, beginnt damit
 * automatisch eine neue Runde, falls keine läuft. Das Abschließen zählt mit, wie oft die Sure
 * schon vollständig gelesen wurde. Gespeichert wird lokal in SharedPreferences.
 */
class ReadingProgressStore(context: Context) {

  data class Progress(
    val sura: Int,
    /** gemerkte Aya; 0 = gerade keine laufende Rezitation (nur Zähler gespeichert) */
    val ayah: Int,
    val page: Int,
    val startedAt: Long,
    val updatedAt: Long,
    /** wie oft die Sure mit dieser Funktion schon abgeschlossen wurde */
    val completedCount: Int,
  ) {
    val isActive: Boolean get() = ayah > 0
  }

  enum class MarkResult { STARTED, UPDATED, COMPLETED }

  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun get(sura: Int): Progress? = prefs.getString(key(sura), null)?.let { parse(sura, it) }

  /** Alle laufenden Rezitationen, zuletzt bearbeitete zuerst. */
  fun active(): List<Progress> = prefs.all.keys
    .filter { it.startsWith(KEY_PREFIX) }
    .mapNotNull { it.removePrefix(KEY_PREFIX).toIntOrNull() }
    .mapNotNull { get(it) }
    .filter { it.isActive }
    .sortedByDescending { it.updatedAt }

  /**
   * Merkt [ayah] als aktuelle Stelle. Ist [isLastAyah] gesetzt (letzte Aya der Sure),
   * gilt die Rezitation als abgeschlossen.
   */
  fun mark(sura: Int, ayah: Int, page: Int, isLastAyah: Boolean, now: Long = System.currentTimeMillis()): Pair<MarkResult, Progress> {
    val old = get(sura)
    val running = old?.isActive == true
    if (isLastAyah) {
      val done = Progress(sura, 0, page, old?.startedAt ?: now, now, (old?.completedCount ?: 0) + 1)
      save(done)
      return MarkResult.COMPLETED to done
    }
    val progress = Progress(
      sura = sura,
      ayah = ayah,
      page = page,
      startedAt = if (running) old!!.startedAt else now,
      updatedAt = now,
      completedCount = old?.completedCount ?: 0,
    )
    save(progress)
    return (if (running) MarkResult.UPDATED else MarkResult.STARTED) to progress
  }

  /** Laufende Rezitation als gelesen abschließen (zählt mit). */
  fun complete(sura: Int, now: Long = System.currentTimeMillis()): Progress? {
    val old = get(sura)?.takeIf { it.isActive } ?: return null
    val done = old.copy(ayah = 0, updatedAt = now, completedCount = old.completedCount + 1)
    save(done)
    return done
  }

  /** Laufende Rezitation verwerfen, ohne sie zu zählen. */
  fun discard(sura: Int) {
    val old = get(sura) ?: return
    if (old.completedCount > 0) save(old.copy(ayah = 0)) else prefs.edit().remove(key(sura)).apply()
  }

  private fun save(p: Progress) {
    val json = JSONObject()
      .put("ayah", p.ayah)
      .put("page", p.page)
      .put("startedAt", p.startedAt)
      .put("updatedAt", p.updatedAt)
      .put("completedCount", p.completedCount)
    prefs.edit().putString(key(p.sura), json.toString()).apply()
  }

  private fun parse(sura: Int, raw: String): Progress? = try {
    val json = JSONObject(raw)
    Progress(
      sura = sura,
      ayah = json.optInt("ayah", 0),
      page = json.optInt("page", 0),
      startedAt = json.optLong("startedAt", 0L),
      updatedAt = json.optLong("updatedAt", 0L),
      completedCount = json.optInt("completedCount", 0),
    )
  } catch (e: Exception) {
    null
  }

  private fun key(sura: Int) = "$KEY_PREFIX$sura"

  private companion object {
    const val PREFS = "quranapp_reading_progress"
    const val KEY_PREFIX = "sura_"
  }
}
