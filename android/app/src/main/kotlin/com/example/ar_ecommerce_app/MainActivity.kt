package com.example.ar_ecommerce_app

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.example.ar_ecommerce/native"

    companion object {
        var bridgeChannel: MethodChannel? = null
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        bridgeChannel = channel

        channel.setMethodCallHandler { call, result ->
            val productId = call.argument<String>("productId") ?: "unknown"
            when (call.method) {
                "pingNative" -> {
                    result.success(mapOf(
                        "message" to "Android native bridge OK — product: $productId",
                        "source" to "android",
                        "productId" to productId,
                    ))
                }

                "openArScreen" -> {
                    startActivity(Intent(this, ArActivity::class.java)
                        .putExtra("productId", productId))
                    channel.invokeMethod("onNativeMessage", mapOf(
                        "message" to "ARCore session started for $productId",
                        "productId" to productId,
                    ))
                    result.success(true)
                }

                "openVrScreen" -> {
                    startActivity(Intent(this, VrActivity::class.java)
                        .putExtra("productId", productId))
                    channel.invokeMethod("onNativeMessage", mapOf(
                        "message" to "VR 360° showroom opened for $productId",
                        "productId" to productId,
                    ))
                    result.success(true)
                }

                "openUnityScene" -> {
                    startActivity(Intent(this, UnityActivity::class.java)
                        .putExtra("productId", productId))
                    channel.invokeMethod("onNativeMessage", mapOf(
                        "message" to "Unity showroom launched for $productId",
                        "productId" to productId,
                    ))
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridgeChannel = null
    }
}
