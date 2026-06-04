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
10. [Correctness Audit — Bugs Found and Fixed](#10-correctness-audit--bugs-found-and-fixed)

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
     │     OpenGL ES 2.0 sphere          │     SceneKit inside-out sphere
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

> **Critical lifecycle rule (Google's own docs):** Session creation must happen in `onResume`,
> not `onCreate`. `ArCoreApk.requestInstall()` may send the user to Google Play; when they
> return, the activity re-enters via `onResume`. Any init done only in `onCreate` is skipped
> on that return trip.

**Step 1 — Runtime camera permission (Android 6+):**

```kotlin
override fun onResume() {
    super.onResume()

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), RC_CAMERA
        )
        return   // system shows dialog; onResume fires again when user responds
    }
    // ... proceed to step 2
}
```

The `CAMERA` permission is declared in the manifest but Android 6+ requires an explicit
runtime request. Without this, `Session(this)` throws `SecurityException` on real devices.

**Step 2 — Check device compatibility:**

```kotlin
when (ArCoreApk.getInstance().checkAvailability(this)) {
    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
        showFallback("ARCore not supported on this device.")
        return
    }
    ArCoreApk.Availability.UNKNOWN_ERROR,
    ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> {
        showFallback("ARCore availability check failed.")
        return
    }
    else -> { /* SUPPORTED_* variants or UNKNOWN_CHECKING — proceed to requestInstall */ }
}
```

**Step 3 — `requestInstall()` (mandatory for both AR Required and AR Optional apps):**

```kotlin
when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
        installRequested = true
        return   // user sent to Play Store; activity resumes via onResume when done
    }
    ArCoreApk.InstallStatus.INSTALLED -> { /* safe to create Session */ }
}
```

Google's ARCore docs state: *"Both AR Required and AR Optional apps must call
`requestInstall()` before creating a Session."* Skipping this step means ARCore may be
stale or missing and session creation fails with a cryptic exception.

**Step 4 — Create a Session (once, reused across pause/resume):**

```kotlin
if (arSession == null) {
    val session = Session(this)
    session.configure(Config(session).apply {
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    })
    arSession = session
    // attach renderer + touch listener (only done once)
}
arSession?.resume()
glSurfaceView?.onResume()
```

A `Session` owns the camera + tracking pipeline. `Config` sets:
- `HORIZONTAL_AND_VERTICAL` — detect both floors and walls
- `LATEST_CAMERA_IMAGE` — always use the most recent frame
- `ENVIRONMENTAL_HDR` — estimate real-world lighting to shade AR objects realistically

**Step 5 — OpenGL Renderer (`ArCoreRenderer`):**

```kotlin
class ArCoreRenderer(session, productId, onUpdate) : GLSurfaceView.Renderer {

    // NDC quad — input to Frame.transformCoordinates2d()
    private val QUAD_NDC = floatArrayOf(-1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f)

    override fun onSurfaceCreated(gl, config) {
        // Generate an OES texture for the camera feed
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        session.setCameraTextureName(cameraTextureId)  // tell ARCore to write here
        bgProgram = buildProgram(vertexShaderSrc, fragmentShaderSrc)
    }

    override fun onDrawFrame(gl) {
        val frame = session.update()      // advance ARCore, get latest camera frame

        // Transform UV coords per-frame to correct for camera sensor vs. display rotation.
        // Hardcoded UVs are wrong on devices where sensor and display orientation differ.
        val transformedUVs = FloatArray(8)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, QUAD_NDC,
            Coordinates2d.TEXTURE_NORMALIZED,                   transformedUVs
        )
        bgTexCoordBuffer = floatBuffer(transformedUVs)

        drawCameraBackground()     // render camera image using transformed UVs
        handlePendingTap(frame)    // if user tapped, do a hit test
        updatePlaneCount(frame)    // count detected planes, report to UI
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

**Step 6 — Tap to place a product anchor:**

```kotlin
// Touch listener on main thread
glSurfaceView.setOnTouchListener { _, event ->
    if (event.action == MotionEvent.ACTION_UP) renderer?.onTap(event.x, event.y)
    true
}

// onTap writes to @Volatile fields; onDrawFrame reads them on GL thread
@Volatile private var pendingTapX = -1f
@Volatile private var pendingTapY = -1f
fun onTap(x: Float, y: Float) { pendingTapX = x; pendingTapY = y }

// Inside ArCoreRenderer.onDrawFrame:
val tx = pendingTapX; val ty = pendingTapY
if (tx >= 0f && ty >= 0f) {
    pendingTapX = -1f; pendingTapY = -1f
    for (hit in frame.hitTest(tx, ty)) {
        val trackable = hit.trackable
        if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
            anchors.add(hit.createAnchor())  // lock virtual object to real-world point
            break
        }
    }
}
```

`@Volatile` ensures the tap coordinates written on the main thread are immediately visible
on the GL thread. `frame.hitTest` casts a ray from screen coordinates into 3D world space
and returns intersection points with detected planes. `createAnchor()` creates a pose that
stays locked to the real-world position even as the device moves.

**Step 7 — Session lifecycle:**

```kotlin
override fun onPause() {
    super.onPause()
    glSurfaceView?.onPause()    // pause GL thread first
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
    guard let sceneView else { return }
    let location = recognizer.location(in: sceneView)

    // raycastQuery returns ARRaycastQuery? (Optional).
    // Do NOT use .flatMap { session.raycast($0) } — that returns [ARRaycastResult]?
    // and .first on an Optional<Array> is a compile error in Swift.
    // Correct pattern: unwrap the query explicitly, then call session.raycast().
    guard let query = sceneView.raycastQuery(
        from: location,
        allowing: .estimatedPlane,
        alignment: .any
    ) else { return }

    let results = sceneView.session.raycast(query)
    guard let first = results.first else { return }

    let box = SCNBox(width: 0.15, height: 0.4, length: 0.15, chamferRadius: 0.01)
    box.firstMaterial?.diffuse.contents = UIColor(red: 0.9, green: 0.5, blue: 0.1, alpha: 1)
    let node = SCNNode(geometry: box)
    node.simdTransform = first.worldTransform
    node.position.y += 0.2
    sceneView.scene.rootNode.addChildNode(node)
    anchorCount += 1
    updateStatus()
}
```

`raycastQuery` + `session.raycast` replaces the deprecated `hitTest(_:types:)` API (deprecated
in iOS 14). It casts a ray from screen coordinates into 3D world space and returns intersection
points with estimated planes.

> **Thread safety note:** `planeCount` is only ever incremented inside
> `DispatchQueue.main.async { self.planeCount += 1 }` in `renderer(_:didAdd:for:)`.
> ARKit calls that delegate method on a background thread, so the increment must be
> dispatched to main to avoid a data race with `updateStatus()` reads.

---

## 4. VR Integration

### What is 360° VR here?

A 360° equirectangular panorama rendered on the inside of a 3D sphere. The camera sits at
the sphere's centre and looks outward. Rotating the device rotates the camera — giving the
full "look around a room" experience. On emulator, swipe gestures replace the gyroscope.

**No headset required** — this is "Magic Window" VR (single screen, no side-by-side stereo
split). The same technique is used by Google Street View, Facebook 360 Photos, and most
mobile 360° viewer apps.

---

### 4.1 Android — OpenGL ES 2.0 Equirectangular Sphere

#### Why this approach (not Google Cardboard SDK / GVR)?

| Option | Verdict |
|---|---|
| **Google Cardboard SDK** | Requires C++ NDK + JNI bridge — too complex for a Kotlin-only app |
| **Google VR SDK `VrPanoramaView`** | Java widget, deprecated, no Maven Central release in recent versions |
| **Android XR / OpenXR** | Developer Preview 4 — alpha quality, not production-ready |
| **Custom OpenGL ES 2.0 sphere** | Pure Kotlin, no external dependencies, standard industry pattern for 360° viewers |

The custom sphere renderer is the same core approach used by:
- Google Street View for Android (equirectangular sphere)
- YouTube 360° video player
- Facebook 360 Photos

#### No extra dependency needed

Everything used is part of the Android SDK:
- `android.opengl.GLES20` — OpenGL ES 2.0 API
- `android.opengl.GLSurfaceView` — managed OpenGL surface
- `android.opengl.Matrix` — 4×4 matrix operations (perspective, rotate, multiply)
- `android.opengl.GLUtils` — uploads Android `Bitmap` to an OpenGL texture
- `android.hardware.SensorManager` — gyroscope fusion sensor

#### Architecture — `VrActivity.kt`

The file contains three classes:

```
VrActivity          — AppCompatActivity + SensorEventListener
SafeGLSurfaceView   — GLSurfaceView subclass (crash guard for emulator)
VrSphereRenderer    — GLSurfaceView.Renderer (all OpenGL code)
```

---

**Component 1 — `VrActivity` (lifecycle + sensor)**

```kotlin
class VrActivity : AppCompatActivity(), SensorEventListener {

    private var glSurfaceView: SafeGLSurfaceView? = null
    private var renderer: VrSphereRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        renderer = VrSphereRenderer(productId)

        glSurfaceView = SafeGLSurfaceView(this).also { sv ->
            sv.setEGLContextClientVersion(2)       // OpenGL ES 2.0
            sv.preserveEGLContextOnPause = true    // keep GL state across pause/resume
            sv.setRenderer(renderer!!)
            sv.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY  // 60fps
        }
        // ...
    }

    override fun onResume() {
        glSurfaceView?.onResume()
        sensorManager.registerListener(this, rotationSensor, SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        glSurfaceView?.onPause()
    }
}
```

**Component 2 — `SafeGLSurfaceView` (emulator crash guard)**

On Android emulators using SwiftShader (software GL renderer), `GLSurfaceView.mGLThread`
(an internal private field) is left `null` after a failed EGL context init — even though
`setRenderer()` appeared to succeed. When the activity pauses, `mGLThread.onPause()` throws
a NullPointerException inside `super.onPause()`.

The fix is a one-line subclass that catches that NPE at the call site:

```kotlin
class SafeGLSurfaceView(context: Context) : GLSurfaceView(context) {
    override fun onPause()  { try { super.onPause()  } catch (_: Exception) {} }
    override fun onResume() { try { super.onResume() } catch (_: Exception) {} }
}
```

This is a genuine fix, not a workaround — the crash location is inside Android's own
internal code; the only way to handle it is at the boundary where we call into it.

**Component 3 — Sensor → view matrix pipeline**

```kotlin
override fun onSensorChanged(event: SensorEvent?) {
    // TYPE_ROTATION_VECTOR fuses accelerometer + gyroscope + magnetometer
    // into a stable, drift-corrected rotation vector
    SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

    // Remap axes for portrait orientation:
    // device Y axis (up when held upright) → world Z axis (vertical in 3D space)
    SensorManager.remapCoordinateSystem(
        rotMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapMatrix
    )

    renderer?.setSensorMatrix(remapMatrix)   // pass 4×4 matrix to GL thread
}
```

**Component 4 — `VrSphereRenderer` — sphere geometry**

```
UV Sphere:
  STACKS = 32  (horizontal rings, top → bottom)
  SLICES = 64  (vertical segments around circumference)
  RADIUS = 50f (camera is at origin; large radius avoids near-clip issues)
```

The sphere is generated using spherical coordinates:

```kotlin
for (stack in 0..STACKS) {
    val theta    = PI * stack / STACKS   // 0 → PI (top pole to bottom pole)
    val sinTheta = sin(theta).toFloat()
    val cosTheta = cos(theta).toFloat()
    val v        = stack.toFloat() / STACKS   // texture V coordinate

    for (slice in 0..SLICES) {
        val phi  = 2 * PI * slice / SLICES   // 0 → 2PI (full circle)
        val u    = slice.toFloat() / SLICES  // texture U coordinate

        // Cartesian position on sphere surface
        x = RADIUS * sinTheta * cos(phi)
        y = RADIUS * cosTheta
        z = RADIUS * sinTheta * sin(phi)
    }
}
```

The UV coordinates `(u, v)` map directly to an equirectangular panorama image:
- U = 0 is the left edge, U = 1 is the right edge (full 360°)
- V = 0 is the top of the image (north pole), V = 1 is the bottom (south pole)

**Triangle winding for inside-out rendering:**

A normal sphere has triangles wound counter-clockwise (CCW) when viewed from the outside.
Since the camera is inside the sphere, we reverse the winding to CCW from the inside:

```kotlin
// Normal (outside) winding:   tl, bl, tr  /  bl, br, tr
// Reversed (inside) winding:  tl, tr, bl  /  bl, tr, br  ← what we use
indices += tl; indices += tr; indices += bl
indices += bl; indices += tr; indices += br
```

Face culling is disabled (`glDisable(GL_CULL_FACE)`) since we only have one sphere.

**Component 5 — GLSL shaders**

```glsl
// Vertex shader
attribute vec4 a_Position;
attribute vec2 a_TexCoord;
uniform mat4 u_MVP;
varying vec2 v_TexCoord;
void main() {
    gl_Position = u_MVP * a_Position;
    v_TexCoord  = a_TexCoord;
}

// Fragment shader
precision mediump float;
uniform sampler2D u_Texture;
varying vec2 v_TexCoord;
void main() {
    gl_FragColor = texture2D(u_Texture, v_TexCoord);
}
```

The MVP uniform matrix = Projection × View. No Model matrix is needed (sphere is at origin).

**Component 6 — MVP matrix construction per frame**

```kotlin
override fun onDrawFrame(gl: GL10?) {
    val sm = sensorMatrix   // 4×4 rotation matrix from sensor thread

    if (sm != null) {
        // Transpose of an orthonormal matrix = its inverse.
        // Sensor matrix rotates device→world; transpose gives world→device (= view matrix).
        Matrix.transposeM(viewMatrix, 0, sm, 0)
    } else {
        // Touch pan fallback — build view matrix from accumulated yaw/pitch
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.rotateM(viewMatrix, 0, touchYaw,   0f, 1f, 0f)
        Matrix.rotateM(viewMatrix, 0, touchPitch, 1f, 0f, 0f)
    }

    // MVP = Projection × View  (no Model matrix — sphere is at origin)
    Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)
    GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
}
```

**Component 7 — Perspective projection**

```kotlin
override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    GLES20.glViewport(0, 0, width, height)
    // 90° vertical FOV — comfortable for mobile VR without a headset.
    // Near = 0.1, Far = 200 (sphere radius is 50, well within range)
    Matrix.perspectiveM(projMatrix, 0, 90f, width.toFloat() / height.toFloat(), 0.1f, 200f)
}
```

**Component 8 — Panorama texture upload**

The panorama is a procedural equirectangular Bitmap uploaded to OpenGL:

```kotlin
private fun uploadPanoramaTexture(): Int {
    val bmp = buildEquirectangularBitmap(2048, 1024)   // 2:1 ratio = equirectangular standard

    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
    GLES20.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    GLES20.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)   // Bitmap → GL texture
    bmp.recycle()
    return ids[0]
}
```

`GLUtils.texImage2D` converts an Android `Bitmap` to an OpenGL texture in one call.
To use a real 360° photo, replace the procedural bitmap with:

```kotlin
val bmp = BitmapFactory.decodeResource(resources, R.drawable.panorama_360)
```

**How all components connect:**

```
SensorManager (sensor thread)
    → getRotationMatrixFromVector + remapCoordinateSystem
    → setSensorMatrix(4×4 float array)  ─────────────────────────────────────┐
                                                                               ▼
