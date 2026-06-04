package com.example.ar_ecommerce_app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/**
 * 360° VR panorama activity.
 *
 * Rendering: Canvas-based panorama bitmap — no GLSurfaceView, no GL thread.
 * Head-tracking: TYPE_ROTATION_VECTOR sensor (real device) or pan gesture (emulator).
 * Architecture: Flutter → MethodChannel → MainActivity → VrActivity (this file)
 */
class VrActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var panoramaView: VrPanoramaView
    private lateinit var overlayText: TextView
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var productId = "unknown"

    // Rotation matrix from sensor (written on sensor thread, read on main thread — @Volatile)
    @Volatile private var sensorYaw = 0f
    @Volatile private var sensorPitch = 0f
    private val rotMatrix = FloatArray(16)
    private val remapMatrix = FloatArray(16)
    private val orientValues = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        productId = intent.getStringExtra("productId") ?: "unknown"
        title = "VR 360° — $productId"

        panoramaView = VrPanoramaView(this)

        overlayText = TextView(this).apply {
            text = "Product: $productId\n360° VR Showroom  |  Move device or swipe to look around"
            textSize = 12f
            setPadding(20, 10, 20, 10)
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }

        val root = RelativeLayout(this)
        val matchParent = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT
        )
        val topBar = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }

        root.addView(panoramaView, matchParent)
        root.addView(overlayText, topBar)
        setContentView(root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationSensor == null) {
            overlayText.text = "Product: $productId\n360° VR Showroom  |  Swipe to look around"
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        panoramaView.startRendering()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        panoramaView.stopRendering()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
        SensorManager.remapCoordinateSystem(rotMatrix,
            SensorManager.AXIS_X, SensorManager.AXIS_Z, remapMatrix)
        SensorManager.getOrientation(remapMatrix, orientValues)
        sensorYaw   = Math.toDegrees(orientValues[0].toDouble()).toFloat()
        sensorPitch = Math.toDegrees(orientValues[1].toDouble()).toFloat()
        panoramaView.setHeadRotation(sensorYaw, sensorPitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

// ─────────────────────────────────────────────────────────────────────────────
// Canvas-based 360° panorama view
// ─────────────────────────────────────────────────────────────────────────────

class VrPanoramaView(context: Context) : View(context) {

    // Panorama bitmap: 4× screen width so one full yaw sweep scrolls across it
    private var panorama: Bitmap? = null
    private val drawMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Head orientation (degrees)
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f

    // Touch pan fallback
    private var lastTouchX = 0f
    private var touchYaw = 0f
    private var touchPitch = 0f
    private var usingSensor = false

    // Choreographer-driven render loop
    private var rendering = false
    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            if (rendering) android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (!usingSensor) {
                    touchYaw  -= distanceX * 0.3f
                    touchPitch = (touchPitch + distanceY * 0.2f).coerceIn(-60f, 60f)
                    yaw   = touchYaw
                    pitch = touchPitch
                }
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) panorama = buildPanorama(w * 4, h + 200)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = panorama ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()

        // Horizontal offset from yaw: full 360° maps to full bitmap width
        val normalizedYaw = ((yaw % 360f) + 360f) % 360f
        val xOffset = -(normalizedYaw / 360f) * bmpW

        // Vertical offset from pitch: ±60° maps to ±half bitmap height
        val yOffset = (pitch / 60f) * ((bmpH - h) / 2f)

        drawMatrix.reset()
        // Scale bitmap to screen height, keep aspect ratio
        val scale = h / bmpH
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(xOffset * scale, -yOffset * scale)

        // Wrap: if bitmap scrolls off left edge, draw second copy to the right
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

        val scaledW = bmpW * scale
        if (xOffset * scale + scaledW < w) {
            val wrapMatrix = Matrix(drawMatrix)
            wrapMatrix.postTranslate(scaledW, 0f)
            canvas.drawBitmap(bmp, wrapMatrix, bitmapPaint)
        }
        if (xOffset * scale > 0) {
            val wrapMatrix = Matrix(drawMatrix)
            wrapMatrix.postTranslate(-scaledW, 0f)
            canvas.drawBitmap(bmp, wrapMatrix, bitmapPaint)
        }
    }

    fun setHeadRotation(yawDeg: Float, pitchDeg: Float) {
        usingSensor = true
        yaw   = yawDeg
        pitch = pitchDeg.coerceIn(-60f, 60f)
    }

    fun startRendering() {
        rendering = true
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stopRendering() {
        rendering = false
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // ── Panorama bitmap ──────────────────────────────────────────────────────

    private fun buildPanorama(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()

        // Sky gradient (top 40%)
        val skyH = (h * 0.4f).toInt()
        paint.shader = LinearGradient(
            0f, 0f, 0f, skyH.toFloat(),
            intArrayOf(0xFF0D1B4B.toInt(), 0xFF1A3A6B.toInt(), 0xFF2E6DA4.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), skyH.toFloat(), paint)

        // Horizon / wall band (middle 35%)
        val wallTop = skyH
        val wallH = (h * 0.35f).toInt()
        paint.shader = LinearGradient(
            0f, wallTop.toFloat(), 0f, (wallTop + wallH).toFloat(),
            intArrayOf(0xFF2E6DA4.toInt(), 0xFFD4822A.toInt(), 0xFFB5621A.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, wallTop.toFloat(), w.toFloat(), (wallTop + wallH).toFloat(), paint)

        // Floor (bottom 25%)
        val floorTop = wallTop + wallH
        paint.shader = LinearGradient(
            0f, floorTop.toFloat(), 0f, h.toFloat(),
            intArrayOf(0xFF6B3A10.toInt(), 0xFF2C1A08.toInt(), 0xFF0A0705.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, floorTop.toFloat(), w.toFloat(), h.toFloat(), paint)

        // Vertical room dividers to give depth cues every 90°
        val dividerPaint = Paint().apply {
            color = 0x22FFFFFF
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val step = w / 4
        for (i in 1..3) {
            canvas.drawLine(
                (i * step).toFloat(), 0f,
                (i * step).toFloat(), h.toFloat(),
                dividerPaint
            )
        }

        // Subtle floor grid lines for presence
        val gridPaint = Paint().apply {
            color = 0x1AFFFFFF
            strokeWidth = 1f
        }
        var gy = floorTop.toFloat()
        while (gy < h) {
            canvas.drawLine(0f, gy, w.toFloat(), gy, gridPaint)
            gy += (h - floorTop) / 8f
        }

        paint.shader = null
        return bmp
    }
}
