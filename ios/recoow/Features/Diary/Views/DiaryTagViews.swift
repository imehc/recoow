import SwiftUI
import UIKit

struct DiaryTagSelectionView: View {
    static let minimumPresentationHeight: CGFloat = 320

    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: DiaryViewModel
    @Binding var selectedTags: [DiaryTagReference]
    @State private var presentedSheet: Sheet?
    @State private var pendingDeletionTag: DiaryTag?
    @State private var isShowingDeletionConfirmation = false

    private enum Sheet: Identifiable {
        case newTag
        case editTag(DiaryTag)

        var id: String {
            switch self {
            case .newTag:
                "newTag"
            case .editTag(let tag):
                "editTag-\(tag.id)"
            }
        }
    }

    var body: some View {
        List {
            if viewModel.tags.isEmpty {
                ContentUnavailableView {
                    Label(AppLocalization.string("还没有标签"), systemImage: "tag")
                } description: {
                    Text(AppLocalization.string("添加标签后，可以为日记快速分类。"))
                } actions: {
                    Button(AppLocalization.string("添加标签"), systemImage: "plus", action: showNewTag)
                }
            } else {
                ForEach(viewModel.tags) { tag in
                    Button {
                        toggle(tag)
                    } label: {
                        DiaryTagSelectionRow(
                            tag: tag,
                            isSelected: selectedTags.contains(where: { $0.key == tag.id })
                        )
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            requestDelete(tag)
                        } label: {
                            Label(AppLocalization.string("删除"), systemImage: "trash")
                        }

                        Button {
                            presentedSheet = .editTag(tag)
                        } label: {
                            Label(AppLocalization.string("编辑"), systemImage: "square.and.pencil")
                        }
                        .tint(.blue)
                    }
                }
            }

            if customSelectedTags.isEmpty == false {
                Section(AppLocalization.string("已选自定义标签")) {
                    ForEach(customSelectedTags, id: \.self) { tag in
                        HStack {
                            Label(tag.value, systemImage: "tag")
                            Spacer()
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(AppLocalization.string("选择标签"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalization.string("完成"), action: close)
            }

            ToolbarItem(placement: .topBarTrailing) {
                Button(AppLocalization.string("添加标签"), systemImage: "plus", action: showNewTag)
            }
        }
        .presentationDetents([.height(preferredPresentationHeight)])
        .presentationDragIndicator(.visible)
        .sheet(item: $presentedSheet) { sheet in
            NavigationStack {
                switch sheet {
                case .newTag:
                    DiaryTagFormView(tag: nil, viewModel: viewModel)
                case .editTag(let tag):
                    DiaryTagFormView(tag: tag, viewModel: viewModel)
                }
            }
            .presentationDetents([.height(DiaryTagFormView.preferredPresentationHeight)])
            .presentationDragIndicator(.visible)
        }
        .alert(deletionTitle, isPresented: $isShowingDeletionConfirmation) {
            Button(AppLocalization.string("删除"), role: .destructive, action: confirmDelete)
            Button(AppLocalization.string("取消"), role: .cancel, action: clearPendingDeletion)
        } message: {
            Text(AppLocalization.string("删除标签不会删除已有日记记录。"))
        }
    }

    static func preferredPresentationHeight(tagCount: Int, customSelectedCount: Int) -> CGFloat {
        let maxHeight = max(Self.minimumPresentationHeight, UIScreen.main.bounds.height * 0.82)

        if tagCount == 0 {
            return min(maxHeight, 360)
        }

        let visibleTagRows = min(max(tagCount, 1), 7)
        let visibleCustomRows = min(customSelectedCount, 3)
        let customSectionHeight = visibleCustomRows > 0 ? 46 + CGFloat(visibleCustomRows) * 48 : 0
        let fittingHeight = 150 + CGFloat(visibleTagRows) * 56 + customSectionHeight

        return min(max(Self.minimumPresentationHeight, fittingHeight), maxHeight)
    }

    private var preferredPresentationHeight: CGFloat {
        Self.preferredPresentationHeight(
            tagCount: viewModel.tags.count,
            customSelectedCount: customSelectedTags.count
        )
    }

    private var customSelectedTags: [DiaryTagReference] {
        let managedKeys = Set(viewModel.tags.map(\.id))
        return selectedTags.map(viewModel.resolvedTagReference).filter { managedKeys.contains($0.key) == false }
    }

    private var deletionTitle: String {
        guard let pendingDeletionTag else {
            return AppLocalization.string("删除标签？")
        }

        return AppLocalization.format("删除“%@”？", pendingDeletionTag.name)
    }

    private func close() {
        dismiss()
    }

    private func showNewTag() {
        presentedSheet = .newTag
    }

    private func toggle(_ tag: DiaryTag) {
        let reference = viewModel.tagReference(for: tag)

        if selectedTags.contains(where: { $0.key == reference.key }) {
            selectedTags.removeAll { $0.key == reference.key }
        } else {
            selectedTags.append(reference)
        }
    }

    private func requestDelete(_ tag: DiaryTag) {
        pendingDeletionTag = tag
        isShowingDeletionConfirmation = true
    }

    private func confirmDelete() {
        guard let tag = pendingDeletionTag else { return }
        clearPendingDeletion()

        Task {
            await viewModel.deleteTag(id: tag.id)
        }
    }

    private func clearPendingDeletion() {
        pendingDeletionTag = nil
        isShowingDeletionConfirmation = false
    }
}

private struct DiaryTagSelectionRow: View {
    let tag: DiaryTag
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: isSelected ? "checkmark.circle.fill" : "tag")
                .foregroundStyle(Color.accentColor)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 4) {
                Text(tag.name)
                    .font(.headline)

                if let note = tag.note, note.isEmpty == false {
                    Text(note)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }

            Spacer(minLength: 12)

            if isSelected {
                Image(systemName: "checkmark")
                    .foregroundStyle(.tint)
            }
        }
        .contentShape(.rect)
        .padding(.vertical, 4)
    }
}

