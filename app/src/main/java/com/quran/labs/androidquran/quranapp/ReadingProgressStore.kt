package com.quran.labs.androidquran.quranapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * QuranApp: Fortschritts-Tracker – benannte Lesezeichen für Rezitationsrunden
 * (z. B. „Suratul Kahf“, „Runde mit Freunden“, „Private Khatma“).
 *
 * Jeder Tracker merkt sich eine Stelle (Sure, Aya, Seite), egal in welcher Sure. „Standard“ gibt es
 * immer; weitere legt der Nutzer im Dialog beim Markieren an. „Runde abschließen“ zählt mit und
 * leert die Stelle. Gespeichert wird lokal in SharedPreferences (JSON).
 */
class ReadingProgressStore(
  context: Context,
  /** Name einer Sure, nur für die Übernahme der Daten aus Version 0.2.0 */
  private val suraName: (Int) -> String,
) {

  data class Tracker(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    /** gemerkte Stelle; ayah 0 = keine Stelle gemerkt */
    val sura: Int,
    val ayah: Int,
    val page: Int,
    val startedAt: Long,
    val updatedAt: Long,
    /** wie oft eine Runde mit diesem Tracker abgeschlossen wurde */
    val completedCount: Int,
  ) {
    val hasPosition: Boolean get() = ayah > 0
  }

  enum class MarkResult { STARTED, UPDATED }

  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Alle Tracker: „Standard“ zuerst, dann zuletzt benutzte. */
  fun trackers(): List<Tracker> {
    val all = load()
    return all.filter { it.isDefault } + all.filterNot { it.isDefault }.sortedByDescending { it.updatedAt }
  }

  /** Tracker mit gemerkter Stelle, zuletzt benutzte zuerst (für die Startseite). */
  fun withPosition(): List<Tracker> = load().filter { it.hasPosition }.sortedByDescending { it.updatedAt }

  fun create(name: String, now: Long = System.currentTimeMillis()): Tracker {
    val tracker = Tracker(
      id = "t$now", name = name.trim(), isDefault = false,
      sura = 0, ayah = 0, page = 0, startedAt = now, updatedAt = now, completedCount = 0,
    )
    save(load() + tracker)
    return tracker
  }

  /** Merkt die Stelle im Tracker [id]. STARTED, wenn der Tracker vorher keine Stelle hatte. */
  fun mark(id: String, sura: Int, ayah: Int, page: Int, now: Long = System.currentTimeMillis()): Pair<MarkResult, Tracker>? {
    val old = load().firstOrNull { it.id == id } ?: return null
    val updated = old.copy(
      sura = sura, ayah = ayah, page = page, updatedAt = now,
      startedAt = if (old.hasPosition) old.startedAt else now,
    )
    replace(updated)
    return (if (old.hasPosition) MarkResult.UPDATED else MarkResult.STARTED) to updated
  }

  /** Runde abschließen: zählt mit und leert die Stelle. */
  fun complete(id: String, now: Long = System.currentTimeMillis()): Tracker? {
    val old = load().firstOrNull { it.id == id } ?: return null
    val done = old.copy(sura = 0, ayah = 0, page = 0, updatedAt = now, completedCount = old.completedCount + 1)
    replace(done)
    return done
  }

  /** Stelle leeren, ohne zu zählen. */
  fun reset(id: String) {
    val old = load().firstOrNull { it.id == id } ?: return
    replace(old.copy(sura = 0, ayah = 0, page = 0))
  }

  fun rename(id: String, name: String) {
    val old = load().firstOrNull { it.id == id } ?: return
    if (name.isNotBlank()) replace(old.copy(name = name.trim()))
  }

  /** Löscht den Tracker („Standard“ kann nicht gelöscht werden). */
  fun delete(id: String) {
    save(load().filterNot { it.id == id && !it.isDefault })
  }

  private fun replace(tracker: Tracker) {
    save(load().map { if (it.id == tracker.id) tracker else it })
  }

  private fun load(): List<Tracker> {
    val raw = prefs.getString(KEY_TRACKERS, null)
    val list = if (raw == null) migrate() else parse(raw)
    if (list.none { it.isDefault }) {
      val withDefault = listOf(defaultTracker()) + list
      save(withDefault)
      return withDefault
    }
    return list
  }

  private fun defaultTracker() = Tracker(
    id = DEFAULT_ID, name = DEFAULT_NAME, isDefault = true,
    sura = 0, ayah = 0, page = 0, startedAt = 0L, updatedAt = 0L, completedCount = 0,
  )

  /** Version 0.2.0 speicherte je Sure eine Rezitation („sura_N“) – als Tracker mit dem Surennamen übernehmen. */
  private fun migrate(): List<Tracker> {
    val migrated = prefs.all.keys
      .filter { it.startsWith(OLD_PREFIX) }
      .mapNotNull { key ->
        val sura = key.removePrefix(OLD_PREFIX).toIntOrNull() ?: return@mapNotNull null
        val raw = prefs.getString(key, null) ?: return@mapNotNull null
        val json = try {
          JSONObject(raw)
        } catch (e: Exception) {
          return@mapNotNull null
        }
        Tracker(
          id = "t$sura", name = suraName(sura), isDefault = false,
          sura = if (json.optInt("ayah", 0) > 0) sura else 0,
          ayah = json.optInt("ayah", 0),
          page = json.optInt("page", 0),
          startedAt = json.optLong("startedAt", 0L),
          updatedAt = json.optLong("updatedAt", 0L),
          completedCount = json.optInt("completedCount", 0),
        )
      }
    val list = listOf(defaultTracker()) + migrated
    val editor = prefs.edit()
    prefs.all.keys.filter { it.startsWith(OLD_PREFIX) }.forEach { editor.remove(it) }
    editor.putString(KEY_TRACKERS, toJson(list)).apply()
    return list
  }

  private fun save(list: List<Tracker>) {
    prefs.edit().putString(KEY_TRACKERS, toJson(list)).apply()
  }

  private fun toJson(list: List<Tracker>): String {
    val array = JSONArray()
    list.forEach { t ->
      array.put(
        JSONObject()
          .put("id", t.id)
          .put("name", t.name)
          .put("default", t.isDefault)
          .put("sura", t.sura)
          .put("ayah", t.ayah)
          .put("page", t.page)
          .put("startedAt", t.startedAt)
          .put("updatedAt", t.updatedAt)
          .put("completedCount", t.completedCount)
      )
    }
    return array.toString()
  }

  private fun parse(raw: String): List<Tracker> = try {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { i ->
      val json = array.optJSONObject(i) ?: return@mapNotNull null
      Tracker(
        id = json.optString("id"),
        name = json.optString("name"),
        isDefault = json.optBoolean("default", false),
        sura = json.optInt("sura", 0),
        ayah = json.optInt("ayah", 0),
        page = json.optInt("page", 0),
        startedAt = json.optLong("startedAt", 0L),
        updatedAt = json.optLong("updatedAt", 0L),
        completedCount = json.optInt("completedCount", 0),
      )
    }.filter { it.id.isNotEmpty() }
  } catch (e: Exception) {
    emptyList()
  }

  companion object {
    const val DEFAULT_NAME = "Standard"
    private const val DEFAULT_ID = "default"
    private const val PREFS = "quranapp_reading_progress"
    private const val KEY_TRACKERS = "trackers"
    private const val OLD_PREFIX = "sura_"
  }
}
