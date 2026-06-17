import SwiftUI

struct DecisionOptionFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var detail: String
    @State private var customInfo: String
    @State private var imageData: Data?
    @State private var weight: Int
    @State private var isEnabled: Bool

    let option: DecisionOption?
    let viewModel: DecisionOptionsViewModel

    init(option: DecisionOption?, viewModel: DecisionOptionsViewModel) {
        self.option = option
        self.viewModel = viewModel
        _title = State(initialValue: option?.title ?? "")
        _detail = State(initialValue: option?.detail ?? "")
        _customInfo = State(initialValue: option?.customInfo ?? "")
        _imageData = State(initialValue: option?.imageData)
        _weight = State(initialValue: option?.weight ?? 1)
        _isEnabled = State(initialValue: option?.isEnabled ?? true)
    }

    var body: some View {
        Form {
            Section("基础信息") {
                TextField("标题", text: $title)

                TextField("描述", text: $detail, axis: .vertical)
                    .lineLimit(3...)

                TextField("自定义信息", text: $customInfo, axis: .vertical)
                    .lineLimit(3...)
            }

            PhotoInputSection(imageData: $imageData, placeholderSystemImage: "questionmark.circle")

            Section("随机设置") {
                Stepper(value: $weight, in: 1...100) {
                    LabeledContent("权重", value: "\(weight)")
                }

                Toggle("启用", isOn: $isEnabled)
            }
        }
        .navigationTitle(option == nil ? "添加选项" : "编辑选项")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存", action: save)
                    .disabled(trimmedTitle.isEmpty)
            }
        }
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedDetail: String? {
        let value = detail.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedCustomInfo: String? {
        let value = customInfo.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        var record = option ?? viewModel.makeOption(
            title: trimmedTitle,
            detail: normalizedDetail,
            customInfo: normalizedCustomInfo,
            imageData: imageData,
            weight: weight,
            isEnabled: isEnabled
        )
        record.title = trimmedTitle
        record.detail = normalizedDetail
        record.customInfo = normalizedCustomInfo
        record.imageData = imageData
        record.weight = weight
        record.isEnabled = isEnabled

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }
}
