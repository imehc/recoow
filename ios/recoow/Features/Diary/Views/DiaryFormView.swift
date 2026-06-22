import CoreLocation
import SwiftUI
import UIKit

struct DiaryFormView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var content: String
    @State private var mood: DiaryMood
    @State private var selectedTags: [DiaryTagReference]
    @State private var occurredDate: Date
    @State private var selectedLinks: [DiaryLink]
    @State private var selectedAttachments: [MediaAttachment]
    @State private var presentedSheet: PresentedSheet?
    @State private var isShowingAttachmentPhotoPicker = false
    @State private var latitude: Double?
    @State private var longitude: Double?
    @State private var horizontalAccuracy: Double?
    @State private var isLocating = false
    @State private var locationErrorMessage: String?
    @State private var attachmentDragCoordinator = MediaAttachmentDragCoordinator()
    @State private var draftID: String
    @FocusState private var focusedField: String?

    let entry: DiaryEntry?
    let viewModel: DiaryViewModel

    private enum PresentedSheet: Identifiable {
        case source(DiaryLinkSourceType)
        case attachmentPreview(MediaAttachment)
        case tagSelection

        var id: String {
            switch self {
            case .source(let sourceType):
                "source-\(sourceType.rawValue)"
            case .attachmentPreview(let attachment):
                "attachment-\(attachment.id)"
            case .tagSelection:
                "tagSelection"
            }
        }
    }

    init(
        entry: DiaryEntry?,
        links: [DiaryLink],
        attachments: [MediaAttachment],
        viewModel: DiaryViewModel
    ) {
        self.entry = entry
        self.viewModel = viewModel
        _title = State(initialValue: entry?.title ?? "")
        _content = State(initialValue: entry?.content ?? "")
        _mood = State(initialValue: entry?.diaryMood ?? .calm)
        _selectedTags = State(initialValue: entry?.tagReferences ?? [])
        _occurredDate = State(initialValue: entry?.occurredDate ?? Date())
        _selectedLinks = State(initialValue: links)
        _selectedAttachments = State(initialValue: attachments)
        _latitude = State(initialValue: entry?.latitude)
        _longitude = State(initialValue: entry?.longitude)
        _horizontalAccuracy = State(initialValue: entry?.horizontalAccuracy)
        _draftID = State(initialValue: entry?.id ?? UUID().uuidString)
    }

    var body: some View {
        Form {
            Section(AppLocalization.string("内容")) {
                LabeledContent(AppLocalization.string("标题")) {
                    TextField(AppLocalization.string("请输入标题"), text: $title)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "title")
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text(AppLocalization.string("正文"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextEditor(text: $content)
                        .frame(minHeight: 180)
                        .focused($focusedField, equals: "content")
                }
            }

            MediaAttachmentInputSection(
                ownerType: .diary,
                ownerID: diaryID,
                deviceID: viewModel.deviceID,
                attachments: $selectedAttachments,
                dragCoordinator: attachmentDragCoordinator,
                onPhotoSourceRequest: showAttachmentPhotoPicker,
                onPreviewAttachment: showAttachmentPreview
            )

            Section(AppLocalization.string("记录信息")) {
                DatePicker(selection: $occurredDate, displayedComponents: [.date, .hourAndMinute]) {
                    FormInfoLabelView(title: "日期", systemImage: "calendar", tint: .blue)
                }

                Picker(selection: $mood) {
                    ForEach(DiaryMood.allCases) { mood in
                        Label(AppLocalization.string(mood.title), systemImage: mood.systemImage)
                            .tag(mood)
                    }
                } label: {
                    FormInfoLabelView(title: "心情", systemImage: mood.systemImage, tint: mood.tint)
                }

                tagInputRow

                locationInputRow
            }

            Section {
                if selectedLinks.isEmpty {
                    Text(AppLocalization.string("还没有关联记录"))
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(selectedLinks) { link in
                        DiaryLinkRow(link: link)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    removeLink(link)
                                } label: {
                                    Label(AppLocalization.string("移除"), systemImage: "minus.circle")
                                }
                                .tint(.red)
                        }
                    }
                }
            } header: {
                DiaryLinkSectionHeader(
                    selectedCount: selectedLinks.count,
                    onSelectSource: showLinkSelection
                )
            }
        }
        .overlay {
            MediaAttachmentDragLayer(coordinator: attachmentDragCoordinator)
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(AppLocalization.string(entry == nil ? "写日记" : "编辑日记"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalization.string("取消"), action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalization.string("保存"), action: save)
                    .disabled(isSaveDisabled)
            }

        }
        .sheet(item: $presentedSheet) { sheet in
            switch sheet {
            case .source(let sourceType):
                NavigationStack {
                    DiaryLinkSelectionView(
                        sourceType: sourceType,
                        diaryID: diaryID,
                        viewModel: viewModel,
                        selectedLinks: $selectedLinks
                    )
                }
                .presentationDragIndicator(.visible)
            case .attachmentPreview(let attachment):
                MediaAttachmentPreviewView(attachment: attachment)
            case .tagSelection:
                NavigationStack {
                    DiaryTagSelectionView(
                        viewModel: viewModel,
                        selectedTags: $selectedTags
                    )
                }
                .presentationDetents([
                    .height(
                        DiaryTagSelectionView.preferredPresentationHeight(
                            tagCount: viewModel.tags.count,
                            customSelectedCount: selectedTags.count
                        )
                    )
                ])
                .presentationDragIndicator(.visible)
            }
        }
        .fullScreenCover(isPresented: $isShowingAttachmentPhotoPicker) {
            PhotoSourcePickerView(
                onPhotoPicked: addPhotoAttachment,
                onClose: closeAttachmentPhotoPicker
            )
        }
    }

    private var diaryID: String {
        entry?.id ?? draftID
    }

    private var draftEntry: DiaryEntry {
        viewModel.makeEntry(
            title: normalizedTitle,
            content: normalizedContent,
            mood: mood,
            tags: normalizedSelectedTags,
            occurredDate: occurredDate,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy
        )
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedTitle: String {
        trimmedTitle.isEmpty ? AppLocalization.string("日记") : trimmedTitle
    }

    private var normalizedContent: String {
        content.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedSelectedTags: [DiaryTagReference] {
        var seenKeys = Set<String>()
        return selectedTags
            .map(viewModel.resolvedTagReference)
            .filter { reference in
                guard reference.key.isEmpty == false,
                      reference.value.isEmpty == false,
                      seenKeys.contains(reference.key) == false else {
                    return false
                }
                seenKeys.insert(reference.key)
                return true
            }
    }

    private var locationText: String? {
        guard let latitude, let longitude else { return nil }
        return AppFormatters.coordinate(latitude: latitude, longitude: longitude)
    }

    private var locationDetailText: String? {
        guard let horizontalAccuracy else { return nil }
        return AppLocalization.format("精度 %.0f 米", horizontalAccuracy)
    }

    private var isSaveDisabled: Bool {
        trimmedTitle.isEmpty && normalizedContent.isEmpty
    }

    @ViewBuilder
    private var tagInputRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                FormInfoLabelView(title: "标签", systemImage: "tag", tint: Color.accentColor)

                Spacer(minLength: 12)

                Button {
                    presentedSheet = .tagSelection
                } label: {
                    HStack(spacing: 4) {
                        Text(normalizedSelectedTags.isEmpty ? AppLocalization.string("选择标签") : AppLocalization.format("%d 个标签", normalizedSelectedTags.count))
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                    }
                    .font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }

            if normalizedSelectedTags.isEmpty == false {
                DiarySelectedTagChipsView(tags: normalizedSelectedTags, onRemove: removeTag)
                    .padding(.leading, FormInfoLabelView.contentLeadingInset)
            }
        }
    }

    @ViewBuilder
    private var locationInputRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                FormInfoLabelView(title: "位置", systemImage: "location", tint: .blue)

                Spacer(minLength: 12)

                if isLocating {
                    ProgressView()
                        .controlSize(.small)
                        .frame(width: AppDesign.touchIconSize, height: AppDesign.touchIconSize)
                } else {
                    FormRowIconButton(
                        systemImage: "location",
                        tint: .blue,
                        accessibilityLabel: locationText == nil ? AppLocalization.string("选择当前位置") : AppLocalization.string("更新位置"),
                        action: captureCurrentLocation
                    )
                }

                if locationText != nil {
                    FormRowIconButton(
                        systemImage: "xmark.circle.fill",
                        tint: .red,
                        accessibilityLabel: AppLocalization.string("移除位置"),
                        action: clearLocation
                    )
                }
            }

            if let locationText {
                VStack(alignment: .leading, spacing: 2) {
                    Text(locationText)
                        .lineLimit(1)
                        .truncationMode(.middle)

                    if let locationDetailText {
                        Text(locationDetailText)
                            .lineLimit(1)
                    }
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, FormInfoLabelView.contentLeadingInset)
            }

            if let locationErrorMessage {
                Text(locationErrorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .padding(.leading, FormInfoLabelView.contentLeadingInset)
            }
        }
    }

    private func cancel() {
        dismiss()
    }

    private func removeLink(_ link: DiaryLink) {
        selectedLinks.removeAll { $0.id == link.id }
    }

    private func removeTag(_ tag: DiaryTagReference) {
        selectedTags.removeAll { $0.key == tag.key }
    }

    private func showLinkSelection(_ sourceType: DiaryLinkSourceType) {
        presentedSheet = .source(sourceType)
    }

    private func showAttachmentPreview(_ attachment: MediaAttachment) {
        presentedSheet = .attachmentPreview(attachment)
    }

    private func captureCurrentLocation() {
        isLocating = true
        locationErrorMessage = nil

        Task {
            do {
                let location = try await container.locationService.currentLocation(accuracy: .tenMeters)
                latitude = location.coordinate.latitude
                longitude = location.coordinate.longitude
                horizontalAccuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
            } catch {
                locationErrorMessage = error.localizedDescription
            }

            isLocating = false
        }
    }

    private func clearLocation() {
        latitude = nil
        longitude = nil
        horizontalAccuracy = nil
        locationErrorMessage = nil
    }

    private func showAttachmentPhotoPicker() {
        isShowingAttachmentPhotoPicker = true
    }

    private func closeAttachmentPhotoPicker() {
        isShowingAttachmentPhotoPicker = false
    }

    private func addPhotoAttachment(_ data: Data) {
        let image = UIImage(data: data)
        selectedAttachments.append(
            MediaAttachment.makeNew(
                ownerType: .diary,
                ownerID: diaryID,
                kind: .photo,
                title: nil,
                data: data,
                mimeType: "image/jpeg",
                width: image.map { Int($0.size.width) },
                height: image.map { Int($0.size.height) },
                deviceID: viewModel.deviceID
            )
        )
    }

    private func save() {
        var record = entry ?? draftEntry
        record.id = diaryID
        record.title = normalizedTitle
        record.content = normalizedContent
        record.mood = mood.rawValue
        record.tagsJSON = DiaryEntry.encodeTags(normalizedSelectedTags)
        record.occurredAt = DiaryViewModel.milliseconds(for: occurredDate)
        record.latitude = latitude
        record.longitude = longitude
        record.horizontalAccuracy = horizontalAccuracy

        Task {
            if await viewModel.save(record, links: selectedLinks, attachments: selectedAttachments) {
                dismiss()
            }
        }
    }
}

private struct DiaryLinkSectionHeader: View {
    let selectedCount: Int
    let onSelectSource: (DiaryLinkSourceType) -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(AppLocalization.string("关联记录"))

            if selectedCount > 0 {
                Text(AppLocalization.format("%d 个关联", selectedCount))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 12)

            Menu {
                ForEach(DiaryLinkSourceRegistry.providers) { provider in
                    Button {
                        onSelectSource(provider.sourceType)
                    } label: {
                        Label(provider.sourceType.localizedTitle, systemImage: provider.sourceType.systemImage)
                    }
                }
            } label: {
                MetadataItemView(titleKey: "新增", systemImage: "plus.circle")
                    .font(.footnote.weight(.semibold))
            }
            .accessibilityLabel(AppLocalization.string("新增关联记录"))
        }
        .textCase(nil)
    }
}
