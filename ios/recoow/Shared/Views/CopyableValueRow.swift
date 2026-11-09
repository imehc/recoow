import SwiftUI

/// 信息值与复制操作共用一行，保留原生信息行的高度与按钮点击范围。
struct CopyableValueRow: View {
    let title: String
    let value: String
    let copyLabel: String
    let copiedLabel: String
    @State private var didCopy = false

    var body: some View {
        LabeledContent(title) {
            Text(value)
                .foregroundStyle(AppPalette.secondaryInk)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.trailing, AppDesign.touchIconSize)
                .overlay(alignment: .trailing) {
                    FormRowIconButton(
                        systemImage: didCopy ? "checkmark" : "doc.on.doc",
                        tint: AppPalette.accent,
                        accessibilityLabel: didCopy ? copiedLabel : copyLabel
                    ) {
                        UIPasteboard.general.string = value
                        didCopy = true
                    }
                }
        }
        .sensoryFeedback(.success, trigger: didCopy) { _, copied in copied }
        .task(id: didCopy) {
            guard didCopy else { return }
            do {
                try await Task.sleep(for: .seconds(2))
                didCopy = false
            } catch {}
        }
        .onChange(of: value) { didCopy = false }
    }
}
