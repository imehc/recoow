import SwiftUI
import UIKit

struct PhotoPreviewView: View {
    @Environment(\.dismiss) private var dismiss

    private let items: [PhotoPreviewItem]
    @State private var selectedID: String

    init(item: PhotoPreviewItem) {
        self.init(items: [item], initialID: item.id)
    }

    init(items: [PhotoPreviewItem], initialID: String? = nil) {
        self.items = items
        let selectedID = initialID.flatMap { id in
            items.contains { $0.id == id } ? id : nil
        } ?? items.first?.id ?? UUID().uuidString
        _selectedID = State(initialValue: selectedID)
    }

    var body: some View {
        ZStack(alignment: .top) {
            Color.black
                .ignoresSafeArea()

            if items.isEmpty {
                unavailableView
            } else {
                TabView(selection: $selectedID) {
                    ForEach(items) { item in
                        ZoomablePhotoPreviewPage(item: item, onClose: close)
                            .tag(item.id)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .ignoresSafeArea()
            }

            previewChrome
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(true)
    }

    private var unavailableView: some View {
        VStack(spacing: 12) {
            Image(systemName: "photo")
                .font(.largeTitle)
                .accessibilityHidden(true)

            Text(AppLocalization.string("无法预览图片"))
                .font(.headline)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var previewChrome: some View {
        HStack(spacing: 12) {
            if items.count > 1 {
                Text("\(selectedIndex + 1)/\(items.count)")
                    .font(.subheadline.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(.black.opacity(0.42), in: Capsule())
                    .accessibilityLabel(AppLocalization.format("第 %d 张，共 %d 张", selectedIndex + 1, items.count))
            }

            Spacer(minLength: 8)

            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: AppDesign.touchIconSize, height: AppDesign.touchIconSize)
                    .background(.black.opacity(0.42), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(AppLocalization.string("完成"))
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
    }

    private var selectedIndex: Int {
        items.firstIndex { $0.id == selectedID } ?? 0
    }

    private func close() {
        dismiss()
    }
}

private struct ZoomablePhotoPreviewPage: View {
    let item: PhotoPreviewItem
    let onClose: () -> Void

    @State private var baseScale: CGFloat = 1
    @State private var baseOffset: CGSize = .zero
    @GestureState private var gestureScale: CGFloat = 1
    @GestureState private var gestureOffset: CGSize = .zero

    private let maxScale: CGFloat = 4
    private let doubleTapScale: CGFloat = 2.35

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.clear
                    .contentShape(Rectangle())

                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .scaleEffect(currentScale)
                        .offset(currentOffset(in: proxy.size))
                        .animation(.interactiveSpring(response: 0.24, dampingFraction: 0.88), value: baseScale)
                        .animation(.interactiveSpring(response: 0.24, dampingFraction: 0.88), value: baseOffset)
                        .accessibilityLabel(item.title ?? AppLocalization.string("图片预览"))
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "photo")
                            .font(.largeTitle)
                            .accessibilityHidden(true)

                        Text(AppLocalization.string("无法预览图片"))
                            .font(.headline)
                    }
                    .foregroundStyle(.white)
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
            .contentShape(Rectangle())
            .simultaneousGesture(magnifyGesture(in: proxy.size))
            .simultaneousGesture(dragGesture(in: proxy.size))
            .onTapGesture(count: 2) {
                toggleZoom(in: proxy.size)
            }
        }
    }

    private var image: UIImage? {
        UIImage(data: item.imageData)
    }

    private var currentScale: CGFloat {
        clampScale(baseScale * gestureScale)
    }

    private func currentOffset(in size: CGSize) -> CGSize {
        clampedOffset(
            CGSize(
                width: baseOffset.width + gestureOffset.width,
                height: baseOffset.height + gestureOffset.height
            ),
            in: size,
            scale: currentScale
        )
    }

    private func magnifyGesture(in size: CGSize) -> some Gesture {
        MagnifyGesture()
            .updating($gestureScale) { value, state, _ in
                state = value.magnification
            }
            .onEnded { value in
                let nextScale = clampScale(baseScale * value.magnification)

                withAnimation(.interactiveSpring(response: 0.24, dampingFraction: 0.88)) {
                    baseScale = nextScale
                    baseOffset = nextScale <= 1.01 ? .zero : clampedOffset(baseOffset, in: size, scale: nextScale)
                }
            }
    }

    private func dragGesture(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .updating($gestureOffset) { value, state, _ in
                guard currentScale > 1.01 else { return }
                state = value.translation
            }
            .onEnded { value in
                if currentScale > 1.01 {
                    let nextOffset = CGSize(
                        width: baseOffset.width + value.translation.width,
                        height: baseOffset.height + value.translation.height
                    )

                    withAnimation(.interactiveSpring(response: 0.24, dampingFraction: 0.88)) {
                        baseOffset = clampedOffset(nextOffset, in: size, scale: currentScale)
                    }
                } else if shouldClose(for: value.translation) {
                    onClose()
                }
            }
    }

    private func shouldClose(for translation: CGSize) -> Bool {
        translation.height > 120 && abs(translation.height) > abs(translation.width) * 1.35
    }

    private func toggleZoom(in size: CGSize) {
        withAnimation(.interactiveSpring(response: 0.24, dampingFraction: 0.88)) {
            if baseScale > 1.01 {
                baseScale = 1
                baseOffset = .zero
            } else {
                baseScale = doubleTapScale
                baseOffset = clampedOffset(.zero, in: size, scale: doubleTapScale)
            }
        }
    }

    private func clampScale(_ scale: CGFloat) -> CGFloat {
        min(max(scale, 1), maxScale)
    }

    private func clampedOffset(_ offset: CGSize, in size: CGSize, scale: CGFloat) -> CGSize {
        guard scale > 1 else { return .zero }

        let maxX = size.width * (scale - 1) / 2
        let maxY = size.height * (scale - 1) / 2
        return CGSize(
            width: min(max(offset.width, -maxX), maxX),
            height: min(max(offset.height, -maxY), maxY)
        )
    }
}
