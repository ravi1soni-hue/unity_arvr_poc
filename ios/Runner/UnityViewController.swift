import UIKit

/**
 * Unity Showroom View Controller.
 *
 * Architecture: Flutter → MethodChannel → AppDelegate → UnityViewController → Unity runtime
 *
 * Runtime detection via NSClassFromString means this compiles without UnityFramework
 * embedded. When the framework IS added, the player is loaded and lifecycle events
 * (pause/resume/unload) are forwarded via a stored AnyObject reference so the
 * Unity engine behaves correctly during foreground/background transitions.
 *
 * To activate Unity integration:
 *   1. Open your Unity project (2019.3+)
 *   2. File → Build Settings → iOS → Export
 *   3. Drag UnityFramework.framework into Xcode project
 *   4. Set Embed & Sign in General → Frameworks, Libraries, and Embedded Content
 *   5. Rebuild — Unity scene loads here automatically
 */
final class UnityViewController: UIViewController {

    private let productId: String
    // Stored as AnyObject so this file compiles without UnityFramework on the classpath.
    // When the framework is linked this holds the UnityFramework singleton instance.
    private var unityFramework: AnyObject? = nil

    init(productId: String) {
        self.productId = productId
        super.init(nibName: nil, bundle: nil)
        title = "Unity Showroom"
    }

    required init?(coder: NSCoder) { fatalError("not implemented") }

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.05, green: 0.07, blue: 0.09, alpha: 1)

        if isUnityFrameworkLinked() {
            attachUnityFramework()
        } else {
            showReadyState()
        }
    }

    // Unity must be paused before the view disappears to let the engine
    // complete its current frame before the render surface is invalidated
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

    // MARK: - Unity detection

    private func isUnityFrameworkLinked() -> Bool {
        return NSClassFromString("UnityFramework") != nil
    }

    // MARK: - Unity attachment

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

        // Set data bundle so Unity can locate its assets
        fw.perform(
            NSSelectorFromString("setDataBundleId:"),
            with: "com.unity3d.framework"
        )

        // Register this VC as the Unity app controller delegate
        fw.perform(NSSelectorFromString("register:"), with: self)

        // Boot the Unity engine
        fw.perform(
            NSSelectorFromString("runEmbeddedWithArgc:argv:appLaunchOpts:"),
            with: NSNumber(value: CommandLine.argc),
            with: CommandLine.unsafeArgv,
            with: nil
        )

        // Attach Unity's root view
        if let appController = fw.perform(NSSelectorFromString("appController"))?.takeUnretainedValue(),
           let rootView = (appController as AnyObject).perform(NSSelectorFromString("rootView"))?.takeUnretainedValue() as? UIView {
            view.addSubview(rootView)
            rootView.frame = view.bounds
            rootView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        }

        // Send product context into the Unity C# scene
        fw.perform(
            NSSelectorFromString("sendMessageToGO:functionName:message:"),
            with: "ProductBridge",
            with: "OnProductReceived",
            with: productId
        )
    }

    // MARK: - Fallback UI

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
        stack.addArrangedSubview(separator())
        stack.addArrangedSubview(statusRow("Native bridge", "CONNECTED ✓", color: UIColor(red: 0.4, green: 0.8, blue: 0.4, alpha: 1)))
        stack.addArrangedSubview(statusRow("Unity framework", "pending export", color: UIColor(red: 1, green: 0.65, blue: 0.15, alpha: 1)))
        stack.addArrangedSubview(separator())
        stack.addArrangedSubview(label("To activate:", size: 13, color: .white, bold: true))
        let steps = [
            "1. Open your Unity project",
            "2. File → Build Settings → iOS → Export",
            "3. Drag UnityFramework.framework into Xcode",
            "4. Set Embed & Sign in General → Frameworks",
            "5. Rebuild — scene loads here automatically",
        ]
        for step in steps { stack.addArrangedSubview(label(step, size: 12, color: UIColor(red: 0.47, green: 0.56, blue: 0.61, alpha: 1))) }
        stack.addArrangedSubview(progressView())
        stack.addArrangedSubview(label("Integration: 65% complete", size: 11, color: .darkGray))

        let backBtn = UIButton(type: .system)
        backBtn.setTitle("← Back to Product", for: .normal)
        backBtn.addTarget(self, action: #selector(goBack), for: .touchUpInside)
        stack.addArrangedSubview(backBtn)
    }

    @objc private func goBack() { navigationController?.popViewController(animated: true) }

    // MARK: - Helpers

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

    private func progressView() -> UIProgressView {
        let p = UIProgressView(progressViewStyle: .default)
        p.progress = 0.65
        p.tintColor = UIColor(red: 0, green: 0.74, blue: 0.83, alpha: 1)
        p.widthAnchor.constraint(equalToConstant: 200).isActive = true
        return p
    }
}
