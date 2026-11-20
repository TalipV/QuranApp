package com.quran.mobile.recitation.tilawa

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.min
import kotlin.math.sin

/**
 * Großes Mikrofon-Symbol für „Stelle per Rezitation finden“, das den Zustand sichtbar macht:
 * - [Phase.LOADING]: grau, ohne Bewegung (Modell lädt noch)
 * - [Phase.READY]: farbig, ruhiges „Atmen“ – bereit zum Zuhören
 * - [Phase.HEARING]: Ringe wachsen mit der Lautstärke, dazu ein drehender Bogen (Verarbeitung)
 * - [Phase.PROCESSING]: drehender Bogen – Gehörtes wird ausgewertet
 * - [Phase.FOUND]: grün mit Häkchen
 * - [Phase.ERROR]: rot
 */
class TilawaListeningIndicator(context: Context, private val accentColor: Int) : View(context) {

  enum class Phase { LOADING, READY, HEARING, PROCESSING, FOUND, ERROR }

  var phase: Phase = Phase.LOADING
    set(value) {
      if (field == value) return
      field = value
      if (value != Phase.HEARING) level = 0f
      invalidate()
    }

  private val density = resources.displayMetrics.density
  private var level = 0f
  private var shownLevel = 0f
  private var time = 0f

  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeWidth = 4 * density
  }
  private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
  private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    strokeWidth = 4 * density
  }
  private val micStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 1.7f
    strokeCap = Paint.Cap.ROUND
  }
  private val micArcRect = RectF(5.85f, 4.9f, 18.15f, 17.1f)
  private val arcRect = RectF()
  private val micPath = Path()
  private val checkPath = Path()

  private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = 1_000
    repeatCount = ValueAnimator.INFINITE
    interpolator = LinearInterpolator()
    addUpdateListener {
      time = (SystemClock.uptimeMillis() % 3_600_000L) / 1000f
      // sanft an den aktuellen Pegel annähern
      shownLevel += (level - shownLevel) * 0.25f
      invalidate()
    }
  }

  /** Mikrofonpegel als RMS (0 … ~0.3); wird intern auf 0 … 1 abgebildet. */
  fun setLevel(rms: Float) {
    level = (rms / 0.08f).coerceIn(0f, 1f)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    animator.start()
  }

  override fun onDetachedFromWindow() {
    animator.cancel()
    super.onDetachedFromWindow()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val size = (140 * density).toInt()
    setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
  }

  override fun onDraw(canvas: Canvas) {
    val cx = width / 2f
    val cy = height / 2f
    val maxRadius = min(width, height) / 2f
    val core = maxRadius * 0.48f

    val color = when (phase) {
      Phase.LOADING -> Color.GRAY
      Phase.FOUND -> Color.rgb(46, 160, 67)
      Phase.ERROR -> Color.rgb(200, 50, 50)
      else -> accentColor
    }

    // Ringe um den Kreis
    when (phase) {
      Phase.READY -> {
        val breath = (sin(time * 2.5f) + 1f) / 2f
        drawRing(canvas, cx, cy, core * (1.12f + 0.12f * breath), color, 50)
      }
      Phase.HEARING -> {
        val extra = (maxRadius - core) * shownLevel
        drawRing(canvas, cx, cy, core + extra, color, 45)
        drawRing(canvas, cx, cy, core + extra * 0.55f, color, 80)
      }
      Phase.PROCESSING -> drawRing(canvas, cx, cy, core * 1.12f, color, 40)
      else -> Unit
    }

    // drehender Bogen = „wird verarbeitet“
    if (phase == Phase.HEARING || phase == Phase.PROCESSING) {
      val r = maxRadius - arcPaint.strokeWidth
      arcRect.set(cx - r, cy - r, cx + r, cy + r)
      arcPaint.color = color
      val start = (time * 300f) % 360f
      canvas.drawArc(arcRect, start, 90f, false, arcPaint)
    }

    fillPaint.color = color
    canvas.drawCircle(cx, cy, core, fillPaint)

    if (phase == Phase.FOUND) drawCheck(canvas, cx, cy, core) else drawMic(canvas, cx, cy, core)
  }

  private fun drawRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
    ringPaint.color = ColorUtils.setAlphaComponent(color, alpha)
    canvas.drawCircle(cx, cy, radius, ringPaint)
  }

  /** Mikrofon (Material „mic“, 24er-Raster) passend in den Kreis skaliert. */
  private fun drawMic(canvas: Canvas, cx: Float, cy: Float, core: Float) {
    val scale = core * 1.2f / 24f
    micPath.reset()
    micPath.addRoundRect(9f, 2f, 15f, 14f, 3f, 3f, Path.Direction.CW)
    canvas.save()
    canvas.translate(cx - 12f * scale, cy - 12f * scale)
    canvas.scale(scale, scale)
    canvas.drawPath(micPath, iconPaint)
    canvas.drawArc(micArcRect, 0f, 180f, false, micStrokePaint)
    canvas.drawLine(12f, 17.2f, 12f, 21f, micStrokePaint)
    canvas.restore()
  }

  private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, core: Float) {
    val s = core * 0.45f
    checkPath.reset()
    checkPath.moveTo(cx - s, cy)
    checkPath.lineTo(cx - s * 0.25f, cy + s * 0.7f)
    checkPath.lineTo(cx + s, cy - s * 0.6f)
    canvas.drawPath(checkPath, checkPaint)
  }
}
