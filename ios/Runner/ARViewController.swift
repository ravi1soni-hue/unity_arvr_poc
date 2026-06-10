import UIKit
import ARKit
import SceneKit

final class ARViewController: UIViewController, ARSCNViewDelegate, ARSessionDelegate {

    private let productId: String
    private var sceneView: ARSCNView!
    private var statusLabel: UILabel!
    private var planeCount = 0
    private var anchorCount = 0

    init(productId: String) {
        self.productId = productId
        super.init(nibName: nil, bundle: nil)
        title = "AR Preview"
    }

    required init?(coder: NSLayoutCoder) { fatalError("not implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Close", style: .done, target: self, action: #selector(close)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Reset", style: .plain, target: self, action: #selector(resetSession)
        )

        if ARWorldTrackingConfiguration.isSupported {
            setupARView()
        } else {
            showFallback()
        }
    }

    @objc private func close() {
        dismiss(animated: true)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard ARWorldTrackingConfiguration.isSupported else { return }
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.horizontal, .vertical]
        sceneView?.session.run(config, options: [.resetTracking, .removeExistingAnchors])
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sceneView?.session.pause()
    }

    private func setupARView() {
        sceneView = ARSCNView(frame: view.bounds)
        sceneView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        sceneView.delegate = self
        sceneView.session.delegate = self
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
        label.text = "ARKit is not supported on this device."
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
        guard let sceneView = sceneView else { return }
        let location = recognizer.location(in: sceneView)
        guard let query = sceneView.raycastQuery(from: location, allowing: .estimatedPlane, alignment: .any) else { return }
        let results = sceneView.session.raycast(query)
        guard let first = results.first else { return }

        let box = SCNBox(width: 0.1, height: 0.1, length: 0.1, chamferRadius: 0)
        box.firstMaterial?.diffuse.contents = UIColor.orange
        let node = SCNNode(geometry: box)
        node.simdTransform = first.worldTransform
        sceneView.scene.rootNode.addChildNode(node)

        anchorCount += 1
        updateStatus()
    }

    @objc private func resetSession() {
        sceneView?.scene.rootNode.childNodes.forEach { $0.removeFromParentNode() }
        anchorCount = 0
        planeCount = 0
        updateStatus()
    }

    private func updateStatus() {
        statusLabel?.text = "Product: \(productId) | Planes: \(planeCount) | Objects: \(anchorCount)"
    }

    func renderer(_ renderer: SCNSceneRenderer, didAdd node: SCNNode, for anchor: ARAnchor) {
        if anchor is ARPlaneAnchor {
            DispatchQueue.main.async {
                self.planeCount += 1
                self.updateStatus()
            }
        }
    }
}
