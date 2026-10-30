import UIKit
import CoreImage
import ComposeApp

/// iOS share sheet for identity sharing, bridged into the shared core (`IosSharer`). Shares a short message
/// plus a QR image generated from the contact payload (CoreImage `CIQRCodeGenerator`), so a recipient can
/// scan it or paste the message into Add Contact.
final class SharerBridge: NSObject, IosSharer {
    func share(message: String, qrPayload: String?) {
        DispatchQueue.main.async {
            var items: [Any] = [message]
            if let payload = qrPayload, let image = SharerBridge.makeQr(payload) {
                items.append(image)
            }
            let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)

            guard let root = SharerBridge.topViewController() else { return }
            // iPad requires a popover anchor.
            vc.popoverPresentationController?.sourceView = root.view
            vc.popoverPresentationController?.sourceRect = CGRect(
                x: root.view.bounds.midX, y: root.view.bounds.midY, width: 0, height: 0)
            vc.popoverPresentationController?.permittedArrowDirections = []
            root.present(vc, animated: true)
        }
    }

    private static func makeQr(_ string: String) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(string.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.first { $0.activationState == .foregroundActive } as? UIWindowScene
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
            ?? scene?.windows.first?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
