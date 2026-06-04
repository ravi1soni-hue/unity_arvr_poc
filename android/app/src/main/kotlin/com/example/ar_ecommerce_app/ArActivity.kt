package com.example.ar_ecommerce_app

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
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
 *
 * Lifecycle follows the pattern mandated by Google's ARCore docs:
 *   onResume → camera permission check → ArCoreApk.requestInstall() → Session creation
 * Session init is deferred to onResume (not onCreate) because requestInstall() sends
 * the user to Play Store and activity returns via onResume, not onCreate.
 *
 * Rendering: OpenGL ES 2.0 OES texture for live camera background.
 * UV coords are transformed per-frame via Frame.transformCoordinates2d() to
 * correct for camera sensor orientation vs. display rotation.
 * Tap on a detected plane to drop a virtual product anchor.
 */
class ArActivity : AppCompatActivity() {

    private var glSurfaceView: GLSurfaceView? = null
    private lateinit var statusText: TextView
    private var arSession: Session? = null
    private var renderer: ArCoreRenderer? = null
    private var productId = "unknown"
    private var installRequested = false   // tracks whether requestInstall was already called

    companion object {
        private const val RC_CAMERA = 100
    }

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
    }

    override fun onResume() {
        super.onResume()

        // Step 1 — Runtime camera permission (Android 6+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), RC_CAMERA
            )
            return
        }

        // Step 2 — ARCore availability check
        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                showFallback(
                    "ARCore is not supported on this device.\nProduct: $productId\n\n" +
                    "On a compatible device this view renders live camera feed with plane " +
                    "detection and lets you tap to place the product in your room."
                )
                return
            }
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> {
                showFallback(
                    "ARCore availability check failed.\nProduct: $productId\n\n" +
                    "Ensure Google Play Services for AR is installed."
                )
                return
            }
            else -> { /* SUPPORTED_* variants or UNKNOWN_CHECKING — proceed */ }
        }

        // Step 3 — requestInstall() is mandatory for both AR Required and AR Optional apps.
        // When Play Store installs ARCore the activity pauses; onResume re-enters here.
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> { /* Ready to create session */ }
            }
        } catch (e: Exception) {
            showFallback("ARCore unavailable: ${e.message}\nProduct: $productId")
            return
        }

        // Step 4 — Create ARCore session (only once; arSession is reused across pause/resume)
        if (arSession == null) {
            try {
                val session = Session(this)
                session.configure(Config(session).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                })
                arSession = session

                val r = ArCoreRenderer(session, productId) { planes, anchors ->
                    runOnUiThread {
                        statusText.text =
                            "Product: $productId\n" +
                            "Planes: $planes  |  Anchors: $anchors\n" +
                            if (planes == 0) "Point camera at a flat surface…"
                            else "Tap a highlighted surface to place product"
                    }
                }
                renderer = r
                glSurfaceView?.setRenderer(r)
                glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glSurfaceView?.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) renderer?.onTap(event.x, event.y)
                    true
                }
            } catch (e: UnavailableUserDeclinedInstallationException) {
                showFallback("ARCore installation was declined.\nProduct: $productId"); return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                showFallback("Device not compatible with ARCore.\nProduct: $productId"); return
            } catch (e: Exception) {
                Log.e("ArActivity", "ARCore session error", e)
                showFallback("AR session error: ${e.message}\nProduct: $productId"); return
            }
        }

        // Step 5 — Resume session and GL surface
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_CAMERA &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            showFallback("Camera permission required for AR.\nProduct: $productId")
        }
        // If granted: system triggers onResume automatically — no extra action needed
    }

    private fun showFallback(msg: String) {
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
}

// ---------------------------------------------------------------------------
// OpenGL ES 2.0 renderer — live camera background + tap-to-place anchors
// ---------------------------------------------------------------------------
class ArCoreRenderer(
    private val session: Session,
    private val productId: String,
    private val onUpdate: (planes: Int, anchors: Int) -> Unit
) : GLSurfaceView.Renderer {

    private var cameraTextureId  = -1
    private var bgProgram        = -1
    private var bgPositionHandle = -1
    private var bgTexCoordHandle = -1
    private lateinit var bgVertexBuffer: FloatBuffer
    // UV coords are regenerated per frame; null until first frame is drawn
    private var bgTexCoordBuffer: FloatBuffer? = null

    private val anchors = mutableListOf<com.google.ar.core.Anchor>()
    @Volatile private var pendingTapX = -1f
    @Volatile private var pendingTapY = -1f

    // NDC quad vertices — input to Frame.transformCoordinates2d()
    private val QUAD_NDC = floatArrayOf(-1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f)

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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        cameraTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        session.setCameraTextureName(cameraTextureId)

        bgProgram        = buildProgram(BG_VERTEX_SRC, BG_FRAGMENT_SRC)
        bgPositionHandle = GLES20.glGetAttribLocation(bgProgram, "a_Position")
        bgTexCoordHandle = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")
        bgVertexBuffer   = floatBuffer(QUAD_NDC)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session.setDisplayGeometry(Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val frame = try { session.update() } catch (e: Exception) { return }

        // ARCore transforms the UV coords each frame to account for camera sensor
        // orientation relative to the display rotation. Hardcoded UVs are wrong on
        // devices where these don't match (most landscape/rotated configurations).
        val transformedUVs = FloatArray(8)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, QUAD_NDC,
            Coordinates2d.TEXTURE_NORMALIZED,                   transformedUVs
        )
        bgTexCoordBuffer = floatBuffer(transformedUVs)

        drawCameraBackground()

        // Process pending tap on GL thread (tap originates on main thread via @Volatile)
        val tx = pendingTapX; val ty = pendingTapY
        if (tx >= 0f && ty >= 0f) {
            pendingTapX = -1f; pendingTapY = -1f
            for (hit in frame.hitTest(tx, ty)) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                    anchors.add(hit.createAnchor())
                    break
                }
            }
        }

        val planeCount = session.getAllTrackables(Plane::class.java)
            .count { it.trackingState == TrackingState.TRACKING }
        onUpdate(planeCount, anchors.size)
    }

    private fun drawCameraBackground() {
        val texCoords = bgTexCoordBuffer ?: return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(bgProgram)

        GLES20.glVertexAttribPointer(bgPositionHandle, 2, GLES20.GL_FLOAT, false, 0, bgVertexBuffer)
        GLES20.glEnableVertexAttribArray(bgPositionHandle)
        GLES20.glVertexAttribPointer(bgTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
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

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(data); position(0) }
}
