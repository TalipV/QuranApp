package com.quran.labs.androidquran.recitation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.quran.labs.androidquran.QuranForwarderActivity
import com.quran.labs.androidquran.R
import org.json.JSONObject

/** Erkennt per Mikrofon, welche Aya gerade rezitiert wird (Tilawa, offline). */
class RecitationActivity : AppCompatActivity(), TilawaEngine.Listener {

  private lateinit var engine: TilawaEngine
  private lateinit var statusView: TextView
  private lateinit var locationView: TextView
  private lateinit var verseView: TextView
  private lateinit var progressView: TextView
  private lateinit var toggleButton: Button
  private lateinit var jumpButton: Button

  private var isReady = false
  private var lastSura = -1
  private var lastAyah = -1

  private val requestMicrophone =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startListening() else statusView.setText(R.string.recitation_permission_denied)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_recitation)

    val root = findViewById<ViewGroup>(R.id.root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
      val insets = windowInsets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
      )
      root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        topMargin = insets.top
        bottomMargin = insets.bottom
        leftMargin = insets.left
        rightMargin = insets.right
      }
      windowInsets
    }

    val toolbar = findViewById<Toolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.menu_recitation)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    statusView = findViewById(R.id.recitation_status)
    locationView = findViewById(R.id.recitation_location)
    verseView = findViewById(R.id.recitation_verse)
    progressView = findViewById(R.id.recitation_progress)
    toggleButton = findViewById(R.id.recitation_toggle)
    jumpButton = findViewById(R.id.recitation_jump)

    statusView.setText(R.string.recitation_loading)
    toggleButton.setOnClickListener { onToggleClicked() }
    jumpButton.setOnClickListener { jumpToLastAyah() }

    engine = TilawaEngine(this, this)
    findViewById<FrameLayout>(R.id.recitation_webview_container).addView(engine.webView)
  }

  override fun onStop() {
    super.onStop()
    if (engine.recording) {
      engine.stopRecording()
      onRecordingStopped()
    }
  }

  override fun onDestroy() {
    engine.destroy()
    super.onDestroy()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      finish()
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onTilawaMessage(message: JSONObject) {
    when (message.optString("type")) {
      "loading" -> statusView.setText(R.string.recitation_loading)
      "ready" -> {
        isReady = true
        toggleButton.isEnabled = true
        statusView.setText(R.string.recitation_ready)
      }
      "event" -> onTilawaEvent(message.getJSONObject("event"))
      "stopped" -> if (!engine.recording) statusView.setText(R.string.recitation_stopped)
      "error" -> statusView.text = getString(R.string.recitation_error, message.optString("message"))
    }
  }

  private fun onTilawaEvent(event: JSONObject) {
    when (event.optString("type")) {
      "verse_match" -> {
        lastSura = event.getInt("surah")
        lastAyah = event.getInt("ayah")
        locationView.text = getString(R.string.recitation_location, event.optString("surah_name"), lastAyah)
        verseView.text = event.optString("verse_text")
        progressView.text = ""
        jumpButton.isEnabled = true
      }
      "word_progress" -> {
        if (event.getInt("surah") == lastSura && event.getInt("ayah") == lastAyah) {
          progressView.text = getString(
            R.string.recitation_word_progress,
            event.getInt("word_index") + 1,
            event.getInt("total_words")
          )
        }
      }
    }
  }

  private fun onToggleClicked() {
    if (engine.recording) {
      engine.stopRecording()
      onRecordingStopped()
    } else if (isReady) {
      val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      if (granted) startListening() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  private fun startListening() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) return

    engine.startRecording()
    if (!engine.recording) return
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    toggleButton.setText(R.string.recitation_stop)
    statusView.setText(R.string.recitation_listening)
  }

  private fun onRecordingStopped() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    toggleButton.setText(R.string.recitation_start)
    statusView.setText(R.string.recitation_stopped)
  }

  private fun jumpToLastAyah() {
    if (lastSura < 1 || lastAyah < 1) return
    startActivity(
      Intent(Intent.ACTION_VIEW, "quran://$lastSura/$lastAyah".toUri(), this, QuranForwarderActivity::class.java)
    )
  }
}
