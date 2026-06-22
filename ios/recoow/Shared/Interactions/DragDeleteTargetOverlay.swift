import SwiftUI

struct DragDeleteTargetState: Equatable {
    var isActive = false
    var isTargeted = false
    var targetFrame = CGRect.zero
}

struct DragDeleteTargetOverlay: View {
    @Binding var state: DragDeleteTargetState
    var normalTitle = "拖动到此处删除"
    var targetedTitle = "松手即可删除"

    var body: some View {
        Group {
            if state.isActive {
                VStack(spacing: 8) {
                    Image(systemName: state.isTargeted ? "trash.fill" : "trash")
                        .font(.title2.weight(.semibold))

                    Text(titleText)
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 92)
                .padding(.bottom, 8)
                .background(Color(.systemRed).opacity(state.isTargeted ? 0.96 : 0.86))
                .background(frameReader)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityLabel(titleText)
            }
        }
        .allowsHitTesting(false)
        .ignoresSafeArea(edges: .bottom)
        .animation(.spring(response: 0.22, dampingFraction: 0.9), value: state.isActive)
        .animation(.easeOut(duration: 0.12), value: state.isTargeted)
    }

    private var titleText: String {
        AppLocalization.string(state.isTargeted ? targetedTitle : normalTitle)
    }

    private var frameReader: some View {
        GeometryReader { proxy in
            Color.clear.preference(
                key: DragDeleteTargetFramePreferenceKey.self,
                value: proxy.frame(in: .global)
            )
        }
        .onPreferenceChange(DragDeleteTargetFramePreferenceKey.self) { frame in
            guard state.targetFrame != frame else { return }
            state.targetFrame = frame
        }
    }
}

private struct DragDeleteTargetFramePreferenceKey: PreferenceKey {
    static var defaultValue: CGRect = .zero

    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        value = nextValue()
    }
}
