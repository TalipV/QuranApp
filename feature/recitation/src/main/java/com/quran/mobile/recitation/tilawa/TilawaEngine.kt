package com.quran.mobile.recitation.tilawa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresPermission
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hostet @tilawa/core (TypeScript) in einer unsichtbaren WebView.
 *
 * Die Seite liegt unter assets/recitation/ und wird über eine virtuelle
 * https-Origin ausgeliefert (sicherer Kontext für WebAssembly). Audio wird
 * nativ mit 16 kHz mono float32 aufgenommen und in 300-ms-Stücken an JS gereicht.
 */
class TilawaEngine(context: Context, private val listener: Listener) {

  fun interface Listener {
    fun onTilawaMessage(message: JSONObject)
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile
  private var isRecording = false
  private var recordThread: Thread? = null

  @SuppressLint("SetJavaScriptEnabled")
  val webView: WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    webViewClient = AssetWebViewClient(context.applicationContext)
    addJavascriptInterface(HostBridge(), "TilawaHost")
    loadUrl("https://$HOST/recitation/index.html")
  }

  val recording: Boolean
    get() = isRecording

  /**
   * Startet die Aufnahme. [configJson] geht an `window.tilawa.configure`
   * (Modus, erwartete Passage, Weiterlaufen nach Fehlern).
   */
  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  fun startRecording(configJson: String) {
    if (isRecording) return

    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    val recorder = AudioRecord(
      MediaRecorder.AudioSource.VOICE_RECOGNITION,
      SAMPLE_RATE,
      CHANNEL,
      ENCODING,
      maxOf(minBuffer, CHUNK_SAMPLES * BYTES_PER_SAMPLE * 2)
    )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      recorder.release()
      postError("Mikrofon konnte nicht initialisiert werden")
      return
    }

    evaluate("window.tilawa.reset()")
    evaluate("window.tilawa.configure(${JSONObject.quote(configJson)})")
    isRecording = true
    recorder.startRecording()
    recordThread = Thread({ recordLoop(recorder) }, "tilawa-audio").apply { start() }
  }

  fun stopRecording(flush: Boolean = true) {
    if (!isRecording) return
    isRecording = false
    recordThread?.join(1_000)
    recordThread = null
    if (flush) evaluate("window.tilawa.stop()")
  }

  fun destroy() {
    stopRecording(flush = false)
    (webView.parent as? ViewGroup)?.removeView(webView)
    webView.removeJavascriptInterface("TilawaHost")
    webView.destroy()
  }

  private fun recordLoop(recorder: AudioRecord) {
    val samples = FloatArray(CHUNK_SAMPLES)
    val bytes = ByteBuffer.allocate(CHUNK_SAMPLES * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
    try {
      while (isRecording) {
        // In 100-ms-Stücken lesen, damit die Pegelanzeige flüssig reagiert;
        // an JS geht weiterhin ein 300-ms-Block.
        var filled = 0
        while (isRecording && filled < CHUNK_SAMPLES) {
          val size = minOf(LEVEL_SAMPLES, CHUNK_SAMPLES - filled)
          val read = recorder.read(samples, filled, size, AudioRecord.READ_BLOCKING)
          if (read <= 0) continue
          postLevel(rms(samples, filled, read))
          filled += read
        }
        if (filled == 0) continue
        bytes.clear()
        bytes.asFloatBuffer().put(samples, 0, filled)
        val base64 = Base64.encodeToString(bytes.array(), 0, filled * BYTES_PER_SAMPLE, Base64.NO_WRAP)
        evaluate("window.tilawa.feed('$base64')")
      }
    } catch (e: Exception) {
      Timber.e(e, "Tilawa-Aufnahme fehlgeschlagen")
      postError(e.message ?: e.toString())
    } finally {
      recorder.stop()
      recorder.release()
    }
  }

  private fun evaluate(script: String) {
    mainHandler.post { webView.evaluateJavascript(script, null) }
  }

  private fun rms(samples: FloatArray, offset: Int, count: Int): Float {
    var sum = 0.0
    for (i in offset until offset + count) sum += samples[i] * samples[i]
    return kotlin.math.sqrt(sum / count).toFloat()
  }

  /** Mikrofonpegel (RMS) als `{type: "level", rms}` – für die Anzeige „ich höre zu“. */
  private fun postLevel(rms: Float) {
    mainHandler.post {
      listener.onTilawaMessage(JSONObject().put("type", "level").put("rms", rms.toDouble()))
    }
  }

  private fun postError(message: String) {
    mainHandler.post {
      listener.onTilawaMessage(JSONObject().put("type", "error").put("message", message))
    }
  }

  private inner class HostBridge {
    @JavascriptInterface
    fun postMessage(json: String) {
      val message = try {
        JSONObject(json)
      } catch (e: Exception) {
        Timber.w(e, "Ungültige Nachricht von Tilawa")
        return
      }
      mainHandler.post { listener.onTilawaMessage(message) }
    }
  }

  /** Liefert assets/ unter https://[HOST]/ mit korrekten MIME-Typen aus. */
  private class AssetWebViewClient(private val context: Context) : WebViewClient() {
    override fun shouldInterceptRequest(
      view: WebView,
      request: WebResourceRequest
    ): WebResourceResponse? {
      val url = request.url
      if (url.host != HOST) return notFound()
      val path = url.path?.removePrefix("/").orEmpty()
      if (path.isEmpty() || path.contains("..")) return notFound()
      return try {
        val headers = mutableMapOf<String, String>()
        // Länge mitgeben, damit die Seite den Ladefortschritt anzeigen kann
        // (klappt für unkomprimierte Assets wie Modell und WASM)
        runCatching { context.assets.openFd(path).use { it.length } }
          .getOrNull()?.takeIf { it > 0 }?.let { headers["Content-Length"] = it.toString() }
        WebResourceResponse(mimeType(path), null, 200, "OK", headers, context.assets.open(path))
      } catch (e: IOException) {
        Timber.w(e, "Asset fehlt: %s", path)
        notFound()
      }
    }

    private fun notFound() =
      WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "")) {
      "html" -> "text/html"
      "js", "mjs" -> "text/javascript"
      "json" -> "application/json"
      "wasm" -> "application/wasm"
      else -> "application/octet-stream"
    }
  }

  companion object {
    private const val HOST = "appassets.androidplatform.net"
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
    private const val BYTES_PER_SAMPLE = 4
    private const val CHUNK_SAMPLES = 4_800 // 300 ms
    private const val LEVEL_SAMPLES = 1_600 // 100 ms
  }
}
