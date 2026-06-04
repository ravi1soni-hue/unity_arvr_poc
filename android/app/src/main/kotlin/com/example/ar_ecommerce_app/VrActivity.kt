package com.example.ar_ecommerce_app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * 360° VR panorama activity — equirectangular sphere renderer.
 *
 * Rendering: OpenGL ES 2.0 UV sphere. The camera sits at the sphere's centre;
 * reversed triangle winding makes the interior surface visible (standard
 * equirectangular / "inside-out" panorama technique).
 *
 * Head-tracking: TYPE_ROTATION_VECTOR sensor → 4×4 rotation matrix → view matrix.
 * Touch pan: swipe fallback when gyroscope is absent (emulator).
 * Crash guard: SafeGLSurfaceView catches SwiftShader mGLThread NPE on emulator.
 *
 * Architecture: Flutter → MethodChannel → MainActivity → VrActivity (this file)
 */
class VrActivity : AppCompatActivity(), SensorEventListener {

    private var glSurfaceView: SafeGLSurfaceView? = null
    private var renderer: VrSphereRenderer? = null
    private lateinit var overlayText: TextView
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var productId = "unknown"

    private val rotMatrix   = FloatArray(16)
    private val remapMatrix = FloatArray(16)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        productId = intent.getStringExtra("productId") ?: "unknown"
        title = "VR 360° — $productId"

        overlayText = TextView(this).apply {
            text = "Product: $productId\n360° VR  |  Move device or swipe to look around"
            textSize = 12f
            setPadding(20, 10, 20, 10)
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }

        renderer = VrSphereRenderer(productId)

