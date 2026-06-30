import SwiftUI
import UIKit

struct PhotoEditorView: View {
    @State private var scale: CGFloat = 1
    @State private var scaleBeforeGesture: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var offsetBeforeDrag: CGSize = .zero
    @State private var rotationDegrees = 0.0

    let image: UIImage
    let onCancel: () -> Void
    let onSave: (Data) -> Void

    private let minimumScale: CGFloat = 1
    private let maximumScale: CGFloat = 4

    var body: some View {
        GeometryReader { proxy in
            let cropLength = cropLength(for: proxy.size)

            VStack(spacing: 20) {
                Spacer(minLength: 12)

                ZStack {
                    Color(.secondarySystemGroupedBackground)

                    Image(uiImage: image.normalizedForEditing)
                        .resizable()
                        .scaledToFill()
                        .frame(width: cropLength, height: cropLength)
                        .scaleEffect(scale)
                        .rotationEffect(.degrees(rotationDegrees))
                        .offset(offset)
                        .gesture(dragGesture)
                        .simultaneousGesture(magnificationGesture)

                    PhotoCropGuideView()
                        .overlay {
                            Rectangle()
                                .stroke(.white, lineWidth: 2)
                        }
                }
                .frame(width: cropLength, height: cropLength)
                .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))
                .overlay {
                    RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                        .stroke(.separator, lineWidth: 1)
                }

                VStack(alignment: .leading, spacing: 14) {
                    Text("调整")
                        .font(.headline)

                    HStack(spacing: 12) {
                        Image(systemName: "minus.magnifyingglass")
                            .foregroundStyle(.secondary)

                        Slider(
                            value: Binding(
                                get: { scale },
                                set: { newValue in
                                    scale = newValue
                                    scaleBeforeGesture = newValue
                                }
                            ),
                            in: minimumScale...maximumScale
                        )

                        Image(systemName: "plus.magnifyingglass")
                            .foregroundStyle(.secondary)
                    }

                    HStack(spacing: 12) {
                        Image(systemName: "rotate.right")
                            .foregroundStyle(.secondary)

                        Slider(value: $rotationDegrees, in: -180...180)

                        Text(AppLocalization.format("%d度", Int(rotationDegrees.rounded())))
                            .font(.footnote.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .frame(width: 52, alignment: .trailing)
                    }

                    Button("重置", systemImage: "arrow.counterclockwise", action: reset)
                        .buttonStyle(.bordered)
                }
                .padding()
                .background(.background, in: .rect(cornerRadius: AppDesign.cornerRadius))
                .overlay {
                    RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                        .stroke(.separator, lineWidth: 1)
                }
                .padding(.horizontal)
                .padding(.bottom, 16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemGroupedBackground))
            .navigationTitle("编辑图片")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .cancel, action: onCancel) {
                        Label("取消", systemImage: "xmark")
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(action: { save(cropLength: cropLength) }) {
                        Label("完成", systemImage: "checkmark")
                    }
                }
            }
        }
    }

    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                offset = CGSize(
                    width: offsetBeforeDrag.width + value.translation.width,
                    height: offsetBeforeDrag.height + value.translation.height
                )
            }
            .onEnded { _ in
                offsetBeforeDrag = offset
            }
    }

    private var magnificationGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(maximumScale, max(minimumScale, scaleBeforeGesture * value))
            }
            .onEnded { _ in
                scaleBeforeGesture = scale
            }
    }

    private func cropLength(for size: CGSize) -> CGFloat {
        max(160, min(size.width - 32, size.height - 190))
    }

    private func reset() {
        scale = minimumScale
        scaleBeforeGesture = minimumScale
        offset = .zero
        offsetBeforeDrag = .zero
        rotationDegrees = 0
    }

    private func save(cropLength: CGFloat) {
        let editedImage = PhotoEditorRenderer.render(
            image: image,
            cropLength: cropLength,
            scale: scale,
            offset: offset,
            rotationDegrees: rotationDegrees
        )

        guard let data = PhotoStorageOptimizer.normalizedJPEGData(from: editedImage) else { return }
        onSave(data)
    }
}
