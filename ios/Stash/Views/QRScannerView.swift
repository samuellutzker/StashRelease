import AVFoundation
import SwiftUI

/// A SwiftUI wrapper around AVCaptureSession that scans QR codes.
/// Call the `onScan` closure with the raw string when a code is found.
struct QRScannerView: UIViewRepresentable {
    var onScan: (String) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        let session = AVCaptureSession()

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            return view
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(context.coordinator, queue: .main)
            output.metadataObjectTypes = [.qr]
        }

        view.session = session
        DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onScan: onScan)
    }

    // MARK: - Coordinator

    class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let onScan: (String) -> Void
        private var didScan = false

        init(onScan: @escaping (String) -> Void) {
            self.onScan = onScan
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput objects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !didScan,
                  let obj = objects.first as? AVMetadataMachineReadableCodeObject,
                  let value = obj.stringValue else { return }
            didScan = true
            onScan(value)
        }
    }

    // MARK: - PreviewView

    class PreviewView: UIView {
        var session: AVCaptureSession? {
            didSet {
                guard let session else { return }
                (layer as! AVCaptureVideoPreviewLayer).session = session
            }
        }

        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        override func layoutSubviews() {
            super.layoutSubviews()
            (layer as! AVCaptureVideoPreviewLayer).videoGravity = .resizeAspectFill
        }
    }
}

// MARK: - Sheet wrapper

struct QRScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    var onResult: (String) -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                QRScannerView { value in
                    onResult(value)
                    dismiss()
                }
            }
            .navigationTitle("Scan QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(.white)
                }
            }
        }
    }
}