struct DiaryTagFormView: View {
    static let preferredPresentationHeight: CGFloat = 330

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var note: String
    @FocusState private var focusedField: String?

    let tag: DiaryTag?
    let viewModel: DiaryViewModel

    init(tag: DiaryTag?, viewModel: DiaryViewModel) {
        self.tag = tag
        self.viewModel = viewModel
        _name = State(initialValue: tag?.name ?? "")
        _note = State(initialValue: tag?.note ?? "")
    }

    var body: some View {
        Form {
            Section(AppLocalization.string("标签")) {
                LabeledContent(AppLocalization.string("名称")) {
                    TextField(AppLocalization.string("请输入名称"), text: $name)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "name")
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(AppLocalization.string("备注"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField(AppLocalization.string("请输入备注"), text: $note, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "note")
                }
            }
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(AppLocalization.string(tag == nil ? "添加标签" : "编辑标签"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalization.string("取消"), action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalization.string("保存"), action: save)
                    .disabled(trimmedName.isEmpty)
            }
        }
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedNote: String? {
        let value = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        var record = tag ?? viewModel.makeTag(name: trimmedName, note: normalizedNote)
        record.name = trimmedName
        record.note = normalizedNote

        Task {
            if await viewModel.save(record) {
                dismiss()
            }
        }
    }
}

struct DiarySelectedTagChipsView: View {
    let tags: [DiaryTagReference]
    var onRemove: ((DiaryTagReference) -> Void)?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(tags, id: \.self) { tag in
                    DiaryTagChipView(title: tag.value, onRemove: onRemove.map { remove in
                        { remove(tag) }
                    })
                }
            }
            .padding(.vertical, 2)
        }
    }
}

private struct DiaryTagChipView: View {
    let title: String
    var onRemove: (() -> Void)?

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "tag")
                .imageScale(.small)

            Text(title)
                .lineLimit(1)

            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark.circle.fill")
                        .imageScale(.small)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(AppLocalization.string("移除标签"))
            }
        }
        .font(.footnote.weight(.medium))
        .foregroundStyle(Color.accentColor)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.accentColor.opacity(0.12), in: Capsule())
        .overlay {
            Capsule()
                .strokeBorder(Color.accentColor.opacity(0.2), lineWidth: 1)
        }
    }
}
