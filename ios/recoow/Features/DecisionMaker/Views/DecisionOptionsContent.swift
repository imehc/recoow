import SwiftUI

struct DecisionOptionsContent: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Bindable var viewModel: DecisionOptionsViewModel
    @State private var scene = DecisionSceneController()
    @State private var isVisible = false
    @State private var isDrawerExpanded = false
    @State private var drawerTab = DrawerTab.options
    @State private var presentedSheet: Sheet?
    @State private var pendingDeletionOption: DecisionOption?
    @State private var pendingDeletionRecord: DecisionChoiceRecord?
    @State private var isShowingDeletionConfirmation = false
    @State private var isShowingHistoryDeletionConfirmation = false
    let choiceRecordImageTransition: Namespace.ID

    private enum DrawerTab { case options, history }

    private enum Sheet: Identifiable {
        case newOption
        case editOption(DecisionOption)
        case details(DecisionOption, isResult: Bool)

        var id: String {
            switch self {
            case .newOption:
                "newOption"
            case .editOption(let option):
                "editOption-\(option.id)"
            case .details(let option, _):
                "details-\(option.id)"
            }
        }
    }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                if let errorMessage = viewModel.errorMessage {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .font(AppDesign.rowSubtitleFont)
                        .foregroundStyle(AppPalette.red)
                        .padding(.horizontal, AppDesign.pagePadding)
                        .padding(.top, AppDesign.pageTopPadding)
                }

                sceneContent

                candidateDrawer
                    .frame(height: isDrawerExpanded ? geometry.size.height * (dynamicTypeSize.isAccessibilitySize ? 0.55 : 0.48) : nil)
                    .padding(.horizontal, AppDesign.pagePadding)
                    .padding(.bottom, AppDesign.pageTopPadding)
            }
        }
        .appPageStyle()
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("历史记录", systemImage: "clock") {
                    drawerTab = .history
                    setDrawerExpanded(true)
                }
                .accessibilityIdentifier("decision.history")
                .disabled(scene.isBusy)

                Button("添加选项", systemImage: "plus", action: showNewOption)
                    .accessibilityIdentifier("decision.add")
                    .disabled(scene.isBusy)
            }
        }
        .appSheet(item: $presentedSheet) { sheet in
            NavigationStack {
                switch sheet {
                case .newOption:
                    DecisionOptionFormView(option: nil, viewModel: viewModel)
                case .editOption(let option):
                    DecisionOptionFormView(option: option, viewModel: viewModel)
                case .details(let option, let isResult):
                    ScrollView {
                        DecisionResultView(option: option)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(AppDesign.pagePadding)
                    }
                    .appPageStyle()
                    .navigationTitle(AppLocalization.string(isResult ? "结果" : "候选项详情"))
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("完成") { presentedSheet = nil }
                        }
                    }
                }
            }
        }
        .alert(deletionTitle, isPresented: $isShowingDeletionConfirmation) {
            Button("删除", role: .destructive, action: confirmDelete)
            Button("取消", role: .cancel, action: clearPendingDeletion)
        } message: {
            Text("删除后该候选项会从当前集合中移除。")
        }
        .alert(historyDeletionTitle, isPresented: $isShowingHistoryDeletionConfirmation) {
            Button("删除", role: .destructive, action: confirmDeleteHistory)
            Button("取消", role: .cancel, action: clearPendingHistoryDeletion)
        } message: {
            Text("删除后这条选择结果会从历史记录中移除。")
        }
        .onAppear {
            isVisible = true
            scene.updateOptions(viewModel.enabledOptions)
        }
        .onDisappear { isVisible = false }
        .onChange(of: viewModel.options) { _, _ in
            scene.updateOptions(viewModel.enabledOptions)
        }
    }

    private var sceneContent: some View {
        VStack(spacing: 0) {
            Text(AppLocalization.string(sceneStatus))
                .font(AppDesign.rowSubtitleFont)
                .foregroundStyle(AppPalette.secondaryInk)
                .multilineTextAlignment(.center)
                .padding(.horizontal, AppDesign.pagePadding)
                .padding(.top, AppDesign.pageTopPadding)
                .accessibilityIdentifier("decision.scene.status")

            ZStack {
                DecisionSceneView(
                    controller: scene,
                    isActive: isVisible && scenePhase == .active && presentedSheet == nil,
                    onDraw: chooseRandom
                )
                if scene.isLoading {
                    ProgressView("正在准备选择盘")
                        .font(AppDesign.rowSubtitleFont)
                        .padding(AppDesign.pagePadding)
                        .background(AppPalette.background, in: RoundedRectangle(cornerRadius: AppDesign.cornerRadius))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if viewModel.enabledOptions.isEmpty {
                Button("添加选项", systemImage: "plus", action: showNewOption)
                    .frame(minHeight: AppDesign.touchIconSize)
                    .padding(.bottom, AppDesign.pageTopPadding)
            }
        }
    }

    private var sceneStatus: String {
        if scene.isSceneUnavailable { return "暂时无法显示选择盘，仍可抽选" }
        if scene.isLoading { return "拖动浏览 · 轻甩或点中心抽选" }
        switch scene.phase {
        case .preparing, .spinning: return "正在抽选"
        case .slowing: return "慢慢停下…"
        case .revealing, .result: return "已选好，点中心可以再选一次"
        case .browsing:
            if viewModel.enabledOptions.isEmpty { return "添加或启用候选项后即可抽选" }
            if viewModel.enabledOptions.count == 1 { return "当前只有一个候选项，点中心选定" }
            return "拖动浏览 · 轻甩或点中心抽选"
        }
    }

    private var candidateDrawer: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: AppDesign.gridSpacing) {
                Button {
                    guard let option = scene.focusedOption else { return }
                    presentedSheet = .details(option, isResult: scene.resultOption?.id == option.id)
                } label: {
                    HStack(spacing: AppDesign.gridSpacing) {
                        if let option = scene.focusedOption, option.hasImage, !scene.isBusy {
                            PhotoThumbnailView(imageData: option.resolvedImageData, systemImage: "questionmark.circle")
                        }
                        VStack(alignment: .leading, spacing: AppDesign.metadataItemSpacing) {
                            Text(AppLocalization.string(scene.isBusy ? "正在经过" : (scene.resultOption == nil ? "当前浏览" : "本次结果")))
                                .font(AppDesign.metadataFont)
                                .foregroundStyle(AppPalette.secondaryInk)
                            Text(scene.focusedOption?.title ?? AppLocalization.string("还没有候选项"))
                                .font(AppDesign.rowTitleFont)
                                .foregroundStyle(AppPalette.ink)
                                .multilineTextAlignment(.leading)
                                .lineLimit(2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if scene.focusedOption != nil && !scene.isBusy {
                            Image(systemName: "info.circle")
                                .foregroundStyle(AppPalette.secondaryInk)
                                .accessibilityHidden(true)
                        }
                    }
                    .frame(minHeight: AppDesign.touchIconSize)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(scene.focusedOption == nil || scene.isBusy)
                .accessibilityHint("查看完整说明和照片")
                .accessibilityIdentifier("decision.focus")

                Button {
                    setDrawerExpanded(!isDrawerExpanded)
                } label: {
                    HStack(spacing: AppDesign.metadataItemSpacing) {
                        Text(AppLocalization.format("候选项 %d", viewModel.options.count))
                            .font(AppDesign.metadataFont)
                            .foregroundStyle(AppPalette.ink)
                            .lineLimit(1)
                        Image(systemName: isDrawerExpanded ? "chevron.down" : "chevron.up")
                            .font(AppDesign.metadataFont.weight(.medium))
                    }
                    .fixedSize(horizontal: true, vertical: false)
                    .frame(minWidth: AppDesign.touchIconSize, minHeight: AppDesign.touchIconSize)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(scene.isBusy)
                .accessibilityLabel(AppLocalization.string(isDrawerExpanded ? "收起候选项" : "展开候选项"))
                .accessibilityValue(AppLocalization.format("共 %d 项，%d 项参与抽选", viewModel.options.count, viewModel.enabledOptions.count))
                .accessibilityIdentifier("decision.drawer.toggle")
            }
            .padding(AppDesign.pagePadding)
            .simultaneousGesture(DragGesture(minimumDistance: 20).onEnded { value in
                if abs(value.translation.height) > abs(value.translation.width) {
                    setDrawerExpanded(value.translation.height < 0)
                }
            })

            if isDrawerExpanded {
                Divider()
                Picker("候选项与历史", selection: $drawerTab) {
                    Text("候选项").tag(DrawerTab.options)
                    Text("历史记录").tag(DrawerTab.history)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, AppDesign.pagePadding)
                .padding(.vertical, AppDesign.gridSpacing)

                List {
                    if drawerTab == .options { candidateRows } else { historyRows }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .contentMargins(.top, 0, for: .scrollContent)
                .environment(\.defaultMinListRowHeight, AppDesign.touchIconSize)
                .accessibilityIdentifier("decision.drawer.list")
            }
        }
        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: AppDesign.sheetCornerRadius))
        .clipShape(RoundedRectangle(cornerRadius: AppDesign.sheetCornerRadius))
    }

    private var candidateRows: some View {
        Section {
            if viewModel.options.isEmpty {
                Button("添加选项", systemImage: "plus", action: showNewOption)
            } else {
                ForEach(viewModel.options) { option in
                    Button {
                        if option.isEnabled {
                            scene.browse(option)
                        } else {
                            presentedSheet = .details(option, isResult: false)
                        }
                    } label: {
                        HStack(spacing: AppDesign.gridSpacing) {
                            DecisionOptionRow(option: option)
                            Spacer(minLength: 0)
                            if scene.focusedOption?.id == option.id {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(AppPalette.accent)
                                    .accessibilityLabel("正在查看")
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(scene.isBusy)
                    .swipeActions(edge: .trailing) {
                        Button { requestDelete(option) } label: {
                            Label("删除", systemImage: "trash")
                        }
                        .tint(.red)

                        Button { presentedSheet = .editOption(option) } label: {
                            Label("编辑", systemImage: "square.and.pencil")
                        }
                        .tint(AppPalette.accent)
                    }
                    .contextMenu {
                        Button("编辑", systemImage: "square.and.pencil") { presentedSheet = .editOption(option) }
                        Button("删除", systemImage: "trash", role: .destructive) { requestDelete(option) }
                    }
                }
            }
        } footer: {
            Text("只会从启用的候选项中随机，权重越高越容易被选中。")
                .font(AppDesign.metadataFont)
        }
        .listRowBackground(AppPalette.surface)
    }

    private var historyRows: some View {
        Section {
            if viewModel.choiceRecords.isEmpty {
                Text("暂无选择记录").foregroundStyle(AppPalette.secondaryInk)
            } else {
                ForEach(viewModel.choiceRecords.prefix(10)) { record in
                    NavigationLink(value: DecisionChoiceRecordRoute(id: record.id)) {
                        DecisionChoiceRecordRow(
                            record: record,
                            showsCollectionTitle: false,
                            choiceRecordImageTransition: choiceRecordImageTransition
                        )
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button { requestDeleteHistory(record) } label: {
                            Label("删除", systemImage: "trash")
                        }
                        .tint(.red)
                    }
                }
            }
        }
        .listRowBackground(AppPalette.surface)
    }

    private func setDrawerExpanded(_ expanded: Bool) {
        guard !scene.isBusy else { return }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.24)) {
            isDrawerExpanded = expanded
        }
    }

    private var deletionTitle: String {
        guard let option = pendingDeletionOption else {
            return "删除选项？"
        }

        return "删除“\(option.title)”？"
    }

    private func showNewOption() {
        presentedSheet = .newOption
    }

    private func chooseRandom(_ input: DecisionSpinInput) {
        guard scene.prepareDraw() else { return }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.24)) {
            isDrawerExpanded = false
        }
        Task {
            await viewModel.chooseRandomOption { option in
                scene.animateSelection(option, input: input)
            }
            scene.cancelPreparation()
        }
    }

    private func requestDelete(_ option: DecisionOption) {
        pendingDeletionOption = option
        isShowingDeletionConfirmation = true
    }

    private func confirmDelete() {
        guard let option = pendingDeletionOption else { return }
        clearPendingDeletion()

        Task {
            await viewModel.delete(id: option.id)
        }
    }

    private func clearPendingDeletion() {
        pendingDeletionOption = nil
        isShowingDeletionConfirmation = false
    }

    private var historyDeletionTitle: String {
        guard let record = pendingDeletionRecord else {
            return "删除选择记录？"
        }

        return "删除“\(record.optionTitle)”？"
    }

    private func requestDeleteHistory(_ record: DecisionChoiceRecord) {
        pendingDeletionRecord = record
        isShowingHistoryDeletionConfirmation = true
    }

    private func confirmDeleteHistory() {
        guard let record = pendingDeletionRecord else { return }
        clearPendingHistoryDeletion()

        Task {
            await viewModel.deleteChoiceRecord(id: record.id)
        }
    }

    private func clearPendingHistoryDeletion() {
        pendingDeletionRecord = nil
        isShowingHistoryDeletionConfirmation = false
    }
}
