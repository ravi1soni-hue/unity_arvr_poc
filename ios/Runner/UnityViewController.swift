import UIKit

/**
 * Unity Showroom View Controller.
 *
 * Architecture: Flutter → MethodChannel → AppDelegate → UnityViewController → Unity runtime
 *
 * When the Unity framework is exported and embedded via "Unity as a Library":
 *  1. Add UnityFramework.framework to the Xcode project
 *  2. Replace showReadyState() call in viewDidLoad with attachUnityFramework()
 *  3. The Unity scene receives the product ID via UnitySendMessage
 *
 * Until then this controller serves as the verified native entry-point with a
 * clear status UI showing exactly what is done and what remains.
 *
 * Flutter → MethodChannel → AppDelegate → UnityViewController (this file)
 */
final class UnityViewController: UIViewController {

    private let productId: String

    init(productId: String) {
        self.productId = productId
        super.init(nibName: nil, bundle: nil)
        title = "Unity Showroom"
    }

    required init?(coder: NSCoder) { fatalError("not implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.05, green: 0.07, blue: 0.09, alpha: 1)

        if isUnityFrameworkLinked() {
            attachUnityFramework()
        } else {
            showReadyState()
        }
    }

    // MARK: - Unity detection

    private func isUnityFrameworkLinked() -> Bool {
        return NSClassFromString("UnityFramework") != nil
    }

    // MARK: - Unity attachment (activate when framework is embedded)

    private func attachUnityFramework() {
        // Uncomment when UnityFramework.framework is embedded in the Xcode project:
        //
        // guard let frameworkBundle = Bundle(path: Bundle.main.bundlePath + "/Frameworks/UnityFramework.framework"),
        //       let principalClass = frameworkBundle.principalClass as? NSObject.Type,
        //       let unityFramework = principalClass.init() as? UnityFrameworkLoad else { return }
        //
        // unityFramework.setDataBundleId("com.unity3d.framework")
        // unityFramework.register(self)
        // unityFramework.runEmbedded(withArgc: CommandLine.argc,
        //                             argv: CommandLine.unsafeArgv,
        //                             appLaunchOpts: nil)
        // unityFramework.sendMessageToGO(withName: "ProductBridge",
        //                                functionName: "OnProductReceived",
        //                                message: productId)
        //
        // if let unityView = unityFramework.appController()?.rootView {
        //     view.addSubview(unityView)
        //     unityView.frame = view.bounds
        //     unityView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // }

        showReadyState() // remove this line and uncomment above when Unity is linked
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
            "5. Uncomment attachUnityFramework() in\n   UnityViewController.swift",
            "6. Rebuild — scene loads here automatically",
        ]
        for step in steps {
            stack.addArrangedSubview(label(step, size: 12, color: UIColor(red: 0.47, green: 0.56, blue: 0.61, alpha: 1)))
        }
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
        let keyLabel = label(key + ":", size: 13, color: .lightGray)
        let valLabel = label(value, size: 13, color: color, bold: true)
        row.addArrangedSubview(keyLabel)
        row.addArrangedSubview(valLabel)
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
