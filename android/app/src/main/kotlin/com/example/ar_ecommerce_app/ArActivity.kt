package com.example.ar_ecommerce_app

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArActivity : AppCompatActivity() {

    private var glSurfaceView: GLSurfaceView? = null
    private lateinit var statusText: TextView
    private var arSession: Session? = null
    private var renderer: ArCoreRenderer? = null
    private var productId = "unknown"
    private var installRequested = false

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), RC_CAMERA
            )
            return
        }

        try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }
        } catch (e: Exception) {
            showFallback("ARCore error: ${e.message}")
            return
        }

        if (arSession == null) {
            try {
                val session = Session(this)
                session.configure(Config(session).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                })
                arSession = session

                renderer = ArCoreRenderer(session) { planes, anchors ->
                    runOnUiThread {
                        statusText.text = "Product: $productId\nPlanes: $planes | Anchors: $anchors\n" +
                            if (planes == 0) "Move phone to find floor..." else "Tap the floor to place product"
                    }
                }
                glSurfaceView?.setRenderer(renderer)
                glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glSurfaceView?.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) renderer?.onTap(event.x, event.y)
                    true
                }
            } catch (e: Exception) {
                showFallback("AR Init failed: ${e.message}")
            }
        }

        arSession?.resume()
        glSurfaceView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
    }

    private fun showFallback(msg: String) {
        statusText.text = msg
    }
}

class ArCoreRenderer(
    private val session: Session,
    private val onUpdate: (planes: Int, anchors: Int) -> Unit
) : GLSurfaceView.Renderer {

    private var cameraTextureId = -1
    private var bgProgram = -1
    private var objProgram = -1
    
    private val anchors = mutableListOf<com.google.ar.core.Anchor>()
    @Volatile private var pendingTapX = -1f
    @Volatile private var pendingTapY = -1f

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val anchorMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1); GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        session.setCameraTextureName(cameraTextureId)

        bgProgram = buildProgram(BG_VERT, BG_FRAG)
        objProgram = buildProgram(OBJ_VERT, OBJ_FRAG)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session.setDisplayGeometry(Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame = try { session.update() } catch (e: Exception) { return }

        // 1. Draw Camera Feed
        drawBackground(frame)

        // 2. Handle Taps
        if (pendingTapX >= 0) {
            val hits = frame.hitTest(pendingTapX, pendingTapY)
            pendingTapX = -1f
            for (hit in hits) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                    anchors.add(hit.createAnchor())
                    break
                }
            }
        }

        // 3. Draw Placed Objects (Simple Diamond)
        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
        
        GLES20.glUseProgram(objProgram)
        for (anchor in anchors) {
            if (anchor.trackingState != TrackingState.TRACKING) continue
            anchor.pose.toMatrix(anchorMatrix, 0)
            val mvp = FloatArray(16)
            val mv = FloatArray(16)
            Matrix.multiplyMM(mv, 0, viewMatrix, 0, anchorMatrix, 0)
            Matrix.multiplyMM(mvp, 0, projMatrix, 0, mv, 0)
            
            val uMvp = GLES20.glGetUniformLocation(objProgram, "u_MVP")
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            drawDiamond()
        }

        val planeCount = session.getAllTrackables(Plane::class.java).count { it.trackingState == TrackingState.TRACKING }
        onUpdate(planeCount, anchors.size)
    }

    private fun drawBackground(frame: com.google.ar.core.Frame) {
        val quad = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
        val tex = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, tex)
        
        GLES20.glUseProgram(bgProgram)
        val pos = GLES20.glGetAttribLocation(bgProgram, "a_Pos")
        val txc = GLES20.glGetAttribLocation(bgProgram, "a_Tex")
        
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(quad))
        GLES20.glVertexAttribPointer(txc, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(tex))
        GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(txc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawDiamond() {
        val diamond = floatArrayOf(
            0f,0.2f,0f,  -0.1f,0f,0.1f,  0.1f,0f,0.1f,
            0f,0.2f,0f,   0.1f,0f,0.1f,  0.1f,0f,-0.1f,
            0f,0.2f,0f,   0.1f,0f,-0.1f, -0.1f,0f,-0.1f,
            0f,0.2f,0f,  -0.1f,0f,-0.1f, -0.1f,0f,0.1f
        )
        val pos = GLES20.glGetAttribLocation(objProgram, "a_Pos")
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, floatBuffer(diamond))
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 12)
    }

    fun onTap(x: Float, y: Float) { pendingTapX = x; pendingTapY = y }

    private fun buildProgram(v: String, f: String): Int {
        val vp = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { GLES20.glShaderSource(it, v); GLES20.glCompileShader(it) }
        val fp = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { GLES20.glShaderSource(it, f); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vp); GLES20.glAttachShader(it, fp); GLES20.glLinkProgram(it) }
    }

    private fun floatBuffer(data: FloatArray) = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

    private val BG_VERT = "attribute vec4 a_Pos; attribute vec2 a_Tex; varying vec2 v_Tex; void main() { gl_Position = a_Pos; v_Tex = a_Tex; }"
    private val BG_FRAG = "#extension GL_OES_EGL_image_external : require\n precision mediump float; uniform samplerExternalOES s_Tex; varying vec2 v_Tex; void main() { gl_FragColor = texture2D(s_Tex, v_Tex); }"
    private val OBJ_VERT = "uniform mat4 u_MVP; attribute vec4 a_Pos; void main() { gl_Position = u_MVP * a_Pos; }"
    private val OBJ_FRAG = "precision mediump float; void main() { gl_FragColor = vec4(0.0, 0.8, 1.0, 1.0); }"
}