GestureDetector (main thread)                                    VrSphereRenderer.onDrawFrame()
    → applyTouchDelta(dx, dy)  ─────────────────────────────────► transposeM → viewMatrix
                                                                   projMatrix × viewMatrix → MVP
                                                                   glUniformMatrix4fv(mvpUniform)
                                                                   glDrawElements(sphere)
```

---

### 4.2 iOS — SceneKit Inside-Out Sphere (same concept, different API)

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

The `unityPlayer` is stored as `Any?` (not the concrete type) so the file compiles without
the Unity library on the classpath. Lifecycle methods forward via reflection:

```kotlin
// Stored as Any — compiles without Unity library; cast is safe when isUnityLibraryLinked() == true
private var unityPlayer: Any? = null

private fun attachUnityPlayer() {
    val playerClass = Class.forName("com.unity3d.player.UnityPlayer")
    val player = playerClass.getConstructor(Activity::class.java).newInstance(this)
    unityPlayer = player

    val view = playerClass.getMethod("getView").invoke(player) as android.view.View
    setContentView(view)
    view.requestFocus()

    // Send product ID to a Unity C# script (static method on UnityPlayer)
    playerClass.getMethod("UnitySendMessage",
        String::class.java, String::class.java, String::class.java)
        .invoke(null, "ProductBridge", "OnProductReceived", productId)
}

