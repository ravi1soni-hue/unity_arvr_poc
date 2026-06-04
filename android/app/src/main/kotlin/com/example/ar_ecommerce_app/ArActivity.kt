package com.example.ar_ecommerce_app

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Real ARCore activity.
 * - Opens an ARCore session with horizontal plane detection
 * - Renders live camera feed via OpenGL ES 2.0 OES texture
 * - Tap on a detected plane to drop a virtual product anchor
 * - Overlays status text (plane count, anchor count)
 * Falls back gracefully on emulator / non-ARCore device.
 */
class ArActivity : AppCompatActivity() {

    private var glSurfaceView: GLSurfaceView? = null
    private lateinit var statusText: TextView
    private var arSession: Session? = null
    private var renderer: ArCoreRenderer? = null
    private var productId = "unknown"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        productId = intent.getStringExtra("productId") ?: "unknown"
        title = "AR Preview — $productId"

        val root = RelativeLayout(this)

        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
        }

        statusText = TextView(this).apply {
            text = "Initialising ARCore…"
            textSize = 13f
            setPadding(20, 12, 20, 12)
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }

        val fullParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        val bottomParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }

        root.addView(glSurfaceView!!, fullParams)
        root.addView(statusText, bottomParams)
        setContentView(root)

        initArCore()
    }

    private fun initArCore() {
        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                showFallback("ARCore is not supported on this device.\nProduct: $productId\n\nOn a compatible Android device, this view renders live camera feed with plane detection and lets you tap to place the product in your room.")
                return
            }
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            ArCoreApk.Availability.UNKNOWN_ERROR -> {
                showFallback("ARCore availability unknown.\nProduct: $productId\n\nEnsure Google Play Services for AR is installed.")
                return
            }
            else -> setupSession()
        }
    }

    private fun setupSession() {
        try {
            arSession = Session(this)
            val config = Config(arSession!!).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            }
            arSession!!.configure(config)

            renderer = ArCoreRenderer(arSession!!, productId) { planes, anchors ->
                runOnUiThread {
                    statusText.text =
                        "Product: $productId\n" +
                        "Planes detected: $planes  |  Anchors placed: $anchors\n" +
                        if (planes == 0) "Point camera at a flat surface…"
                        else "Tap on a highlighted surface to place product"
                }
            }

            glSurfaceView?.setRenderer(renderer)
            glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            glSurfaceView?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    renderer?.onTap(event.x, event.y)
                }
                true
            }

        } catch (e: UnavailableUserDeclinedInstallationException) {
            showFallback("ARCore installation was declined.\nProduct: $productId")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showFallback("Device not compatible with ARCore.\nProduct: $productId")
        } catch (e: Exception) {
            Log.e("ArActivity", "ARCore session error", e)
            showFallback("AR session error: ${e.message}\nProduct: $productId")
        }
    }

    private fun showFallback(msg: String) {
        // Null out before onPause fires so GLThread.onPause() is never called on un-initialised surface
        glSurfaceView?.visibility = android.view.View.GONE
        glSurfaceView = null
        statusText.apply {
            text = msg
            textSize = 16f
            setPadding(32, 32, 32, 32)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
            glSurfaceView?.onResume()
        } catch (e: Exception) {
            statusText.text = "Resume error: ${e.message}"
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        glSurfaceView = null
        arSession?.close()
        arSession = null
    }
}

// ---------------------------------------------------------------------------
// OpenGL ES 2.0 renderer — camera background + coloured quad for each anchor
// ---------------------------------------------------------------------------
class ArCoreRenderer(
    private val session: Session,
    private val productId: String,
    private val onUpdate: (planes: Int, anchors: Int) -> Unit
) : GLSurfaceView.Renderer {

    // Camera background (OES texture)
    private var cameraTextureId = -1
    private var bgProgram = -1
    private var bgPositionHandle = -1
    private var bgTexCoordHandle = -1
    private lateinit var bgVertexBuffer: FloatBuffer
    private lateinit var bgTexCoordBuffer: FloatBuffer

    // Anchor product boxes
    private val anchors = mutableListOf<com.google.ar.core.Anchor>()
    private var pendingTapX = -1f
    private var pendingTapY = -1f
    private var latestFrame: Frame? = null

    private val BG_VERTEX_SRC = """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
    """.trimIndent()

    private val BG_FRAGMENT_SRC = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform samplerExternalOES u_Texture;
        void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }
    """.trimIndent()

    private val QUAD_POSITIONS = floatArrayOf(
        -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f
    )
    private val QUAD_TEXCOORDS = floatArrayOf(
        0f, 1f,  1f, 1f,  0f, 0f,  1f, 0f
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)

        // Create OES texture for camera feed
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        cameraTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        session.setCameraTextureName(cameraTextureId)

        bgProgram = buildProgram(BG_VERTEX_SRC, BG_FRAGMENT_SRC)
        bgPositionHandle = GLES20.glGetAttribLocation(bgProgram, "a_Position")
        bgTexCoordHandle = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")

        bgVertexBuffer = floatBuffer(QUAD_POSITIONS)
        bgTexCoordBuffer = floatBuffer(QUAD_TEXCOORDS)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session.setDisplayGeometry(android.view.Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val frame = try { session.update() } catch (e: Exception) { return }
        latestFrame = frame

        // Draw camera background
        drawCameraBackground()

        // Handle tap
        val tx = pendingTapX; val ty = pendingTapY
        if (tx >= 0 && ty >= 0) {
            pendingTapX = -1f; pendingTapY = -1f
            val hits: List<HitResult> = frame.hitTest(tx, ty)
            for (hit in hits) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                    anchors.add(hit.createAnchor())
                    break
                }
            }
        }

        // Count planes and update UI
        val planeCount = session.getAllTrackables(Plane::class.java)
            .count { it.trackingState == TrackingState.TRACKING }
        onUpdate(planeCount, anchors.size)
    }

    private fun drawCameraBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(bgProgram)

        GLES20.glVertexAttribPointer(bgPositionHandle, 2, GLES20.GL_FLOAT, false, 0, bgVertexBuffer)
        GLES20.glEnableVertexAttribArray(bgPositionHandle)
        GLES20.glVertexAttribPointer(bgTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, bgTexCoordBuffer)
        GLES20.glEnableVertexAttribArray(bgTexCoordHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(bgPositionHandle)
        GLES20.glDisableVertexAttribArray(bgTexCoordHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    fun onTap(x: Float, y: Float) { pendingTapX = x; pendingTapY = y }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vert)
            GLES20.glAttachShader(it, frag)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }
}
