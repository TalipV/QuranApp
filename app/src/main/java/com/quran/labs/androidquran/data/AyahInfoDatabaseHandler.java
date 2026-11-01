package com.quran.labs.androidquran.data;

import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.quran.labs.androidquran.database.DatabaseUtils;
import com.quran.labs.androidquran.util.QuranFileUtils;
import com.quran.page.common.data.AyahBounds;
import com.quran.page.common.data.AyahCoordinates;
import com.quran.page.common.data.AyahMarkerLocation;
import com.quran.page.common.data.PageCoordinates;
import com.quran.page.common.data.SuraHeaderLocation;
import com.quran.page.common.data.coordinates.PageGlyphsCoords;

import java.io.File;

import timber.log.Timber;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AyahInfoDatabaseHandler {
  private static final RectF EMPTY_BOUNDS = new RectF();
  private static final String COL_PAGE = "page_number";
  private static final String COL_LINE = "line_number";
  private static final String COL_SURA = "sura_number";
  private static final String COL_AYAH = "ayah_number";
  private static final String COL_POSITION = "position";
  private static final String MIN_X = "min_x";
  private static final String MIN_Y = "min_y";
  private static final String MAX_X = "max_x";
  private static final String MAX_Y = "max_y";
  private static final String GLYPH_TYPE = "glyph_type";
  private static final String GLYPHS_TABLE = "glyphs";

  private static final Map<String, AyahInfoDatabaseHandler> ayahInfoCache = new HashMap<>();
  private static File quranDatabaseDirectory = null;

  private final SQLiteDatabase database;
  private final boolean hasGlyphData;

  static AyahInfoDatabaseHandler getAyahInfoDatabaseHandler(
      String databaseName, QuranFileUtils quranFileUtils) {
    final File currentAyahDatabaseDirectory = quranFileUtils.getQuranAyahDatabaseDirectory();
    if (!currentAyahDatabaseDirectory.equals(quranDatabaseDirectory)) {
      quranDatabaseDirectory = currentAyahDatabaseDirectory;
      clearAyahInfoCache();
    }

    AyahInfoDatabaseHandler handler = ayahInfoCache.get(databaseName);
    if (handler == null) {
      try {
        AyahInfoDatabaseHandler db = new AyahInfoDatabaseHandler(databaseName, quranFileUtils);
        if (db.validDatabase()) {
          ayahInfoCache.put(databaseName, db);
          handler = db;
        }
      } catch (SQLException sqlException) {
        // it's okay, we'll try again later
      }
    }
    return handler;
  }

  private static void clearAyahInfoCache() {
    final Set<String> keys = ayahInfoCache.keySet();
    for (String key : keys) {
      final AyahInfoDatabaseHandler handler = ayahInfoCache.get(key);
      try {
        if (handler != null) {
          handler.database.close();
        }
      } catch (Exception e) {
        // ignore
      }
    }
    ayahInfoCache.clear();
  }

  private AyahInfoDatabaseHandler(String databaseName,
                                  QuranFileUtils quranFileUtils) throws SQLException {
    File base = quranFileUtils.getQuranAyahDatabaseDirectory();
    File path = new File(base, databaseName);
    database = SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    hasGlyphData = quranFileUtils.getAyahInfoDbHasGlyphData();
  }

  private boolean validDatabase() {
    return database != null && database.isOpen();
  }

  @NonNull
  public PageCoordinates getPageInfo(int page, boolean wantBounds) {
    final RectF bounds = wantBounds ? getPageBounds(page) : EMPTY_BOUNDS;
    if (haveVerseMarkerData()) {
      return new PageCoordinates(page,
          bounds,
          getSuraHeadersForPage(page),
          getVerseMarkersForPage(page));
    } else {
      return new PageCoordinates(page, bounds, new ArrayList<>(), new ArrayList<>());
    }
  }

  @NonNull
  private RectF getPageBounds(int page) {
    Cursor c = null;
    try {
      String[] colNames = new String[]{
          "MIN(" + MIN_X + ")", "MIN(" + MIN_Y + ")",
          "MAX(" + MAX_X + ")", "MAX(" + MAX_Y + ")" };
      c = database.query(GLYPHS_TABLE, colNames, COL_PAGE + "=" + page, null, null, null, null);
      if (c.moveToFirst()) {
        return new RectF(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3));
      } else {
        throw new IllegalArgumentException("getPageBounds() on a non-existent page: " + page);
      }
    } finally {
      DatabaseUtils.closeCursor(c);
    }
  }

  @NonNull
  public AyahCoordinates getVersesBoundsForPage(int page) {
    boolean includeGlyphType = hasGlyphData && haveGlyphData();
    Map<String, List<AyahBounds>> ayahBounds = new HashMap<>();
    Cursor cursor = null;
    try {
      cursor = getVersesBoundsCursorForPage(page, includeGlyphType);
      while (cursor.moveToNext()) {
        int line = cursor.getInt(1);
        int sura = cursor.getInt(2);
        int ayah = cursor.getInt(3);
        int position = cursor.getInt(4);
        int minX = cursor.getInt(5);
        int minY = cursor.getInt(6);
        int maxX = cursor.getInt(7);
        int maxY = cursor.getInt(8);

        String key = sura + ":" + ayah;
        List<AyahBounds> bounds = ayahBounds.get(key);
        if (bounds == null) {
          bounds = new ArrayList<>();
        }

        AyahBounds last = null;
        if (bounds.size() > 0) {
          last = bounds.get(bounds.size() - 1);
        }

        AyahBounds bound = new AyahBounds(line, position, minX, minY, maxX, maxY);
        if (last != null && last.getLine() == bound.getLine()) {
          last.engulf(bound);
        } else {
          bounds.add(bound);
        }
        ayahBounds.put(key, bounds);
      }
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }

    PageGlyphsCoords glyphCoords = buildGlyphCoords(page, includeGlyphType);
    return new AyahCoordinates(page, ayahBounds, glyphCoords);
  }

  /**
   * QuranApp: Wort-Koordinaten für die Rezitationserkennung.
   * Liest alle Glyphen der Ayat dieser Seite (auch auf Nachbarseiten), damit die
   * Wortnummern bei seitenübergreifenden Ayat durchgehend gezählt werden. Fehlt die
   * Spalte glyph_type, gilt die letzte Glyphe einer Aya als Aya-Ende, alle anderen als Wörter.
   */
  private PageGlyphsCoords buildGlyphCoords(int page, boolean includeGlyphType) {
    GlyphsBuilder glyphsBuilder = new GlyphsBuilder();
    Cursor cursor = null;
    try {
      String ayahKey = COL_SURA + " * 1000 + " + COL_AYAH;
      String columns = COL_PAGE + ", " + COL_LINE + ", " + COL_SURA + ", " + COL_AYAH + ", " +
          COL_POSITION + ", " + MIN_X + ", " + MIN_Y + ", " + MAX_X + ", " + MAX_Y +
          (includeGlyphType ? ", " + GLYPH_TYPE : "");
      cursor = database.rawQuery("SELECT " + columns + " FROM " + GLYPHS_TABLE +
          " WHERE " + ayahKey + " IN (SELECT " + ayahKey + " FROM " + GLYPHS_TABLE +
          " WHERE " + COL_PAGE + " = ?) ORDER BY " + COL_SURA + ", " + COL_AYAH + ", " + COL_PAGE + ", " + COL_POSITION,
          new String[] { String.valueOf(page) });

      int count = cursor.getCount();
      int[] pages = new int[count];
      int[] lines = new int[count];
      int[] suras = new int[count];
      int[] ayahs = new int[count];
      int[] positions = new int[count];
      RectF[] rects = new RectF[count];
      String[] types = new String[count];
      int i = 0;
      while (cursor.moveToNext()) {
        pages[i] = cursor.getInt(0);
        lines[i] = cursor.getInt(1);
        suras[i] = cursor.getInt(2);
        ayahs[i] = cursor.getInt(3);
        positions[i] = cursor.getInt(4);
        rects[i] = new RectF(cursor.getInt(5), cursor.getInt(6), cursor.getInt(7), cursor.getInt(8));
        types[i] = includeGlyphType ? cursor.getString(9) : null;
        i++;
      }

      int wordPosition = 0;
      for (int j = 0; j < count; j++) {
        boolean newAyah = j == 0 || suras[j] != suras[j - 1] || ayahs[j] != ayahs[j - 1];
        if (newAyah) {
          wordPosition = 0;
        }
        boolean lastOfAyah = j == count - 1 || suras[j] != suras[j + 1] || ayahs[j] != ayahs[j + 1];
        String type = types[j] != null ? types[j] : (lastOfAyah ? GlyphsBuilder.END : GlyphsBuilder.WORD);
        if (GlyphsBuilder.WORD.equals(type)) {
          wordPosition++;
        }
        if (pages[j] == page) {
          glyphsBuilder.append(suras[j], ayahs[j], positions[j], lines[j], type, rects[j], wordPosition);
        }
      }
      return new PageGlyphsCoords(page, glyphsBuilder.build());
    } catch (Exception e) {
      Timber.e(e, "Wort-Koordinaten für Seite %d nicht verfügbar", page);
      return null;
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
  }

  private boolean haveVerseMarkerData() {
    Cursor cursor = null;
    try {
      cursor = database.rawQuery("SELECT count(1) from sqlite_master WHERE name = ?",
          new String[] { "ayah_markers" });
      return cursor.moveToFirst() && cursor.getInt(0) > 0;
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
  }

  private boolean haveGlyphData() {
    Cursor cursor = null;
    try {
      cursor = database.query(GLYPHS_TABLE,
          new String[]{GLYPH_TYPE}, null, null, null, null, null, "1");
      return cursor.moveToFirst() && cursor.getString(0) != null;
    } catch (Exception e) {
      // we don't have glyph data (the column doesn't exist)
      return false;
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
  }

  private List<AyahMarkerLocation> getVerseMarkersForPage(int page) {
    final List<AyahMarkerLocation> markers = new ArrayList<>();
    Cursor cursor = null;
    try {
      cursor = database.query("ayah_markers",
          new String[] { "sura_number", "ayah_number", "x", "y" },
          "page_number = ?", new String[] { String.valueOf(page) }, null,
          null, "sura_number, ayah_number ASC");
      while (cursor.moveToNext()) {
        final int sura = cursor.getInt(0);
        final int ayah = cursor.getInt(1);
        final int x = cursor.getInt(2);
        final int y = cursor.getInt(3);
        markers.add(new AyahMarkerLocation(sura, ayah, x, y));
      }
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
    return markers;
  }

  private List<SuraHeaderLocation> getSuraHeadersForPage(int page) {
    final List<SuraHeaderLocation> headers = new ArrayList<>();
    Cursor cursor = null;
    try {
      cursor = database.query("sura_headers",
          new String[] { "sura_number", "x", "y", "width", "height" },
          "page_number = ?", new String[] { String.valueOf(page) }, null,
          null, "sura_number ASC");
      while (cursor.moveToNext()) {
        final int sura = cursor.getInt(0);
        final int x = cursor.getInt(1);
        final int y = cursor.getInt(2);
        final int width = cursor.getInt(3);
        final int height = cursor.getInt(4);
        headers.add(new SuraHeaderLocation(sura, x, y, width, height));
      }
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
    return headers;
  }

  private Cursor getVersesBoundsCursorForPage(int page, boolean withGlyphData) {
    String[] columns = withGlyphData ?
        new String[]{COL_PAGE, COL_LINE, COL_SURA, COL_AYAH, COL_POSITION, MIN_X, MIN_Y, MAX_X, MAX_Y, GLYPH_TYPE} :
        new String[]{COL_PAGE, COL_LINE, COL_SURA, COL_AYAH, COL_POSITION, MIN_X, MIN_Y, MAX_X, MAX_Y};

    return database.query(GLYPHS_TABLE,
        columns,
        COL_PAGE + "=" + page,
        null, null, null,
        COL_SURA + "," + COL_AYAH + "," + COL_POSITION);
  }
}
