import UIKit
import PhotosUI
import UniformTypeIdentifiers
import ComposeApp

/// Photo picker bridged into the Kotlin core (`IosImagePicker`) for attaching an image to a mesh post
/// (Roadmap #6c). Uses PHPickerViewController, which runs out-of-process — no photo-library permission
/// prompt needed. The picked asset is copied to a readable temp file; a ~90 px JPEG thumbnail (the same
/// in-band preview Android posts carry as `thumbnail_b64`) is scaled here where UIImage makes it trivial.
final class ImagePicker: NSObject, IosImagePicker, PHPickerViewControllerDelegate {
    private var onResult: ((String?, String?, KotlinLong, String?) -> Void)?

    func pick(onResult: @escaping (String?, String?, KotlinLong, String?) -> Void) {
        DispatchQueue.main.async {
            guard let root = Self.topViewController() else { onResult(nil, nil, KotlinLong(value: 0), nil); return }
            self.onResult = onResult
            var config = PHPickerConfiguration()
            config.filter = .images
            config.selectionLimit = 1
            let picker = PHPickerViewController(configuration: config)
            picker.delegate = self
            root.present(picker, animated: true)
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider else { finish(nil, nil, 0, nil); return }

        // Prefer the original file representation (keeps format + bytes exact); jpeg/png/etc.
        let typeId = provider.registeredTypeIdentifiers.first { id in
            UTType(id)?.conforms(to: .image) == true
        } ?? UTType.image.identifier

        provider.loadFileRepresentation(forTypeIdentifier: typeId) { [weak self] url, _ in
            guard let self else { return }
            guard let url else { self.finish(nil, nil, 0, nil); return }
            do {
                // The provider's URL dies when this closure returns — copy into our tmp dir first.
                let ext = url.pathExtension.isEmpty ? "jpg" : url.pathExtension.lowercased()
                let dest = FileManager.default.temporaryDirectory
                    .appendingPathComponent("noslop-picked-\(UUID().uuidString).\(ext)")
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.copyItem(at: url, to: dest)

                let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path)
                let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
                let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "image/jpeg"
                let thumb = Self.thumbnailB64(dest)
                self.finish(dest.path, mime, size, thumb)
            } catch {
                self.finish(nil, nil, 0, nil)
            }
        }
    }

    private func finish(_ path: String?, _ mime: String?, _ size: Int64, _ thumb: String?) {
        let cb = onResult
        onResult = nil
        // Kotlin/Native boxes the `Long` in the callback type as KotlinLong on the Swift side.
        DispatchQueue.main.async { cb?(path, mime, KotlinLong(value: size), thumb) }
    }

    /// ~90 px wide JPEG (quality 0.6), base64 — matches Android's `MediaManager.generateTinyThumbnail`.
    private static func thumbnailB64(_ url: URL) -> String? {
        guard let image = UIImage(contentsOfFile: url.path), image.size.width > 0 else { return nil }
        let width: CGFloat = 90
        let height = max(1, width * image.size.height / image.size.width)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
        let scaled = renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return scaled.jpegData(compressionQuality: 0.6)?.base64EncodedString()
    }

    private static func topViewController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
