import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate {

    private let channelName = "com.example.ar_ecommerce/native"

    override func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {

        guard let controller = window?.rootViewController as? FlutterViewController else {
            return super.application(application, didFinishLaunchingWithOptions: launchOptions)
        }

        let methodChannel = FlutterMethodChannel(
            name: channelName,
            binaryMessenger: controller.binaryMessenger
        )

        methodChannel.setMethodCallHandler { [weak controller, weak methodChannel] call, result in
            let args    = call.arguments as? [String: Any]
            let productId = args?["productId"] as? String ?? "unknown"

            switch call.method {

            case "pingNative":
                result([
                    "message":   "iOS native bridge OK — product: \(productId)",
                    "source":    "ios",
                    "productId": productId,
                ])

            case "openArScreen":
                if let fc = controller {
                    self.present(ARViewController(productId: productId), from: fc)
                }
                methodChannel?.invokeMethod("onNativeMessage", arguments: [
                    "message":   "ARKit session started for \(productId)",
                    "productId": productId,
                ])
                result(true)

            case "openVrScreen":
                if let fc = controller {
                    self.present(VRViewController(productId: productId), from: fc)
                }
                methodChannel?.invokeMethod("onNativeMessage", arguments: [
                    "message":   "VR 360° showroom opened for \(productId)",
                    "productId": productId,
                ])
                result(true)

            case "openUnityScene":
                // Fixed: routes to UnityViewController, not VRViewController
                if let fc = controller {
                    self.present(UnityViewController(productId: productId), from: fc)
                }
                methodChannel?.invokeMethod("onNativeMessage", arguments: [
                    "message":   "Unity showroom launched for \(productId)",
                    "productId": productId,
                ])
                result(true)

            default:
                result(FlutterMethodNotImplemented)
            }
        }

        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }

    // MARK: - Helper

    private func present(_ vc: UIViewController, from controller: FlutterViewController) {
        let nav = UINavigationController(rootViewController: vc)
        nav.modalPresentationStyle = .fullScreen
        controller.present(nav, animated: true)
    }
}
