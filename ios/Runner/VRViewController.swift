import UIKit
import SceneKit
import CoreMotion

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

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Close", style: .done, target: self, action: #selector(close)
        )

        setupScene()
        setupOverlay()
        setupInput()
    }

    @objc private func close() {
        dismiss(animated: true)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        startMotion()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        motionManager?.stopDeviceMotionUpdates()
    }

    private func setupScene() {
        let scene = SCNScene()
        cameraNode = SCNNode()
        cameraNode.camera = SCNCamera()
        scene.rootNode.addChildNode(cameraNode)

        let sphere = SCNSphere(radius: 10)
        sphere.firstMaterial?.isDoubleSided = true
        sphere.firstMaterial?.diffuse.contents = makeGradientImage()

        let sphereNode = SCNNode(geometry: sphere)
        sphereNode.scale = SCNVector3(-1, 1, 1) // Invert for inside-out
        scene.rootNode.addChildNode(sphereNode)

        sceneView = SCNView(frame: view.bounds)
        sceneView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        sceneView.scene = scene
        sceneView.pointOfView = cameraNode
        sceneView.backgroundColor = .black
        view.addSubview(sceneView)
    }

    private func makeGradientImage() -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 256))
        return renderer.image { ctx in
            let colors = [UIColor.darkGray.cgColor, UIColor.lightGray.cgColor] as CFArray
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: nil)!
            ctx.cgContext.drawLinearGradient(gradient, start: CGPoint(x: 0, y: 0), end: CGPoint(x: 0, y: 256), options: [])
        }
    }

    private func setupOverlay() {
        statusLabel = UILabel()
        statusLabel.numberOfLines = 0
        statusLabel.font = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        statusLabel.textColor = .white
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        statusLabel.textAlignment = .center
        statusLabel.text = "Product: \(productId) | 360° VR Showroom\nMove device or swipe to look around"
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            statusLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
        ])
    }

    private func setupInput() {
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        sceneView.addGestureRecognizer(pan)
    }

    private func startMotion() {
        motionManager = CMMotionManager()
        guard motionManager.isDeviceMotionAvailable else { return }
        motionManager.deviceMotionUpdateInterval = 1.0 / 60.0
        motionManager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] motion, _ in
            guard let self = self, let motion = motion else { return }
            self.cameraNode.eulerAngles = SCNVector3(-motion.attitude.pitch, motion.attitude.yaw, motion.attitude.roll)
        }
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        let location = gesture.location(in: sceneView)
        if gesture.state == .began { lastPanLocation = location }
        let deltaX = Float(location.x - lastPanLocation.x)
        let deltaY = Float(location.y - lastPanLocation.y)
        lastPanLocation = location

        cameraNode.eulerAngles.y -= deltaX * 0.005
        cameraNode.eulerAngles.x -= deltaY * 0.005
    }
}