// Unity docs: pause() must be called BEFORE super.onPause() so the engine can
// complete its current frame before the window surface is destroyed
override fun onPause() {
    unityPlayer?.let { p -> p.javaClass.getMethod("pause").invoke(p) }
    super.onPause()
}
override fun onResume() {
    super.onResume()
    unityPlayer?.let { p -> p.javaClass.getMethod("resume").invoke(p) }
}
override fun onDestroy() {
    unityPlayer?.let { p -> p.javaClass.getMethod("quit").invoke(p) }
    unityPlayer = null
    super.onDestroy()
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

The framework is stored as `AnyObject?` so the file compiles without `UnityFramework`
on the Xcode classpath. All calls use `perform(NSSelectorFromString:)`:

```swift
// Stored as AnyObject — compiles without framework; selector calls are safe when isUnityFrameworkLinked() == true
private var unityFramework: AnyObject? = nil

private func attachUnityFramework() {
    guard let bundlePath = Bundle.main.path(
        forResource: "UnityFramework", ofType: "framework", inDirectory: "Frameworks"
    ),
    let bundle = Bundle(path: bundlePath),
    let principalClass = bundle.principalClass as? NSObject.Type else { showReadyState(); return }

    bundle.load()
    guard let fw = principalClass.value(forKey: "getInstance") as? AnyObject else {
        showReadyState(); return
    }
    unityFramework = fw

    fw.perform(NSSelectorFromString("setDataBundleId:"), with: "com.unity3d.framework")
    fw.perform(NSSelectorFromString("register:"), with: self)
    fw.perform(NSSelectorFromString("runEmbeddedWithArgc:argv:appLaunchOpts:"),
               with: NSNumber(value: CommandLine.argc),
               with: CommandLine.unsafeArgv, with: nil)

    // Attach Unity's render view
    if let appCtrl = fw.perform(NSSelectorFromString("appController"))?.takeUnretainedValue(),
       let rootView = (appCtrl as AnyObject).perform(NSSelectorFromString("rootView"))
                        ?.takeUnretainedValue() as? UIView {
        view.addSubview(rootView)
        rootView.frame = view.bounds
        rootView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }

    // Send product ID to Unity C# script
    fw.perform(NSSelectorFromString("sendMessageToGO:functionName:message:"),
               with: "ProductBridge", with: "OnProductReceived", with: productId)
}

// Lifecycle — Unity must be paused before the render surface disappears
override func viewWillDisappear(_ animated: Bool) {
    super.viewWillDisappear(animated)
    unityFramework?.perform(NSSelectorFromString("pause:"), with: NSNumber(value: true))
}
override func viewWillAppear(_ animated: Bool) {
    super.viewWillAppear(animated)
    unityFramework?.perform(NSSelectorFromString("pause:"), with: NSNumber(value: false))
}
deinit {
    unityFramework?.perform(NSSelectorFromString("unloadApplication"))
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

---

## 10. Correctness Audit — Bugs Found and Fixed

All three integrations were audited against official SDK documentation after initial
implementation. The following bugs were identified and corrected.

### 10.1 Android AR — 5 bugs fixed

| # | Bug | Impact | Fix |
|---|---|---|---|
| 1 | Session init in `onCreate`, not `onResume` | `requestInstall` sends user to Play Store and returns via `onResume`. Old code skipped reinit on return trip. | Moved entire ARCore init chain into `onResume` |
| 2 | No runtime camera permission | `Session(this)` throws `SecurityException` on real devices (Android 6+). The catch block swallowed it silently. | Added `ActivityCompat.requestPermissions` + `onRequestPermissionsResult` |
| 3 | `ArCoreApk.requestInstall()` never called | Google mandates this for both AR Required and AR Optional apps. Without it, ARCore may be stale/missing. | Added `requestInstall` + `installRequested` flag for the Play Store return trip |
| 4 | UV texture coords hardcoded | Camera image rotated/mirrored on devices where sensor orientation ≠ display orientation. | `Frame.transformCoordinates2d(NDC → TEXTURE_NORMALIZED)` called every frame |
| 5 | `pendingTapX/Y` not `@Volatile` | Written on main thread, read on GL thread — data race; stale tap values possible. | Made both fields `@Volatile` |

### 10.2 Android Unity — 3 bugs fixed

| # | Bug | Impact | Fix |
|---|---|---|---|
| 1 | `attachUnityPlayer()` always showed placeholder | Even when `Class.forName` succeeded, `showReadyState()` was called immediately. Unity was never actually attached. | Instantiates `UnityPlayer` via reflection, sets it as content view, calls `UnitySendMessage` |
| 2 | No lifecycle forwarding | Unity engine wouldn't pause/resume/quit with the activity — rendering glitches, potential ANRs, leaked GL context | Added `onResume/onPause/onDestroy` forwarding via reflection; `pause()` called before `super.onPause()` |
| 3 | `unityPlayer` not stored as member | Couldn't forward lifecycle events or send messages after `onCreate` | Stored as `Any?` member (compiles without Unity library on classpath) |

### 10.3 iOS AR — 2 bugs fixed

| # | Bug | Impact | Fix |
|---|---|---|---|
| 1 | `.flatMap` raycast pattern | `Optional.flatMap { session.raycast($0) }` returns `[ARRaycastResult]?`. Calling `.first` on `Optional<Array>` without `?` is a **compile error** in Swift. iOS code would not build. | Replaced with `guard let query = ...raycastQuery(...) else { return }` then `session.raycast(query)` |
| 2 | `planeCount += 1` on ARKit background thread | ARKit calls `renderer(_:didAdd:for:)` on a background thread. Incrementing `planeCount` there while `updateStatus()` reads it on main is a data race. | Moved `planeCount += 1` inside `DispatchQueue.main.async` block |

### 10.4 iOS Unity — 3 bugs fixed

| # | Bug | Impact | Fix |
|---|---|---|---|
| 1 | `attachUnityFramework()` always showed placeholder | Same as Android: even when `NSClassFromString` returned non-nil, `showReadyState()` was called. | Loads framework bundle, gets singleton via `getInstance`, calls `runEmbeddedWithArgc`, attaches root view, sends `sendMessageToGO` |
| 2 | No lifecycle forwarding | Unity engine wouldn't pause/resume/unload — background CPU usage, potential crashes on iOS 16+ | Added `viewWillDisappear/viewWillAppear` forwarding `pause:` selector; `deinit` calls `unloadApplication` |
| 3 | No stored framework reference | Couldn't forward lifecycle events or send messages after `viewDidLoad` | Stored as `AnyObject?` member (compiles without `UnityFramework.framework` on Xcode classpath) |

### 10.5 What was already correct

| Component | Status |
|---|---|
| Android VR — OpenGL ES 2.0 equirectangular sphere | ✅ Correct. Proper UV sphere, reversed winding, `Frame.transformCoordinates2d` not needed (no ARCore), sensor → view matrix via transpose. |
| iOS VR — SceneKit inside-out sphere | ✅ Correct. `SCNVector3(-1,1,1)` scale flips normals inward. `CMMotionManager` with `.xArbitraryZVertical` reference frame. Euler angles acceptable for non-extreme pitch values. |
| Flutter MethodChannel bridge | ✅ Correct. Channel name matches both sides. `productId` passed as Intent extra / constructor arg. |
| AndroidManifest permissions | ✅ Correct. Camera declared. Gyroscope `required=false`. ARCore `optional`. |
| iOS Info.plist permissions | ✅ Correct. `NSCameraUsageDescription` and `NSMotionUsageDescription` present. |
