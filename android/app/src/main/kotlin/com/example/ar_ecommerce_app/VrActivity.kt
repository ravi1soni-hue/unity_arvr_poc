package com.example.ar_ecommerce_app

import android.content.Context
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
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VrActivity : AppCompatActivity(), SensorEventListener {

    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: VrSphereRenderer? = null
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var productId = "unknown"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        productId = intent.getStringExtra("productId") ?: "unknown"
        
        val root = RelativeLayout(this)
        
        renderer = VrSphereRenderer()
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
        }

        val markButton = Button(this).apply {
            text = "Mark VR View"
            setOnClickListener {
                sendMarkedDataToFlutter()
            }
        }

        val fullParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        val buttonParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            setMargins(0, 50, 50, 0)
        }

        root.addView(glSurfaceView!!, fullParams)
        root.addView(markButton, buttonParams)
        setContentView(root)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                renderer?.applyTouch(dx, dy); return true
            }
        })
        glSurfaceView?.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    private fun sendMarkedDataToFlutter() {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        
        MainActivity.bridgeChannel?.invokeMethod("onNativeMessage", mapOf(
            "message" to "Product $productId marked in VR at $timestamp",
            "productId" to productId,
            "markedData" to mapOf(
                "action" to "ITEM_MARKED_VR_ANDROID",
                "timestamp" to timestamp,
                "view" to "360_PANORAMA"
            )
        ))
        
        Toast.makeText(this, "VR Data sent to Flutter", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        glSurfaceView?.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rot = FloatArray(16); val remap = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(rot, event.values)
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
            renderer?.setRotation(remap)
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

class VrSphereRenderer : GLSurfaceView.Renderer {
    private var prog = -1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    @Volatile private var sensorRot: FloatArray? = null
    private var touchX = 0f; private var touchY = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0.2f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        prog = buildProgram(VERT, FRAG)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        Matrix.perspectiveM(proj, 0, 90f, w.toFloat()/h, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setIdentityM(view, 0)
        sensorRot?.let { Matrix.transposeM(view, 0, it, 0) }
        Matrix.rotateM(view, 0, touchY, 1f, 0f, 0f)
        Matrix.rotateM(view, 0, touchX, 0f, 1f, 0f)

        GLES20.glUseProgram(prog)
        val uMvp = GLES20.glGetUniformLocation(prog, "u_MVP")
        
        // 1. Draw "Room" (Grid)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        drawGrid()

        // 2. Draw "Product" (Cube)
        val model = FloatArray(16); Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, -5f) // Place in front
        val mvpObj = FloatArray(16); val mv = FloatArray(16)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvpObj, 0, proj, 0, mv, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpObj, 0)
        drawCube()
    }

    private fun drawGrid() {
        val lines = mutableListOf<Float>()
        for (i in -10..10) {
            lines.addAll(listOf(i.toFloat(), -2f, -10f, i.toFloat(), -2f, 10f))
            lines.addAll(listOf(-10f, -2f, i.toFloat(), 10f, -2f, i.toFloat()))
        }
        val pos = GLES20.glGetAttribLocation(prog, "a_Pos")
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, floatBuffer(lines.toFloatArray()))
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lines.size / 3)
    }

    private fun drawCube() {
        val cube = floatArrayOf(-1f,-1f,1f, 1f,-1f,1f, 1f,1f,1f, -1f,1f,1f, -1f,-1f,-1f, 1f,-1f,-1f, 1f,1f,-1f, -1f,1f,-1f)
        val indices = shortArrayOf(0,1,2, 2,3,0, 1,5,6, 6,2,1, 7,6,5, 5,4,7, 4,0,3, 3,7,4, 3,2,6, 6,7,3, 4,5,1, 1,0,4)
        val pos = GLES20.glGetAttribLocation(prog, "a_Pos")
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, floatBuffer(cube))
        GLES20.glEnableVertexAttribArray(pos)
        val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, ib)
    }

    fun applyTouch(dx: Float, dy: Float) { touchX -= dx * 0.2f; touchY -= dy * 0.2f }
    fun setRotation(m: FloatArray) { sensorRot = m }
    private fun floatBuffer(d: FloatArray) = ByteBuffer.allocateDirect(d.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(d); position(0) }
    private fun buildProgram(v: String, f: String): Int {
        val vp = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { GLES20.glShaderSource(it, v); GLES20.glCompileShader(it) }
        val fp = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { GLES20.glShaderSource(it, f); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vp); GLES20.glAttachShader(it, fp); GLES20.glLinkProgram(it) }
    }

    private val VERT = "uniform mat4 u_MVP; attribute vec4 a_Pos; void main() { gl_Position = u_MVP * a_Pos; }"
    private val FRAG = "precision mediump float; void main() { gl_FragColor = vec4(1.0, 0.5, 0.0, 1.0); }"
}
