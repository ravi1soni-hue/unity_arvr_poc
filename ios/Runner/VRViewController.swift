import UIKit
import SceneKit
import CoreMotion

/**
 * Real SceneKit 360° VR view controller.
 *
 * - Renders an inside-out SCNSphere with a procedural gradient material
 * - Uses CMMotionManager (gyroscope) for head-tracking rotation
 * - Falls back to pan gesture on simulator / devices without motion
 * - Overlay shows product info
 *
 * Flutter → MethodChannel → AppDelegate → VRViewController (this file)
 */
final class VRViewController: UIViewController {

    private let productId: String
    private var sceneView: SCNView!
    private var cameraNode: SCNNode!
    private var motionManager: CMMotionManager!
    private var statusLabel: UILabel!
    private var lastPanLocation: CGPoint = .zero

    init(productId: String) {
        self.productId = productId
        super.init(nibName: nil, bundle: nil)
        title = "VR 360°"
    }

    required init?(coder: NSCoder) { fatalError("not implemented") }

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupScene()
        setupOverlay()
        setupInput()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        startMotion()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        motionManager?.stopDeviceMotionUpdates()
    }

    // MARK: - Scene

    private func setupScene() {
        let scene = SCNScene()

        // Camera at origin looking into the sphere
        cameraNode = SCNNode()
        cameraNode.camera = SCNCamera()
        cameraNode.camera?.zNear = 0.1
        cameraNode.camera?.zFar = 100
        cameraNode.camera?.fieldOfView = 90
        scene.rootNode.addChildNode(cameraNode)

        // Inside-out sphere (negative X scale flips normals so we see the interior)
        let sphere = SCNSphere(radius: 10)
        sphere.segmentCount = 48
        sphere.firstMaterial = makeGradientMaterial()

        let sphereNode = SCNNode(geometry: sphere)
        sphereNode.scale = SCNVector3(-1, 1, 1)
        scene.rootNode.addChildNode(sphereNode)

        let ambientNode = SCNNode()
        ambientNode.light = SCNLight()
        ambientNode.light?.type = .ambient
        ambientNode.light?.color = UIColor.white
        scene.rootNode.addChildNode(ambientNode)

        sceneView = SCNView(frame: view.bounds)
        sceneView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        sceneView.scene = scene
        sceneView.pointOfView = cameraNode
        sceneView.backgroundColor = .black
        sceneView.showsStatistics = false
        sceneView.allowsCameraControl = false
        view.addSubview(sceneView)
    }

    private func makeGradientMaterial() -> SCNMaterial {
        let size = CGSize(width: 1, height: 256)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            let colors: [(CGFloat, UIColor)] = [
                (0.0, UIColor(red: 0.05, green: 0.05, blue: 0.35, alpha: 1)),
                (0.4, UIColor(red: 0.85, green: 0.55, blue: 0.15, alpha: 1)),
                (0.7, UIColor(red: 0.60, green: 0.30, blue: 0.05, alpha: 1)),
                (1.0, UIColor(red: 0.05, green: 0.05, blue: 0.05, alpha: 1)),
            ]
            let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: colors.map { $0.1.cgColor } as CFArray,
                locations: colors.map { $0.0 }
            )!
            ctx.cgContext.drawLinearGradient(
                gradient,
                start: .zero,
                end: CGPoint(x: 0, y: size.height),
                options: []
            )
        }
        let mat = SCNMaterial()
        mat.diffuse.contents = image
        mat.diffuse.wrapT = .repeat
        mat.diffuse.wrapS = .repeat
        mat.isDoubleSided = true
        mat.lightingModel = .constant
        return mat
    }

    // MARK: - Overlay

    private func setupOverlay() {
        statusLabel = UILabel()
        statusLabel.numberOfLines = 0
        statusLabel.font = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        statusLabel.textColor = .white
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        statusLabel.textAlignment = .center
        statusLabel.text = " Product: \(productId) | 360° VR Showroom\n Move device or swipe to look around "
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            statusLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
        ])
    }

    // MARK: - Motion / Input

    private func setupInput() {
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        sceneView.addGestureRecognizer(pan)
    }

    private func startMotion() {
        motionManager = CMMotionManager()
        guard motionManager.isDeviceMotionAvailable else { return }
        motionManager.deviceMotionUpdateInterval = 1.0 / 60.0
        motionManager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] motion, _ in
            guard let self, let motion else { return }
            let attitude = motion.attitude
            self.cameraNode.eulerAngles = SCNVector3(
                Float(-attitude.pitch),
                Float(attitude.yaw),
                Float(attitude.roll)
            )
        }
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        if gesture.state == .began { lastPanLocation = gesture.location(in: sceneView) }
        let location = gesture.location(in: sceneView)
        let delta = CGPoint(x: location.x - lastPanLocation.x, y: location.y - lastPanLocation.y)
        lastPanLocation = location
        let sensitivity: Float = 0.005
        cameraNode.eulerAngles.y += Float(delta.x) * sensitivity
        cameraNode.eulerAngles.x += Float(delta.y) * sensitivity
        cameraNode.eulerAngles.x = max(-.pi / 2, min(.pi / 2, cameraNode.eulerAngles.x))
    }
}
