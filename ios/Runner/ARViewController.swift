import UIKit
import ARKit
import SceneKit

/**
 * Real ARKit view controller.
 *
 * - ARSCNView with ARWorldTrackingConfiguration
 * - Horizontal + vertical plane detection
 * - Tap-to-place: inserts a coloured SCNBox at the hit-test point
 * - Overlay shows plane count and anchor count
 * - Gracefully falls back on simulator (ARKit not supported)
 *
 * Flutter → MethodChannel → AppDelegate → ARViewController (this file)
 */
final class ARViewController: UIViewController, ARSCNViewDelegate, ARSessionDelegate {

    private let productId: String
    private var sceneView: ARSCNView!
    private var statusLabel: UILabel!
    // Accessed only on main thread — no synchronisation needed
    private var planeCount = 0
    private var anchorCount = 0

    init(productId: String) {
        self.productId = productId
        super.init(nibName: nil, bundle: nil)
        title = "AR Preview"
    }

    required init?(coder: NSCoder) { fatalError("not implemented") }

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Reset", style: .plain, target: self, action: #selector(resetSession)
        )

        if ARWorldTrackingConfiguration.isSupported {
            setupARView()
        } else {
            showFallback()
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard ARWorldTrackingConfiguration.isSupported else { return }
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.horizontal, .vertical]
        config.environmentTexturing = .automatic
        sceneView?.session.run(config, options: [.resetTracking, .removeExistingAnchors])
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sceneView?.session.pause()
    }

    // MARK: - Setup

    private func setupARView() {
        sceneView = ARSCNView(frame: view.bounds)
        sceneView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        sceneView.delegate = self
        sceneView.session.delegate = self
        sceneView.showsStatistics = false
        sceneView.autoenablesDefaultLighting = true
        view.addSubview(sceneView)

        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        sceneView.addGestureRecognizer(tap)

        statusLabel = UILabel()
        statusLabel.numberOfLines = 0
        statusLabel.font = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        statusLabel.textColor = .white
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        statusLabel.textAlignment = .center
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            statusLabel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
        ])

        updateStatus()
    }

    private func showFallback() {
        let label = UILabel()
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .white
        label.font = UIFont.systemFont(ofSize: 16)
        label.text = "ARKit is not available on this device/simulator.\n\n" +
            "Product: \(productId)\n\n" +
            "On a compatible iPhone/iPad, this screen renders live camera with " +
            "plane detection and lets you tap to place the product in your room."
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    // MARK: - Interaction

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
        guard let sceneView else { return }
        let location = recognizer.location(in: sceneView)

        // Correct ARKit raycast pattern:
        // raycastQuery returns ARRaycastQuery? — unwrap before calling session.raycast()
        // Calling .flatMap on Optional<ARRaycastQuery> returns Optional<[ARRaycastResult]>;
        // calling .first on that Optional directly is a compile error in Swift.
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

    @objc private func resetSession() {
        sceneView?.scene.rootNode.childNodes.forEach { $0.removeFromParentNode() }
        anchorCount = 0
        planeCount = 0
        updateStatus()
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.horizontal, .vertical]
        sceneView?.session.run(config, options: [.resetTracking, .removeExistingAnchors])
    }

    private func updateStatus() {
        let hint = planeCount == 0
            ? "Point camera at a flat surface…"
            : "Tap a surface to place \(productId)"
        statusLabel?.text = " Product: \(productId) | Planes: \(planeCount) | Placed: \(anchorCount)\n \(hint) "
    }

    // MARK: - ARSCNViewDelegate — plane visualisation

    func renderer(_ renderer: SCNSceneRenderer, didAdd node: SCNNode, for anchor: ARAnchor) {
        guard let planeAnchor = anchor as? ARPlaneAnchor else { return }

        let plane = SCNPlane(
            width: CGFloat(planeAnchor.planeExtent.width),
            height: CGFloat(planeAnchor.planeExtent.height)
        )
        plane.firstMaterial?.diffuse.contents = UIColor.systemBlue.withAlphaComponent(0.3)
        plane.firstMaterial?.isDoubleSided = true

        let planeNode = SCNNode(geometry: plane)
        planeNode.eulerAngles.x = -.pi / 2
        node.addChildNode(planeNode)

        // planeCount is read/written exclusively on the main thread
        DispatchQueue.main.async {
            self.planeCount += 1
            self.updateStatus()
        }
    }

    func renderer(_ renderer: SCNSceneRenderer, didUpdate node: SCNNode, for anchor: ARAnchor) {
        guard let planeAnchor = anchor as? ARPlaneAnchor,
              let planeNode = node.childNodes.first,
              let plane = planeNode.geometry as? SCNPlane else { return }
        plane.width  = CGFloat(planeAnchor.planeExtent.width)
        plane.height = CGFloat(planeAnchor.planeExtent.height)
    }
}
