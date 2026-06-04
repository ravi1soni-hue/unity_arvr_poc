# AR / VR / Unity Integration Guide
### FloorFlow AR — Flutter + Native Bridge

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Flutter ↔ Native Bridge (MethodChannel)](#2-flutter--native-bridge-methodchannel)
3. [AR Integration — ARCore (Android) & ARKit (iOS)](#3-ar-integration)
4. [VR Integration — 360° Panorama](#4-vr-integration)
5. [Unity Integration — Unity as a Library](#5-unity-integration)
6. [AndroidManifest & Permissions](#6-androidmanifest--permissions)
7. [iOS Info.plist & Permissions](#7-ios-infoplist--permissions)
8. [Dependency Summary](#8-dependency-summary)
9. [How to Complete Unity Integration](#9-how-to-complete-unity-integration)

---

## 1. Architecture Overview

```
Flutter UI (Dart)
     │
     │  MethodChannel("com.example.ar_ecommerce/native")
     │
     ▼
Android: MainActivity.kt          iOS: AppDelegate.swift
     │                                   │
     ├──► ArActivity.kt                  ├──► ARViewController.swift
     │     ARCore Session                │     ARKit ARSCNView
     │     OpenGL ES 2.0 camera          │     SCNBox tap-to-place
     │
     ├──► VrActivity.kt                  ├──► VRViewController.swift
     │     Canvas + Choreographer        │     SceneKit inside-out sphere
     │     Gyroscope / swipe pan         │     CMMotionManager gyroscope
     │
     └──► UnityActivity.kt               └──► UnityViewController.swift
           Unity as Library bridge             Unity as Library bridge
           (awaiting Unity export)             (awaiting Unity export)
```

**Key design rule:** Flutter only handles the ecommerce UI (products, cart, checkout).
All AR, VR, and Unity code lives entirely in native Android/iOS. Flutter never loads a
plugin for these — it only sends a string command through the MethodChannel.

---

## 2. Flutter ↔ Native Bridge (MethodChannel)

### What is a MethodChannel?

A MethodChannel is Flutter's built-in IPC mechanism. It sends a named method call
(with optional arguments as a Map) from Dart to native code and receives a result back.
The channel is identified by a string name that must match exactly on both sides.

**Channel name used:** `com.example.ar_ecommerce/native`

---

### 2.1 Flutter Side — `lib/services/native_bridge.dart`

```dart
import 'package:flutter/services.dart';

class NativeBridge {
  static const MethodChannel _channel = MethodChannel(
    'com.example.ar_ecommerce/native',
  );

  static Future<void> openArScreen(String productId) async {
    await _channel.invokeMethod('openArScreen', {'productId': productId});
  }

  static Future<void> openVrScreen(String productId) async {
    await _channel.invokeMethod('openVrScreen', {'productId': productId});
  }

  static Future<void> openUnityScene(String productId) async {
    await _channel.invokeMethod('openUnityScene', {'productId': productId});
  }
}
```

**How it is called** (from `lib/screens/product_detail_screen.dart`):

```dart
ElevatedButton(
  onPressed: () => NativeBridge.openArScreen(product.id),
  child: Text('View in AR'),
),
ElevatedButton(
  onPressed: () => NativeBridge.openVrScreen(product.id),
  child: Text('VR 360°'),
),
ElevatedButton(
  onPressed: () => NativeBridge.openUnityScene(product.id),
  child: Text('Unity Showroom'),
),
```

---

### 2.2 Android Side — `MainActivity.kt`

```kotlin
class MainActivity : FlutterActivity() {
    private val channelName = "com.example.ar_ecommerce/native"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)

        channel.setMethodCallHandler { call, result ->
            val productId = call.argument<String>("productId") ?: "unknown"
            when (call.method) {
                "openArScreen" -> {
                    startActivity(Intent(this, ArActivity::class.java)
                        .putExtra("productId", productId))
                    result.success(true)
                }
                "openVrScreen" -> {
                    startActivity(Intent(this, VrActivity::class.java)
                        .putExtra("productId", productId))
                    result.success(true)
                }
                "openUnityScene" -> {
                    startActivity(Intent(this, UnityActivity::class.java)
                        .putExtra("productId", productId))
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }
}
```

**How it works:**
- `configureFlutterEngine` is the Flutter embedding hook that fires when the engine starts.
- `MethodChannel` is registered on the `binaryMessenger` (the IPC pipe from Flutter engine).
- When Flutter calls `invokeMethod("openArScreen", {...})`, the `when` block fires.
- `startActivity(Intent(...))` launches the native Activity, passing `productId` as an Intent extra.
- `result.success(true)` sends the return value back to Flutter's `await`.

---

### 2.3 iOS Side — `AppDelegate.swift`

```swift
@main
class AppDelegate: FlutterAppDelegate {
    private let channelName = "com.example.ar_ecommerce/native"

    override func application(_ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        let controller = window?.rootViewController as! FlutterViewController
        let channel = FlutterMethodChannel(name: channelName,
                                           binaryMessenger: controller.binaryMessenger)

        channel.setMethodCallHandler { [weak self] call, result in
            let args = call.arguments as? [String: Any]
            let productId = args?["productId"] as? String ?? "unknown"

            switch call.method {
            case "openArScreen":
                self?.present(ARViewController(productId: productId), from: controller)
                result(true)
            case "openVrScreen":
                self?.present(VRViewController(productId: productId), from: controller)
                result(true)
            case "openUnityScene":
                self?.present(UnityViewController(productId: productId), from: controller)
                result(true)
            default:
                result(FlutterMethodNotImplemented)
            }
        }
        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }

    private func present(_ vc: UIViewController, from controller: FlutterViewController) {
        let nav = UINavigationController(rootViewController: vc)
        nav.modalPresentationStyle = .fullScreen
        controller.present(nav, animated: true)
    }
}
```

**How it works:**
- `FlutterMethodChannel` is created on the `binaryMessenger` of the root `FlutterViewController`.
- The handler switch receives the method name and extracts `productId` from the args dictionary.
- Each case instantiates the relevant Swift view controller and presents it full-screen with a
  `UINavigationController` wrapper (gives a back button automatically).

---

## 3. AR Integration

### What is ARCore / ARKit?

- **ARCore** (Android, Google): SDK that provides motion tracking, plane detection, and light
  estimation using the device camera and IMU sensors.
- **ARKit** (iOS, Apple): Apple's equivalent. Uses LiDAR on newer devices for faster plane detection.

Both are "world-tracking" AR frameworks — they map the real world in 3D and let you anchor
virtual objects to physical surfaces.

---

### 3.1 Android — ARCore

#### Dependency

File: `android/app/build.gradle.kts`

```kotlin
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.ar:core:1.44.0")   // ARCore SDK
}

android {
    defaultConfig {
        minSdk = 24    // ARCore requires minimum Android 7.0
    }
}
```

`com.google.ar:core:1.44.0` pulls in:
- `ArCoreApk` — checks device compatibility and installs the AR service
- `Session` — manages the camera + tracking lifecycle
- `Frame` — per-frame snapshot of camera image + tracking data
- `Plane` — detected flat surface (floor, table, wall)
- `HitResult` / `Anchor` — a point on a plane where you can attach a 3D object
- `Config` — configure plane detection mode, update mode, light estimation

#### How ARCore is initialised — `ArActivity.kt`

**Step 1 — Check device compatibility:**

```kotlin
when (ArCoreApk.getInstance().checkAvailability(this)) {
    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
        showFallback("ARCore not supported on this device.")
        return
    }
    else -> setupSession()
}
```

`checkAvailability` returns an enum: `SUPPORTED_INSTALLED`, `SUPPORTED_NOT_INSTALLED`,
`UNSUPPORTED_DEVICE_NOT_CAPABLE`, etc. On emulator it returns `UNSUPPORTED`, so the
fallback message shows immediately.

**Step 2 — Create a Session:**

```kotlin
arSession = Session(this)
val config = Config(arSession!!).apply {
    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
}
arSession!!.configure(config)
```

A `Session` owns the camera + tracking pipeline. `Config` sets:
- `HORIZONTAL_AND_VERTICAL` — detect both floors and walls
- `LATEST_CAMERA_IMAGE` — always use the most recent frame
- `ENVIRONMENTAL_HDR` — estimate real-world lighting to shade AR objects realistically

**Step 3 — Connect to OpenGL via GLSurfaceView:**

```kotlin
val glSurfaceView = GLSurfaceView(this).apply {
    setEGLContextClientVersion(2)          // OpenGL ES 2.0
    preserveEGLContextOnPause = true       // keep GL context across pause/resume
}
glSurfaceView.setRenderer(ArCoreRenderer(arSession!!, productId) { planes, anchors ->
    runOnUiThread { updateStatusText(planes, anchors) }
})
glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
```

`GLSurfaceView` provides a dedicated OpenGL surface. ARCore writes each camera frame into an
OES (external) texture, and the renderer reads that texture to draw the camera background.

**Step 4 — OpenGL Renderer (`ArCoreRenderer`):**

```kotlin
class ArCoreRenderer(session, productId, onUpdate) : GLSurfaceView.Renderer {

    override fun onSurfaceCreated(gl, config) {
        // Generate an OES texture for the camera feed
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        session.setCameraTextureName(cameraTextureId)  // tell ARCore to write here

        // Compile camera background shader program
        bgProgram = buildProgram(vertexShaderSrc, fragmentShaderSrc)
    }

    override fun onDrawFrame(gl) {
        val frame = session.update()      // advance ARCore, get latest camera frame
        drawCameraBackground()            // render the camera image to the full screen quad
        handlePendingTap(frame)           // if user tapped, do a hit test
        updatePlaneCount(frame)           // count detected planes, report to UI
    }
}
```

The fragment shader uses `GL_OES_EGL_image_external` — a GLSL extension for sampling
hardware-decoded camera textures (not a regular 2D texture):

```glsl
#extension GL_OES_EGL_image_external : require
uniform samplerExternalOES u_Texture;
void main() {
    gl_FragColor = texture2D(u_Texture, v_TexCoord);
}
```

**Step 5 — Tap to place a product anchor:**

```kotlin
glSurfaceView.setOnTouchListener { _, event ->
    if (event.action == MotionEvent.ACTION_UP) {
        renderer?.onTap(event.x, event.y)  // pass tap coordinates to renderer
    }
    true
}

// Inside ArCoreRenderer.onDrawFrame:
val hits = frame.hitTest(tapX, tapY)
for (hit in hits) {
    val trackable = hit.trackable
    if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
        anchors.add(hit.createAnchor())  // lock virtual object to real-world point
        break
    }
}
```

`frame.hitTest` casts a ray from the screen touch point into the 3D world and returns
intersection points with detected planes. `createAnchor()` creates a pose that stays locked
to the real-world position even as the device moves.

**Step 6 — Session lifecycle:**

```kotlin
override fun onResume() {
    arSession?.resume()         // restart camera + tracking
    glSurfaceView?.onResume()   // resume GL rendering
}
override fun onPause() {
    glSurfaceView?.onPause()    // pause GL thread first (important ordering)
    arSession?.pause()          // then pause camera
}
override fun onDestroy() {
    arSession?.close()          // release GPU + camera resources
}
```

> **Null-safety note:** `glSurfaceView` is declared `var` (nullable). On emulator,
> `showFallback()` sets it to `null` before any lifecycle calls fire. `?.onPause()` then
> becomes a safe no-op — preventing the `GLSurfaceView$GLThread` NPE that Android's
> SwiftShader emulator triggers when `mGLThread` is null.

---

### 3.2 iOS — ARKit

#### Dependency

No external dependency needed. ARKit is part of the iOS SDK.

Add to `ios/Runner/Runner.xcodeproj`:
- Link `ARKit.framework`
- Link `SceneKit.framework`

Both are system frameworks — no CocoaPods or Swift Package needed.

#### How ARKit is set up — `ARViewController.swift`

**Step 1 — Check availability:**

```swift
if ARWorldTrackingConfiguration.isSupported {
    setupARView()
} else {
    showFallback()   // simulator or non-ARKit device
}
```

**Step 2 — Create ARSCNView (SceneKit-backed AR view):**

```swift
sceneView = ARSCNView(frame: view.bounds)
sceneView.delegate = self       // ARSCNViewDelegate for plane/node events
sceneView.session.delegate = self
sceneView.autoenablesDefaultLighting = true
```

`ARSCNView` combines ARKit world-tracking with a SceneKit renderer. No OpenGL boilerplate needed
on iOS — SceneKit handles the render loop.

**Step 3 — Start tracking:**

```swift
let config = ARWorldTrackingConfiguration()
config.planeDetection = [.horizontal, .vertical]
config.environmentTexturing = .automatic
sceneView.session.run(config, options: [.resetTracking, .removeExistingAnchors])
```

**Step 4 — Visualise detected planes:**

```swift
func renderer(_ renderer: SCNSceneRenderer, didAdd node: SCNNode, for anchor: ARAnchor) {
    guard let planeAnchor = anchor as? ARPlaneAnchor else { return }
    let plane = SCNPlane(width: CGFloat(planeAnchor.planeExtent.width),
                         height: CGFloat(planeAnchor.planeExtent.height))
    plane.firstMaterial?.diffuse.contents = UIColor.systemBlue.withAlphaComponent(0.3)
    let planeNode = SCNNode(geometry: plane)
    planeNode.eulerAngles.x = -.pi / 2   // rotate flat
    node.addChildNode(planeNode)
}
```

**Step 5 — Tap to place product:**

```swift
@objc func handleTap(_ recognizer: UITapGestureRecognizer) {
    let location = recognizer.location(in: sceneView)
    let results = sceneView.raycastQuery(from: location, allowing: .estimatedPlane, alignment: .any)
        .flatMap { sceneView.session.raycast($0) }
    guard let first = results.first else { return }

    let box = SCNBox(width: 0.15, height: 0.4, length: 0.15, chamferRadius: 0.01)
    box.firstMaterial?.diffuse.contents = UIColor(red: 0.9, green: 0.5, blue: 0.1, alpha: 1)
    let node = SCNNode(geometry: box)
    node.simdTransform = first.worldTransform
    node.position.y += 0.2
    sceneView.scene.rootNode.addChildNode(node)
}
```

`raycastQuery` + `session.raycast` replaces the deprecated `hitTest(_:types:)` API (deprecated
in iOS 14). It casts a ray from screen coordinates into 3D world space and returns intersection
points with estimated planes.

---

## 4. VR Integration

### What is 360° VR here?

A 360° panorama viewer where the user can look around a simulated room. On real devices,
the device gyroscope drives the camera rotation (move the phone = look around). On emulator,
swipe gestures replace gyroscope.

**No headset required** — this is "Magic Window" VR (single screen, no side-by-side stereo split).

---

### 4.1 Android — Canvas + Choreographer

**Why not OpenGL?**

The original implementation used `GLSurfaceView`. On Android emulators using SwiftShader
(software OpenGL), `GLSurfaceView.mGLThread` (an internal Android field) is left null even
after `setRenderer()` is called — EGL context initialisation fails silently. When the activity
pauses, `glSurfaceView.onPause()` calls `mGLThread.onPause()` which crashes with NPE.

The solid fix: remove `GLSurfaceView` entirely. A Canvas-based View has no GL thread,
no EGL context, and no lifecycle fragility.

#### No extra dependency needed

Canvas, Bitmap, Paint, Matrix — all standard `android.graphics.*` from the Android SDK.
`Choreographer` — standard `android.view.Choreographer` from the Android SDK.

#### Architecture — `VrActivity.kt`

**Component 1 — `VrPanoramaView` (custom View):**

```kotlin
class VrPanoramaView(context: Context) : View(context) {
    private var panorama: Bitmap? = null    // the panorama image (built once)
    private val drawMatrix = Matrix()        // transform applied each frame
    private var yaw = 0f                    // horizontal rotation (degrees)
    private var pitch = 0f                  // vertical rotation (degrees)
}
```

**Component 2 — Panorama bitmap generation:**

```kotlin
private fun buildPanorama(w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Sky gradient — top 40%
    paint.shader = LinearGradient(0f, 0f, 0f, skyH.toFloat(),
        intArrayOf(0xFF0D1B4B.toInt(), 0xFF1A3A6B.toInt(), 0xFF2E6DA4.toInt()),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), skyH.toFloat(), paint)

    // Horizon / wall band — middle 35%
    // Floor — bottom 25%
    // Vertical room dividers every 90° (depth cues)
    // Horizontal floor grid lines

    return bmp
}
```

The bitmap is `width × 4` pixels wide (four times the screen width). One full 360° sweep of
yaw maps to scrolling across the full bitmap width once.

**Component 3 — Render loop via Choreographer:**

```kotlin
private val frameCallback = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
        invalidate()   // trigger onDraw on the UI thread
        if (rendering) Choreographer.getInstance().postFrameCallback(this)
    }
}

fun startRendering() {
    rendering = true
    Choreographer.getInstance().postFrameCallback(frameCallback)
}
```

`Choreographer.postFrameCallback` fires the callback on the next VSYNC signal — same
mechanism Android's own View hierarchy uses to animate. This gives 60fps rendering with zero
threading complexity. No GL thread, no handler, no runOnUiThread.

**Component 4 — Scrolling the panorama in `onDraw`:**

```kotlin
override fun onDraw(canvas: Canvas) {
    val bmp = panorama ?: return
    val normalizedYaw = ((yaw % 360f) + 360f) % 360f
    val xOffset = -(normalizedYaw / 360f) * bmpW   // horizontal scroll from yaw

    val scale = height.toFloat() / bmpH             // scale bitmap to screen height
    drawMatrix.setScale(scale, scale)
    drawMatrix.postTranslate(xOffset * scale, -yOffset * scale)
    canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

    // Wrap-around: draw second copy if the bitmap scrolls off screen edge
    if (xOffset * scale + scaledW < width) {
        val wrapMatrix = Matrix(drawMatrix)
        wrapMatrix.postTranslate(scaledW, 0f)
        canvas.drawBitmap(bmp, wrapMatrix, bitmapPaint)
    }
}
```

**Component 5 — Gyroscope head tracking:**

```kotlin
class VrActivity : AppCompatActivity(), SensorEventListener {
    private val rotMatrix = FloatArray(16)

    override fun onSensorChanged(event: SensorEvent?) {
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        SensorManager.remapCoordinateSystem(rotMatrix,
            SensorManager.AXIS_X, SensorManager.AXIS_Z, remapMatrix)
        SensorManager.getOrientation(remapMatrix, orientValues)
        sensorYaw   = Math.toDegrees(orientValues[0].toDouble()).toFloat()
        sensorPitch = Math.toDegrees(orientValues[1].toDouble()).toFloat()
        panoramaView.setHeadRotation(sensorYaw, sensorPitch)
    }
}
```

`TYPE_ROTATION_VECTOR` fuses accelerometer + gyroscope + magnetometer into a stable
rotation vector. `getRotationMatrixFromVector` converts it to a 4×4 rotation matrix.
`remapCoordinateSystem` adapts the coordinate axes for portrait orientation. `getOrientation`
extracts yaw and pitch in radians.

**Component 6 — Touch pan fallback (emulator):**

```kotlin
private val gestureDetector = GestureDetector(context,
    object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                              distanceX: Float, distanceY: Float): Boolean {
            if (!usingSensor) {
                touchYaw  -= distanceX * 0.3f
                touchPitch = (touchPitch + distanceY * 0.2f).coerceIn(-60f, 60f)
                yaw = touchYaw; pitch = touchPitch
            }
            return true
        }
    })
```

If no rotation sensor is available (emulator), touch pan takes over. `usingSensor` flag
ensures sensor input always wins when on a real device.

---

### 4.2 iOS — SceneKit inside-out sphere

#### Dependency

`SceneKit.framework` and `CoreMotion.framework` — both system frameworks, no external packages.

#### Architecture — `VRViewController.swift`

**Step 1 — Create an inside-out sphere:**

```swift
let sphere = SCNSphere(radius: 10)
sphere.firstMaterial = makeGradientMaterial()
let sphereNode = SCNNode(geometry: sphere)
sphereNode.scale = SCNVector3(-1, 1, 1)   // negative X scale flips face normals inward
```

Normally a sphere's faces point outward. Flipping the X scale makes the normals face inward,
so the camera (placed at the sphere's centre) sees the interior surface — the classic 360°
panorama trick.

**Step 2 — Place camera at origin:**

```swift
cameraNode = SCNNode()
cameraNode.camera = SCNCamera()
cameraNode.camera?.fieldOfView = 90
scene.rootNode.addChildNode(cameraNode)
sceneView.pointOfView = cameraNode
```

**Step 3 — Gyroscope rotation via CMMotionManager:**

```swift
motionManager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { motion, _ in
    let attitude = motion.attitude
    self.cameraNode.eulerAngles = SCNVector3(
        Float(-attitude.pitch),
        Float(attitude.yaw),
        Float(attitude.roll)
    )
}
```

`CMDeviceMotion.attitude` gives the device's absolute orientation as pitch/yaw/roll. Applying
this directly to the camera node's Euler angles makes the SceneKit camera track the device's
real-world orientation.

**Step 4 — Pan gesture fallback:**

```swift
@objc func handlePan(_ gesture: UIPanGestureRecognizer) {
    let delta = CGPoint(x: location.x - lastPanLocation.x, y: ...)
    cameraNode.eulerAngles.y += Float(delta.x) * 0.005
    cameraNode.eulerAngles.x  = max(-.pi/2, min(.pi/2,
        cameraNode.eulerAngles.x + Float(delta.y) * 0.005))
}
```

---

## 5. Unity Integration

### What is "Unity as a Library"?

Since Unity 2019.3, Unity can export a project as a native library (`.aar` on Android,
`.framework` on iOS) instead of a standalone app. This library exposes `UnityPlayer` (Android)
and `UnityFramework` (iOS) classes that you instantiate in your own Activity/ViewController.

The Flutter app detects whether the Unity library is linked at runtime using class reflection,
and either activates the Unity player or shows a status UI explaining what remains.

---

### 5.1 Android — `UnityActivity.kt`

#### Dependency (when Unity export is ready)

In `android/settings.gradle.kts`:
```kotlin
include(":unityLibrary")
project(":unityLibrary").projectDir = File("../unity_export/unityLibrary")
```

In `android/app/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":unityLibrary"))
}
```

#### Runtime detection:

```kotlin
private fun isUnityLibraryLinked(): Boolean {
    return try {
        Class.forName("com.unity3d.player.UnityPlayer")
        true
    } catch (e: ClassNotFoundException) {
        false
    }
}
```

`Class.forName` checks whether `UnityPlayer` is on the classpath at runtime. If the
`:unityLibrary` module is not yet added to Gradle, this throws `ClassNotFoundException`
and returns false — showing the status UI instead of crashing.

#### When Unity is linked — activate the player:

```kotlin
private fun attachUnityPlayer() {
    val unityPlayer = com.unity3d.player.UnityPlayer(this)
    setContentView(unityPlayer)
    unityPlayer.requestFocus()

    // Send product ID to a Unity C# script
    com.unity3d.player.UnityPlayer.UnitySendMessage(
        "ProductBridge",        // GameObject name in Unity scene
        "OnProductReceived",    // C# method name on that GameObject
        productId               // string argument
    )
}
```

`UnitySendMessage` is Unity's static IPC bridge. It finds a named `GameObject` in the active
Unity scene and calls a method on its attached `MonoBehaviour`. This is how the Flutter
product ID flows all the way into a Unity C# script.

The matching C# script in Unity would look like:

```csharp
public class ProductBridge : MonoBehaviour {
    public void OnProductReceived(string productId) {
        // load 3D model or scene for this productId
        ProductManager.Instance.LoadProduct(productId);
    }
}
```

---

### 5.2 iOS — `UnityViewController.swift`

#### Dependency (when Unity export is ready)

1. In Unity: File → Build Settings → iOS → Build (not Archive)
2. Drag `UnityFramework.framework` from the exported project into Xcode
3. In Xcode: General → Frameworks, Libraries, Embedded Content → set to "Embed & Sign"

#### Runtime detection:

```swift
private func isUnityFrameworkLinked() -> Bool {
    return NSClassFromString("UnityFramework") != nil
}
```

#### When Unity is linked — activate the framework:

```swift
private func attachUnityFramework() {
    guard let frameworkBundle = Bundle(path: Bundle.main.bundlePath + "/Frameworks/UnityFramework.framework"),
          let principalClass = frameworkBundle.principalClass as? NSObject.Type,
          let unityFramework = principalClass.init() as? UnityFrameworkLoad else { return }

    unityFramework.setDataBundleId("com.unity3d.framework")
    unityFramework.register(self)
    unityFramework.runEmbedded(withArgc: CommandLine.argc,
                                argv: CommandLine.unsafeArgv,
                                appLaunchOpts: nil)

    // Send product ID to Unity C# script
    unityFramework.sendMessageToGO(withName: "ProductBridge",
                                   functionName: "OnProductReceived",
                                   message: productId)

    if let unityView = unityFramework.appController()?.rootView {
        view.addSubview(unityView)
        unityView.frame = view.bounds
        unityView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }
}
```

`sendMessageToGO` is iOS's equivalent of Android's `UnitySendMessage` — it routes a string
message to a named GameObject in the running Unity scene.

---

### 5.3 Current Integration Status

| Layer | Status | What's done | What's pending |
|---|---|---|---|
| Flutter → MethodChannel | ✅ Complete | `NativeBridge.openUnityScene()` fires | — |
| Android bridge | ✅ Complete | `MainActivity` routes to `UnityActivity` | — |
| iOS bridge | ✅ Complete | `AppDelegate` routes to `UnityViewController` | — |
| Android Unity player | ⏳ Ready | Detection + `attachUnityPlayer()` wired | Unity project export |
| iOS Unity framework | ⏳ Ready | Detection + `attachUnityFramework()` wired | Unity project export |
| Unity C# scene | ⏳ Pending | `UnitySendMessage` target documented | Build C# scripts + 3D assets |

---

## 6. AndroidManifest & Permissions

File: `android/app/src/main/AndroidManifest.xml`

```xml
<!-- Network for ecommerce API calls -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Camera — required for ARCore live camera feed -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" />

<!-- Sensors — gyroscope + accelerometer for VR head-tracking (not required) -->
<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="false" />

<!-- ARCore — marks app as AR Optional (installs ARCore service if absent) -->
<uses-feature android:name="android.hardware.camera.ar" android:required="false" />
<meta-data android:name="com.google.ar.core" android:value="optional" />
```

Activity orientations:

```xml
<!-- AR: fullSensor — supports any orientation as user moves the camera -->
<activity android:name=".ArActivity" android:screenOrientation="fullSensor" />

<!-- VR: portrait — panorama pan is designed for portrait layout -->
<activity android:name=".VrActivity" android:screenOrientation="portrait" />

<!-- Unity: landscape — Unity games are typically landscape -->
<activity android:name=".UnityActivity" android:screenOrientation="landscape" />
```

**Why `ar_ecommerce` uses `ar.core` as `optional`:**
AR Optional means the app installs on non-ARCore devices. If AR service is missing,
`ArCoreApk.checkAvailability()` returns a non-`SUPPORTED` value and the app shows the
fallback UI instead of the AR view. Without `optional`, the Play Store would block
installation on any device without ARCore.

---

## 7. iOS Info.plist & Permissions

File: `ios/Runner/Info.plist`

```xml
<!-- Camera permission — shown to user before ARKit accesses camera -->
<key>NSCameraUsageDescription</key>
<string>Camera is used for augmented reality product preview</string>

<!-- Motion permission — for VR gyroscope head tracking -->
<key>NSMotionUsageDescription</key>
<string>Motion sensors are used for 360° VR navigation</string>
```

Without `NSCameraUsageDescription`, iOS kills the app the moment ARKit tries to access
the camera (a hard crash, not a graceful error). The string is shown to the user in the
system permission dialog.

---

## 8. Dependency Summary

### Android

| Dependency | Version | Purpose |
|---|---|---|
| `com.google.ar:core` | 1.44.0 | ARCore SDK — AR session, plane detection, anchors |
| `androidx.appcompat:appcompat` | 1.7.0 | AppCompatActivity base for all native Activities |
| Android SDK (built-in) | API 24+ | `GLSurfaceView`, `Canvas`, `Bitmap`, `SensorManager`, `Choreographer` |
| Kotlin stdlib | via Gradle | Kotlin language runtime |

### iOS

| Framework | Type | Purpose |
|---|---|---|
| `ARKit` | System | ARWorldTrackingConfiguration, ARPlaneAnchor, ARSCNView |
| `SceneKit` | System | 3D scene for AR virtual objects + VR inside-out sphere |
| `CoreMotion` | System | CMMotionManager for gyroscope-based VR head tracking |
| `UIKit` | System | View controllers, gesture recognizers |
| `Flutter.framework` | Embedded | FlutterMethodChannel IPC bridge |

### Flutter (Dart)

| Package | Version | Purpose |
|---|---|---|
| `provider` | ^6.1.2 | Cart and product state management |
| `go_router` | ^14.6.1 | Declarative URL-based navigation |
| `shared_preferences` | ^2.3.2 | Local persistence for cart |
| `flutter/services.dart` | SDK built-in | `MethodChannel` — the only Flutter/native bridge used |

> **No Flutter AR/VR plugins are used.** All AR, VR, and Unity code is 100% native.
> The only Flutter involvement is `MethodChannel` for the initial launch call.

---

## 9. How to Complete Unity Integration

### Step-by-step for Android

1. In Unity Editor: File → Build Settings → select Android → switch platform
2. Enable "Export Project" checkbox
3. Click Build → choose output folder (e.g. `unity_export/`)
4. In `android/settings.gradle.kts` add:
   ```kotlin
   include(":unityLibrary")
   project(":unityLibrary").projectDir = File("../../unity_export/unityLibrary")
   ```
5. In `android/app/build.gradle.kts` add:
   ```kotlin
   implementation(project(":unityLibrary"))
   ```
6. In `UnityActivity.kt`, inside `attachUnityPlayer()`, uncomment the 4 lines and
   remove the `showReadyState()` call.
7. Create a `ProductBridge` GameObject in your Unity scene with a MonoBehaviour that
   has an `OnProductReceived(string productId)` method.
8. Run `flutter build apk`.

### Step-by-step for iOS

1. In Unity Editor: File → Build Settings → select iOS → switch platform
2. Click Build → choose output folder (e.g. `unity_ios_export/`)
3. In Xcode: drag `unity_ios_export/UnityFramework.framework` into Runner project
4. In Xcode: General → Frameworks, Libraries, Embedded Content → set to "Embed & Sign"
5. In `UnityViewController.swift`, inside `attachUnityFramework()`, uncomment all lines
   and remove the `showReadyState()` call.
6. Create a `ProductBridge` GameObject in Unity with a MonoBehaviour method
   `OnProductReceived(string productId)`.
7. Run `flutter build ios`.

### Data flow once Unity is active

```
Flutter (Dart)
  └─ NativeBridge.openUnityScene("prod_001")
        │  MethodChannel.invokeMethod("openUnityScene", {productId: "prod_001"})
        ▼
Android MainActivity / iOS AppDelegate
  └─ startActivity(UnityActivity) / present(UnityViewController)
        │  productId passed as Intent extra / constructor argument
        ▼
UnityActivity.attachUnityPlayer() / UnityViewController.attachUnityFramework()
  └─ UnityPlayer.UnitySendMessage("ProductBridge", "OnProductReceived", "prod_001")
        │
        ▼
Unity C# — ProductBridge.OnProductReceived("prod_001")
  └─ Load 3D model, set materials, play animations for product "prod_001"
```
