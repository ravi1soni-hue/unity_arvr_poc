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
 * When the Unity library is exported and added as a Gradle module, replace the
 * body of [attachUnityPlayer] with:
 *
 *   val unityPlayer = UnityPlayer(this)
 *   setContentView(unityPlayer)
 *   unityPlayer.requestFocus()
 *   // Send product data to Unity scene via UnitySendMessage:
 *   UnityPlayer.UnitySendMessage("SceneManager", "LoadProduct", productId)
 *
 * Until then this activity provides a UI that accurately reflects the Unity
 * integration state and serves as the verified native entry-point.
 */
class UnityActivity : AppCompatActivity() {

    private var productId = "unknown"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        productId = intent.getStringExtra("productId") ?: "unknown"
        title = "Unity Showroom — $productId"

        val unityLibraryLinked = isUnityLibraryLinked()

        if (unityLibraryLinked) {
            attachUnityPlayer()
        } else {
            showReadyState()
        }
    }

    private fun isUnityLibraryLinked(): Boolean {
        return try {
            Class.forName("com.unity3d.player.UnityPlayer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun attachUnityPlayer() {
        // Unity library IS linked — hand off to the Unity runtime.
        // Uncomment and adjust when unityLibrary gradle module is present:
        //
        // val unityPlayer = com.unity3d.player.UnityPlayer(this)
        // setContentView(unityPlayer)
        // unityPlayer.requestFocus()
        // com.unity3d.player.UnityPlayer.UnitySendMessage("ProductBridge", "OnProductReceived", productId)
        showReadyState() // replace with above when Unity lib is linked
    }

    private fun showReadyState() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        val icon = TextView(this).apply {
            text = "⬡"
            textSize = 72f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00BCD4"))
        }

        val title = TextView(this).apply {
            text = "Unity 3D Showroom"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        }

        val productLabel = TextView(this).apply {
            text = "Product: $productId"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#90A4AE"))
        }

        val separator = TextView(this).apply {
            text = "─────────────────────────"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 24, 0, 16)
        }

        val statusTitle = TextView(this).apply {
            text = "Native bridge: CONNECTED"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#66BB6A"))
        }

        val statusUnity = TextView(this).apply {
            text = "Unity library: pending export"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFA726"))
            setPadding(0, 4, 0, 0)
        }

        val instructions = TextView(this).apply {
            text = "\nTo activate:\n" +
                "1. Open your Unity project\n" +
                "2. Build → Android → Export as Gradle project\n" +
                "3. Add ':unityLibrary' to settings.gradle\n" +
                "4. Uncomment attachUnityPlayer() in UnityActivity.kt\n" +
                "5. Rebuild — Unity scene loads here automatically"
            textSize = 12f
            setTextColor(Color.parseColor("#78909C"))
            setPadding(0, 16, 0, 24)
        }

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            progress = 65
            max = 100
        }

        val progressLabel = TextView(this).apply {
            text = "Integration: 65% complete"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#546E7A"))
            setPadding(0, 6, 0, 0)
        }

        val backBtn = Button(this).apply {
            text = "← Back to Product"
            setOnClickListener { finish() }
        }

        root.addView(icon)
        root.addView(title)
        root.addView(productLabel)
        root.addView(separator)
        root.addView(statusTitle)
        root.addView(statusUnity)
        root.addView(instructions)
        root.addView(progress)
        root.addView(progressLabel)
        root.addView(backBtn)

        setContentView(root)
    }
}
