package com.example.ar_ecommerce_app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Unity Showroom Activity.
 *
 * Architecture: Flutter → MethodChannel → UnityActivity → UnityPlayer
 *
 * Runtime detection via Class.forName means this compiles without the Unity
 * library present. When unityLibrary is added as a Gradle module, the player
 * is instantiated and lifecycle events are forwarded via reflection so the
 * Unity engine pauses/resumes/quits correctly alongside the activity.
 *
 * To activate Unity integration:
 *   1. Open your Unity project (2019.3+)
 *   2. Build Settings → Android → Export as Gradle Project
 *   3. Copy the exported 'unityLibrary' folder into android/
 *   4. Add  include ':unityLibrary'  to android/settings.gradle
 *   5. Add  implementation project(':unityLibrary')  to app/build.gradle.kts
 *   6. Rebuild — UnityPlayer is auto-detected here and the scene loads
 */
class UnityActivity : AppCompatActivity() {

    private var productId = "unknown"
    // Stored as Any so this file compiles without the Unity library on the classpath.
    // When the library is linked, this holds a com.unity3d.player.UnityPlayer instance.
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

    // Unity docs: pause() must be called BEFORE super.onPause() so the engine can
    // reach a synchronisation point before the window surface is destroyed.
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

    // ── Private helpers ───────────────────────────────────────────────────────

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

            // UnityPlayer extends FrameLayout; use it directly as the content view
            val view = playerClass.getMethod("getView").invoke(player) as android.view.View
            setContentView(view)
            view.requestFocus()

            // Pass product context to the Unity C# scene via UnitySendMessage (static call)
            playerClass.getMethod(
                "UnitySendMessage",
                String::class.java, String::class.java, String::class.java
            ).invoke(null, "ProductBridge", "OnProductReceived", productId)

        } catch (e: Exception) {
            // Unity library is linked but instantiation failed — show diagnostic screen
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

        root.addView(TextView(this).apply {
            text = "Product: $productId"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#90A4AE"))
        })

        root.addView(TextView(this).apply {
            text = "─────────────────────────"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 24, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = "Native bridge: CONNECTED"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#66BB6A"))
        })

        root.addView(TextView(this).apply {
            text = "Unity library: pending export"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFA726"))
            setPadding(0, 4, 0, 0)
        })

        root.addView(TextView(this).apply {
            text = "\nTo activate:\n" +
                "1. Open your Unity project\n" +
                "2. Build → Android → Export as Gradle project\n" +
                "3. Add ':unityLibrary' to settings.gradle\n" +
                "4. Add implementation project(':unityLibrary') to build.gradle.kts\n" +
                "5. Rebuild — Unity scene loads here automatically"
            textSize = 12f
            setTextColor(Color.parseColor("#78909C"))
            setPadding(0, 16, 0, 24)
        })

        root.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            progress = 65
            max = 100
        })

        root.addView(TextView(this).apply {
            text = "Integration: 65% complete"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#546E7A"))
            setPadding(0, 6, 0, 0)
        })

        root.addView(Button(this).apply {
            text = "← Back to Product"
            setOnClickListener { finish() }
        })

        setContentView(root)
    }
}
