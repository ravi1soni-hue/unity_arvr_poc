import UIKit
import Flutter

/**
 * Unity Showroom View Controller.
 */
final class UnityViewController: UIViewController {

    private let productId: String
    private let channel: FlutterMethodChannel?
    private var unityFramework: AnyObject? = nil

    init(productId: String, channel: FlutterMethodChannel? = nil) {
        self.productId = productId
        self.channel = channel
        super.init(nibName: nil, bundle: nil)
        title = "Unity Showroom"
    }

    required init?(coder: NSCoder) { fatalError("not implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.05, green: 0.07, blue: 0.09, alpha: 1)

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Close", style: .done, target: self, action: #selector(close)
        )

        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Mark", style: .plain, target: self, action: #selector(markInUnity)
        )

        if isUnityFrameworkLinked() {
            attachUnityFramework()
        } else {
            showReadyState()
        }
    }

    @objc private func close() {
        dismiss(animated: true)
    }

    @objc private func markInUnity() {
        let timestamp = ISO8601DateFormatter().string(from: Date())
        channel?.invokeMethod("onNativeMessage", arguments: [
            "message": "Unity item \(productId) marked at \(timestamp)",
            "productId": productId,
            "markedData": [
                "action": "UNITY_MARK",
                "timestamp": timestamp,
                "platform": "ios"
            ]
        ])

        let alert = UIAlertController(title: "Unity Marked", message: "Sent back to Flutter", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }

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
        unityFramework = nil
    }

    private func isUnityFrameworkLinked() -> Bool {
        return NSClassFromString("UnityFramework") != nil
    }

    private func attachUnityFramework() {
        guard let bundlePath = Bundle.main.path(
            forResource: "UnityFramework",
            ofType: "framework",
            inDirectory: "Frameworks"
        ),
        let bundle = Bundle(path: bundlePath),
        let principalClass = bundle.principalClass as? NSObject.Type else {
            showReadyState()
            return
        }

        bundle.load()

        guard let fw = principalClass.value(forKey: "getInstance") as? AnyObject else {
            showReadyState()
            return
        }

        unityFramework = fw
        fw.perform(NSSelectorFromString("setDataBundleId:"), with: "com.unity3d.framework")
        fw.perform(NSSelectorFromString("register:"), with: self)
        fw.perform(NSSelectorFromString("runEmbeddedWithArgc:argv:appLaunchOpts:"), with: NSNumber(value: CommandLine.argc), with: CommandLine.unsafeArgv, with: nil)

        if let appController = fw.perform(NSSelectorFromString("appController"))?.takeUnretainedValue(),
           let rootView = (appController as AnyObject).perform(NSSelectorFromString("rootView"))?.takeUnretainedValue() as? UIView {
            view.addSubview(rootView)
            rootView.frame = view.bounds
            rootView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        }

        fw.perform(NSSelectorFromString("sendMessageToGO:functionName:message:"), with: "ProductBridge", with: "OnProductReceived", with: productId)
    }

    private func showReadyState() {
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            stack.topAnchor.constraint(equalTo: scroll.topAnchor, constant: 40),
            stack.leadingAnchor.constraint(equalTo: scroll.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: scroll.trailingAnchor, constant: -24),
            stack.bottomAnchor.constraint(equalTo: scroll.bottomAnchor, constant: -40),
            stack.widthAnchor.constraint(equalTo: scroll.widthAnchor, constant: -48),
        ])

        stack.addArrangedSubview(label("⬡", size: 64, color: UIColor(red: 0, green: 0.74, blue: 0.83, alpha: 1)))
        stack.addArrangedSubview(label("Unity 3D Showroom", size: 22, color: .white, bold: true))
        stack.addArrangedSubview(label("Product: \(productId)", size: 14, color: .lightGray))

        let testBtn = UIButton(type: .system)
        testBtn.setTitle("Test Marking (No Framework)", for: .normal)
        testBtn.addTarget(self, action: #selector(markInUnity), for: .touchUpInside)
        stack.addArrangedSubview(testBtn)

        stack.addArrangedSubview(separator())
        stack.addArrangedSubview(statusRow("Native bridge", "CONNECTED ✓", color: UIColor(red: 0.4, green: 0.8, blue: 0.4, alpha: 1)))
        stack.addArrangedSubview(separator())

        let backBtn = UIButton(type: .system)
        backBtn.setTitle("← Back to Product", for: .normal)
        backBtn.addTarget(self, action: #selector(goBack), for: .touchUpInside)
        stack.addArrangedSubview(backBtn)
    }

    @objc private func goBack() {
        if let nav = navigationController {
            nav.popViewController(animated: true)
        } else {
            dismiss(animated: true)
        }
    }

    private func label(_ text: String, size: CGFloat, color: UIColor, bold: Bool = false) -> UILabel {
        let l = UILabel()
        l.text = text
        l.textColor = color
        l.font = bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
        l.numberOfLines = 0
        l.textAlignment = .center
        return l
    }

    private func statusRow(_ key: String, _ value: String, color: UIColor) -> UIView {
        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 8
        row.addArrangedSubview(label(key + ":", size: 13, color: .lightGray))
        row.addArrangedSubview(label(value, size: 13, color: color, bold: true))
        return row
    }

    private func separator() -> UIView {
        let v = UIView()
        v.backgroundColor = UIColor(white: 0.2, alpha: 1)
        v.heightAnchor.constraint(equalToConstant: 1).isActive = true
        v.translatesAutoresizingMaskIntoConstraints = false
        return v
    }
}