        glSurfaceView = SafeGLSurfaceView(this).also { sv ->
            sv.setEGLContextClientVersion(2)
            sv.preserveEGLContextOnPause = true
            sv.setRenderer(renderer!!)
            sv.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // Touch pan fallback for emulator / sensor-less devices
        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent,
                    distanceX: Float, distanceY: Float
                ): Boolean {
                    renderer?.applyTouchDelta(distanceX, distanceY)
                    return true
                }
            })
        glSurfaceView!!.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        val root = RelativeLayout(this)
        val matchParent = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT
        )
        val topBar = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }

        root.addView(glSurfaceView!!, matchParent)
        root.addView(overlayText, topBar)
        setContentView(root)

        sensorManager  = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationSensor == null) {
            overlayText.text =
                "Product: $productId\n360° VR  |  Swipe to look around (no gyroscope detected)"
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        glSurfaceView?.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
        // Remap axes for portrait orientation: device Y → world Z (vertical axis)
        SensorManager.remapCoordinateSystem(
            rotMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remapMatrix
        )
        renderer?.setSensorMatrix(remapMatrix)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

// ─────────────────────────────────────────────────────────────────────────────
// SafeGLSurfaceView
// Subclasses GLSurfaceView to catch the SwiftShader emulator NPE that occurs
// when mGLThread (Android's internal field) is null after a failed EGL init.
// ─────────────────────────────────────────────────────────────────────────────

class SafeGLSurfaceView(context: Context) : GLSurfaceView(context) {
    override fun onPause()  { try { super.onPause()  } catch (_: Exception) {} }
    override fun onResume() { try { super.onResume() } catch (_: Exception) {} }
}

// ─────────────────────────────────────────────────────────────────────────────
// VrSphereRenderer — OpenGL ES 2.0 equirectangular sphere
// ─────────────────────────────────────────────────────────────────────────────

class VrSphereRenderer(private val productId: String) : GLSurfaceView.Renderer {

    // Sphere tessellation — 64 slices (horizontal) × 32 stacks (vertical)
    private val SLICES = 64
    private val STACKS = 32
    private val RADIUS = 50f  // large radius keeps geometry well past the near-clip plane

    // ── GLSL shaders ─────────────────────────────────────────────────────────

    private val VERTEX_SRC = """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        uniform mat4 u_MVP;
        varying vec2 v_TexCoord;
        void main() {
            gl_Position = u_MVP * a_Position;
            v_TexCoord  = a_TexCoord;
        }
    """.trimIndent()

    private val FRAGMENT_SRC = """
        precision mediump float;
        uniform sampler2D u_Texture;
        varying vec2 v_TexCoord;
        void main() {
            gl_FragColor = texture2D(u_Texture, v_TexCoord);
        }
    """.trimIndent()

    // ── GL handles ───────────────────────────────────────────────────────────

    private var program       = -1
    private var positionAttr  = -1
    private var texCoordAttr  = -1
    private var mvpUniform    = -1
    private var textureId     = -1
    private var indexCount    = 0

    private lateinit var vertexBuf:   FloatBuffer
    private lateinit var texCoordBuf: FloatBuffer
    private lateinit var indexBuf:    ShortBuffer

    // ── Matrices ─────────────────────────────────────────────────────────────

    private val projMatrix   = FloatArray(16)
    private val viewMatrix   = FloatArray(16)
    private val mvpMatrix    = FloatArray(16)

    // Written by sensor thread via setSensorMatrix(), read on GL thread
    @Volatile private var sensorMatrix: FloatArray? = null

    // Touch pan fallback (no gyroscope)
    @Volatile private var touchYaw   = 0f
    @Volatile private var touchPitch = 0f

    fun setSensorMatrix(mat: FloatArray) {
        val copy = FloatArray(16)
        System.arraycopy(mat, 0, copy, 0, 16)
        sensorMatrix = copy
    }

    fun applyTouchDelta(dx: Float, dy: Float) {
        if (sensorMatrix == null) {
            touchYaw   -= dx * 0.3f
            touchPitch  = (touchPitch + dy * 0.2f).coerceIn(-80f, 80f)
        }
    }

    // ── GL lifecycle ─────────────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // Face culling disabled: camera is inside the sphere; reversed winding
        // handles visibility. Disabling culling simplifies geometry generation.
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        program      = buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        positionAttr = GLES20.glGetAttribLocation(program,  "a_Position")
        texCoordAttr = GLES20.glGetAttribLocation(program,  "a_TexCoord")
        mvpUniform   = GLES20.glGetUniformLocation(program, "u_MVP")

        buildSphereGeometry()
        textureId = uploadPanoramaTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // 90° vertical FOV matches comfortable mobile VR field of view
        Matrix.perspectiveM(projMatrix, 0, 90f, width.toFloat() / height.toFloat(), 0.1f, 200f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Build view matrix from sensor rotation or touch pan
        val sm = sensorMatrix
        if (sm != null) {
            // The sensor rotation matrix rotates device→world.
            // Transposing an orthonormal matrix gives its inverse,
            // which is the camera's world→device transform (view matrix).
            Matrix.transposeM(viewMatrix, 0, sm, 0)
        } else {
            Matrix.setIdentityM(viewMatrix, 0)
            Matrix.rotateM(viewMatrix, 0, touchYaw,   0f, 1f, 0f)
            Matrix.rotateM(viewMatrix, 0, touchPitch, 1f, 0f, 0f)
        }

        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        GLES20.glEnableVertexAttribArray(positionAttr)
        GLES20.glVertexAttribPointer(positionAttr, 3, GLES20.GL_FLOAT, false, 0, vertexBuf)

        GLES20.glEnableVertexAttribArray(texCoordAttr)
        GLES20.glVertexAttribPointer(texCoordAttr, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuf)

        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glDisableVertexAttribArray(texCoordAttr)
    }

    // ── Sphere geometry ──────────────────────────────────────────────────────
    //
    // UV sphere — equirectangular unwrap:
    //   theta (0 → PI)   maps to V texture coordinate (top → bottom)
    //   phi   (0 → 2*PI) maps to U texture coordinate (left → right)
    //
    // Triangle winding is REVERSED compared to an outside-visible sphere,
    // so that faces point inward and are visible from the camera at the origin.

    private fun buildSphereGeometry() {
        val positions = mutableListOf<Float>()
        val texCoords = mutableListOf<Float>()
        val indices   = mutableListOf<Short>()

        for (stack in 0..STACKS) {
            val theta    = Math.PI * stack / STACKS
            val sinTheta = sin(theta).toFloat()
            val cosTheta = cos(theta).toFloat()
            val v        = stack.toFloat() / STACKS

            for (slice in 0..SLICES) {
                val phi    = 2.0 * Math.PI * slice / SLICES
                val sinPhi = sin(phi).toFloat()
                val cosPhi = cos(phi).toFloat()
                val u      = slice.toFloat() / SLICES

                positions += RADIUS * sinTheta * cosPhi   // x
                positions += RADIUS * cosTheta             // y
                positions += RADIUS * sinTheta * sinPhi    // z
                texCoords += u
                texCoords += v
            }
        }

        val stride = SLICES + 1
        for (i in 0 until STACKS) {
            for (j in 0 until SLICES) {
                val tl = (i * stride + j).toShort()           // top-left
                val bl = (tl + stride).toShort()              // bottom-left
                val tr = (tl + 1).toShort()                   // top-right
                val br = (bl + 1).toShort()                   // bottom-right

                // Reversed winding (CW from outside = CCW from inside = front-facing to camera)
                indices += tl; indices += tr; indices += bl
                indices += bl; indices += tr; indices += br
            }
        }

        indexCount  = indices.size
        vertexBuf   = floatBuffer(positions.toFloatArray())
        texCoordBuf = floatBuffer(texCoords.toFloatArray())
        indexBuf    = shortBuffer(indices.toShortArray())
    }

    // ── Panorama texture ─────────────────────────────────────────────────────
    //
    // Procedural equirectangular panorama:
    //   Top 40%    → sky gradient
    //   Middle 35% → horizon / wall gradient
    //   Bottom 25% → floor gradient
    // + vertical room dividers (depth cues) + floor grid lines
    //
    // Replace BitmapFactory.decodeResource(resources, R.drawable.panorama_360)
    // here when a real equirectangular photo is available.

    private fun uploadPanoramaTexture(): Int {
        val w = 2048; val h = 1024
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()

        val skyH     = (h * 0.40f).toInt()
        val wallH    = (h * 0.35f).toInt()
        val floorTop = skyH + wallH

        // Sky
        paint.shader = LinearGradient(0f, 0f, 0f, skyH.toFloat(),
            intArrayOf(0xFF0D1B4B.toInt(), 0xFF1A3A6B.toInt(), 0xFF2E6DA4.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), skyH.toFloat(), paint)

        // Wall / horizon
        paint.shader = LinearGradient(0f, skyH.toFloat(), 0f, floorTop.toFloat(),
            intArrayOf(0xFF2E6DA4.toInt(), 0xFFD4822A.toInt(), 0xFFB5621A.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, skyH.toFloat(), w.toFloat(), floorTop.toFloat(), paint)

        // Floor
        paint.shader = LinearGradient(0f, floorTop.toFloat(), 0f, h.toFloat(),
            intArrayOf(0xFF6B3A10.toInt(), 0xFF2C1A08.toInt(), 0xFF0A0705.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, floorTop.toFloat(), w.toFloat(), h.toFloat(), paint)

        // Vertical room dividers — one every 90° (4 total)
        paint.shader = null
        paint.apply { color = 0x33FFFFFF; strokeWidth = 6f }
        for (i in 1..3) canvas.drawLine(i * w / 4f, 0f, i * w / 4f, h.toFloat(), paint)

        // Horizontal floor grid
        paint.apply { color = 0x1AFFFFFF; strokeWidth = 2f }
        var gy = floorTop.toFloat()
        val step = (h - floorTop) / 8f
        while (gy <= h) {
            canvas.drawLine(0f, gy, w.toFloat(), gy, paint)
            gy += step
        }

        // Upload to GL
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return id
    }

    // ── GLSL helpers ─────────────────────────────────────────────────────────

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vert)
            GLES20.glAttachShader(it, frag)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(data); position(0) }

    private fun shortBuffer(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .apply { put(data); position(0) }
}
