import SwiftUI
import UIKit

extension View {
    /// 根页面使用上报版本，由 AppRoot 统一裁决 TabBar 是否显示，避免 push 后子页面被根页面强制改回可见。
    func reportsTabBarVisibilityWhenScrolling(_ visibility: Binding<Visibility>) -> some View {
        modifier(ScrollDrivenTabBarVisibilityReporter(visibility: visibility))
    }
}

private struct ScrollDrivenTabBarVisibilityReporter: ViewModifier {
    @Binding var visibility: Visibility

    func body(content: Content) -> some View {
        content
            .background(
                TabBarScrollObserver { visibility in
                    guard self.visibility != visibility else { return }

                    withAnimation(.easeInOut(duration: 0.22)) {
                        self.visibility = visibility
                    }
                }
            )
            .onAppear {
                visibility = .visible
            }
    }
}

private struct TabBarScrollObserver: UIViewRepresentable {
    var onVisibilityChange: (Visibility) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false

        DispatchQueue.main.async {
            context.coordinator.attachIfNeeded(from: view)
        }

        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onVisibilityChange = onVisibilityChange

        DispatchQueue.main.async {
            context.coordinator.attachIfNeeded(from: uiView)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onVisibilityChange: onVisibilityChange)
    }

    final class Coordinator {
        var onVisibilityChange: (Visibility) -> Void

        private weak var scrollView: UIScrollView?
        private weak var hostView: UIView?
        private var observation: NSKeyValueObservation?
        private var lastOffsetY: CGFloat?
        private var lastVisibility: Visibility = .visible
        private var lastVisibilityChangeTime: TimeInterval = 0
        private var pendingAttachRetryCount = 0

        // 过滤 List/Form 自身的微小回弹、底部安全区变化和布局抖动，避免 TabBar 频繁闪烁。
        private let directionThreshold: CGFloat = 14
        private let topTolerance: CGFloat = 6
        private let bottomTolerance: CGFloat = 36
        private let minimumVisibilityChangeInterval: TimeInterval = 0.22
        private let maxAttachRetryCount = 8

        init(onVisibilityChange: @escaping (Visibility) -> Void) {
            self.onVisibilityChange = onVisibilityChange
        }

        func attachIfNeeded(from view: UIView) {
            hostView = view

            guard let foundScrollView = nearestScrollView(around: view) else {
                scheduleAttachRetry()
                return
            }

            guard foundScrollView !== scrollView else { return }

            observation?.invalidate()
            scrollView = foundScrollView
            lastOffsetY = normalizedOffsetY(for: foundScrollView)
            sendVisibility(.visible, force: true)

            observation = foundScrollView.observe(\.contentOffset, options: [.new]) { [weak self] scrollView, _ in
                DispatchQueue.main.async {
                    self?.handleScroll(in: scrollView)
                }
            }
        }

        private func scheduleAttachRetry() {
            guard pendingAttachRetryCount < maxAttachRetryCount else { return }

            pendingAttachRetryCount += 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                guard let self, let hostView else { return }
                attachIfNeeded(from: hostView)
            }
        }

        private func handleScroll(in scrollView: UIScrollView) {
            let currentOffsetY = normalizedOffsetY(for: scrollView)

            guard isScrollable(scrollView) else {
                lastOffsetY = currentOffsetY
                sendVisibility(.visible, force: true)
                return
            }

            guard currentOffsetY > topTolerance else {
                lastOffsetY = currentOffsetY
                sendVisibility(.visible, force: true)
                return
            }

            guard isNearBottom(currentOffsetY, in: scrollView) == false else {
                lastOffsetY = currentOffsetY
                return
            }

            guard let lastOffsetY else {
                self.lastOffsetY = currentOffsetY
                return
            }

            let delta = currentOffsetY - lastOffsetY
            guard abs(delta) >= directionThreshold else { return }

            sendVisibility(delta > 0 ? .hidden : .visible)
            self.lastOffsetY = currentOffsetY
        }

        private func sendVisibility(_ visibility: Visibility, force: Bool = false) {
            guard lastVisibility != visibility else { return }

            let now = Date().timeIntervalSinceReferenceDate
            guard force || now - lastVisibilityChangeTime >= minimumVisibilityChangeInterval else { return }

            lastVisibility = visibility
            lastVisibilityChangeTime = now
            onVisibilityChange(visibility)
        }

        private func normalizedOffsetY(for scrollView: UIScrollView) -> CGFloat {
            scrollView.contentOffset.y + scrollView.adjustedContentInset.top
        }

        private func isScrollable(_ scrollView: UIScrollView) -> Bool {
            scrollView.contentSize.height > scrollView.bounds.height + scrollView.adjustedContentInset.top + scrollView.adjustedContentInset.bottom + 1
        }

        private func isNearBottom(_ offsetY: CGFloat, in scrollView: UIScrollView) -> Bool {
            offsetY >= maxScrollableOffsetY(for: scrollView) - bottomTolerance
        }

        private func maxScrollableOffsetY(for scrollView: UIScrollView) -> CGFloat {
            max(
                0,
                scrollView.contentSize.height
                    - scrollView.bounds.height
                    + scrollView.adjustedContentInset.top
                    + scrollView.adjustedContentInset.bottom
            )
        }

        /// SwiftUI 的 List/Form 内部是 UIKit 滚动视图；这里从承载视图附近查找最近的 UIScrollView，避免改动列表内容结构。
        private func nearestScrollView(around view: UIView) -> UIScrollView? {
            var current: UIView? = view

            while let candidate = current {
                if let scrollView = candidate as? UIScrollView {
                    return scrollView
                }

                if let scrollView = firstScrollView(in: candidate) {
                    return scrollView
                }

                current = candidate.superview
            }

            return nil
        }

        private func firstScrollView(in view: UIView) -> UIScrollView? {
            if let scrollView = view as? UIScrollView {
                return scrollView
            }

            for subview in view.subviews {
                if let scrollView = firstScrollView(in: subview) {
                    return scrollView
                }
            }

            return nil
        }
    }
}
