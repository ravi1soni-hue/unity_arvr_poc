package com.example.ar_ecommerce_app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unity Showroom Activity.
 */
class UnityActivity : AppCompatActivity() {

    private var productId = "unknown"
    private var unityPlayer: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        productId = intent.getStringExtra("productId") ?: "unknown"
        title = "Unity Showroom — $productId"

        if (isUnityLibraryLinked()) {
            attachUnityPlayer()
        } else {
            showReadyState()
        }
    }

    private fun sendMarkedDataToFlutter() {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        
        MainActivity.bridgeChannel?.invokeMethod("onNativeMessage", mapOf(
            "message" to "Unity Product $productId marked at $timestamp",
            "productId" to productId,
            "markedData" to mapOf(
                "action" to "UNITY_ITEM_MARKED",
                "timestamp" to timestamp,
                "engine" to "UNITY"
            )
        ))
        
        Toast.makeText(this, "Unity Data sent back!", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        invokeUnityLifecycle("pause")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        invokeUnityLifecycle("resume")
    }

    override fun onDestroy() {
        invokeUnityLifecycle("quit")
        unityPlayer = null
        super.onDestroy()
    }

    private fun isUnityLibraryLinked(): Boolean {
        return try { Class.forName("com.unity3d.player.UnityPlayer"); true }
        catch (_: ClassNotFoundException) { false }
    }

    private fun attachUnityPlayer() {
        try {
            val playerClass = Class.forName("com.unity3d.player.UnityPlayer")
            val player = playerClass
                .getConstructor(android.app.Activity::class.java)
                .newInstance(this)
            unityPlayer = player

            val unityView = playerClass.getMethod("getView").invoke(player) as View
            
            // Create a layout to hold Unity view + a native button overlay
            val container = FrameLayout(this)
            container.addView(unityView)
            
            val markBtn = Button(this).apply {
                text = "Mark in Unity"
                setOnClickListener { sendMarkedDataToFlutter() }
            }
            val btnParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, 50, 50, 0) }
            
            container.addView(markBtn, btnParams)
            setContentView(container)
            
            unityView.requestFocus()

            playerClass.getMethod(
                "UnitySendMessage",
                String::class.java, String::class.java, String::class.java
            ).invoke(null, "ProductBridge", "OnProductReceived", productId)

        } catch (e: Exception) {
            unityPlayer = null
            showReadyState()
        }
    }

    private fun invokeUnityLifecycle(method: String) {
        try {
            unityPlayer?.let { p -> p.javaClass.getMethod(method).invoke(p) }
        } catch (_: Exception) {}
    }

    private fun showReadyState() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        root.addView(TextView(this).apply {
            text = "⬡"
            textSize = 72f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00BCD4"))
        })

        root.addView(TextView(this).apply {
            text = "Unity 3D Showroom"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        })

        root.addView(Button(this).apply {
            text = "Test Marking (No Unity)"
            setOnClickListener { sendMarkedDataToFlutter() }
        })

        root.addView(TextView(this).apply {
            text = "Integration: 65% complete"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#546E7A"))
            setPadding(0, 30, 0, 0)
        })

        root.addView(Button(this).apply {
            text = "← Back to Product"
            setOnClickListener { finish() }
        })

        setContentView(root)
    }
}
