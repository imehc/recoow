import SwiftUI

struct DiaryView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: DiaryViewModel?
    @State private var billsViewModel: BillsViewModel?
    @State private var remindersViewModel: RemindersViewModel?
    @State private var anniversariesViewModel: AnniversariesViewModel?
    @State private var itemLocatorViewModel: ItemLocatorViewModel?
    @Namespace private var billImageTransition
    @Namespace private var reminderImageTransition
    @Namespace private var itemImageTransition
    @Namespace private var choiceRecordImageTransition

    var body: some View {
        Group {
            if let viewModel {
                DiaryContent(viewModel: viewModel)
            } else {
                ProgressView(AppLocalization.string("正在加载"))
            }
        }
        .navigationTitle(AppLocalization.string("日记"))
        .navigationDestination(for: DiaryRoute.self) { route in
            if let viewModel {
                DiaryDetailView(
                    viewModel: viewModel,
                    diaryID: route.id
                )
            }
        }
        .navigationDestination(for: HistoryDetailRoute.self) { route in
            switch route {
            case .track(let id):
                TrackDetailView(trackID: id)
            case .bill(let id):
                if let billsViewModel {
                    BillDetailView(
                        viewModel: billsViewModel,
                        billID: id,
                        billImageTransition: billImageTransition
                    )
                } else {
                    ProgressView(AppLocalization.string("正在加载"))
                }
            case .reminder(let id):
                if let remindersViewModel {
                    ReminderDetailView(
                        viewModel: remindersViewModel,
                        reminderID: id,
                        reminderImageTransition: reminderImageTransition
                    )
                } else {
                    ProgressView(AppLocalization.string("正在加载"))
                }
            case .anniversary(let id):
                if let anniversariesViewModel {
                    AnniversaryDetailView(viewModel: anniversariesViewModel, anniversaryID: id)
                } else {
                    ProgressView(AppLocalization.string("正在加载"))
                }
            case .storedItem(let id):
                if let itemLocatorViewModel {
                    StoredItemDetailView(
                        viewModel: itemLocatorViewModel,
                        itemID: id,
                        itemImageTransition: itemImageTransition
                    )
                } else {
                    ProgressView(AppLocalization.string("正在加载"))
                }
            case .decisionChoice(let id):
                DecisionChoiceRecordDetailView(
                    recordID: id,
                    choiceRecordImageTransition: choiceRecordImageTransition
                )
            case .diary(let id):
                if let viewModel {
                    DiaryDetailView(
                        viewModel: viewModel,
                        diaryID: id
                    )
                } else {
                    ProgressView(AppLocalization.string("正在加载"))
                }
            }
        }
        .task {
            if viewModel == nil {
                let model = container.makeDiaryViewModel()
                model.startObserving()
                viewModel = model
            }

            if billsViewModel == nil {
                let model = container.makeBillsViewModel()
                model.startObserving()
                billsViewModel = model
            }

            if remindersViewModel == nil {
                let model = container.makeRemindersViewModel()
                model.startObserving()
                remindersViewModel = model
            }

            if anniversariesViewModel == nil {
                let model = container.makeAnniversariesViewModel()
                model.startObserving()
                anniversariesViewModel = model
            }

            if itemLocatorViewModel == nil {
                let model = container.makeItemLocatorViewModel()
                model.startObserving()
                itemLocatorViewModel = model
            }
        }
    }
}

private struct DiaryContent: View {
    @Bindable var viewModel: DiaryViewModel
    @State private var isShowingAddSheet = false
    @State private var isShowingCalendarSheet = false
    @State private var entryPendingDeletion: DiaryEntry?

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            DiarySummarySection(
                hasEntries: viewModel.entries.isEmpty == false,
                entryCount: viewModel.entries.count,
                todayEntryCount: viewModel.todayEntryCount,
                activeDayCount: viewModel.activeDayCount,
                currentStreakDays: viewModel.currentStreakDays
            )

            if viewModel.selectedTag != nil || viewModel.selectedDate != nil {
                Section(AppLocalization.string("筛选")) {
                    if let selectedTag = viewModel.selectedTag {
                        LabeledContent(AppLocalization.string("标签"), value: viewModel.tagTitle(forKey: selectedTag))
                    }

                    if let selectedDate = viewModel.selectedDate {
                        LabeledContent(AppLocalization.string("日期"), value: AppFormatters.date(milliseconds: DiaryViewModel.milliseconds(for: selectedDate)))
                    }

                    Button(AppLocalization.string("清除筛选"), systemImage: "xmark.circle") {
                        clearFilters()
                    }
                }
            }

            if viewModel.entries.isEmpty {
                ContentUnavailableView {
                    Label(AppLocalization.string("暂无日记"), systemImage: "book.closed")
                } description: {
                    Text(AppLocalization.string("记录今天发生的事"))
                } actions: {
                    Button(AppLocalization.string("写日记"), systemImage: "plus", action: showAddSheet)
                }
            } else if viewModel.filteredEntries.isEmpty {
                ContentUnavailableView(AppLocalization.string("没有匹配日记"), systemImage: "magnifyingglass")
            } else {
                Section(AppLocalization.string("日记")) {
                    ForEach(viewModel.filteredEntries) { entry in
                        NavigationLink(value: DiaryRoute(id: entry.id)) {
                            DiaryRow(
                                entry: entry,
                                tagTitles: viewModel.resolvedTagReferences(for: entry).map(\.value),
                                links: viewModel.links(for: entry.id),
                                attachments: viewModel.attachments(for: entry.id)
                            )
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button {
                                entryPendingDeletion = entry
                            } label: {
                                Label(AppLocalization.string("删除"), systemImage: "trash")
                            }
                            .tint(.red)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .searchable(text: $viewModel.searchText, prompt: Text(AppLocalization.string("搜索日记")))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(AppLocalization.string("写日记"), systemImage: "plus", action: showAddSheet)
            }

            ToolbarItem(placement: .topBarLeading) {
                Menu(AppLocalization.string("筛选"), systemImage: "line.3.horizontal.decrease.circle") {
                    Button(AppLocalization.string("日历"), systemImage: "calendar") {
                        isShowingCalendarSheet = true
                    }

                    if viewModel.availableTagReferences.isEmpty == false {
                        Section(AppLocalization.string("标签")) {
                            ForEach(viewModel.availableTagReferences) { tag in
                                Button(tag.value) {
                                    viewModel.selectedTag = tag.key
                                }
                            }
                        }
                    }

                    Button(AppLocalization.string("清除筛选"), systemImage: "xmark.circle") {
                        clearFilters()
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingAddSheet) {
            NavigationStack {
                DiaryFormView(
                    entry: nil,
                    links: [],
                    attachments: [],
                    viewModel: viewModel
                )
            }
        }
        .sheet(isPresented: $isShowingCalendarSheet) {
            DiaryCalendarFilterView(viewModel: viewModel)
        }
        .alert(
            entryPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($entryPendingDeletion),
            presenting: entryPendingDeletion
        ) { entry in
            Button(AppLocalization.string("删除"), role: .destructive) {
                confirmDelete(entry)
            }
            Button(AppLocalization.string("取消"), role: .cancel) {
                entryPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    private func showAddSheet() {
        isShowingAddSheet = true
    }

    private func clearFilters() {
        viewModel.selectedTag = nil
        viewModel.selectedDate = nil
    }

    private func confirmDelete(_ entry: DiaryEntry) {
        entryPendingDeletion = nil

        Task {
            await viewModel.deleteEntry(id: entry.id)
        }
    }
}

private struct DiarySummarySection: View {
    let hasEntries: Bool
    let entryCount: Int
    let todayEntryCount: Int
    let activeDayCount: Int
    let currentStreakDays: Int

    var body: some View {
        Section(AppLocalization.string("概览")) {
            LazyVGrid(columns: columns, spacing: 10) {
                CompactSummaryMetricView(
                    title: AppLocalization.string("日记数量"),
                    value: entryCountText,
                    systemImage: "book.closed.fill",
                    tint: .blue
                )

                CompactSummaryMetricView(
                    title: AppLocalization.string("今日记录"),
                    value: todayEntryCountText,
                    systemImage: "calendar.badge.plus",
                    tint: .orange
                )

                CompactSummaryMetricView(
                    title: AppLocalization.string("记录天数"),
                    value: activeDayCountText,
                    systemImage: "calendar",
                    tint: .green
                )

                CompactSummaryMetricView(
                    title: AppLocalization.string("连续记录"),
                    value: currentStreakDaysText,
                    systemImage: "flame.fill",
                    tint: .red
                )
            }
        }
    }

    private var columns: [GridItem] {
        [
            GridItem(.flexible(), spacing: 10),
            GridItem(.flexible(), spacing: 10)
        ]
    }

    private var entryCountText: String {
        hasEntries ? AppLocalization.format("%d 篇", entryCount) : "--"
    }

    private var todayEntryCountText: String {
        hasEntries ? AppLocalization.format("%d 篇", todayEntryCount) : "--"
    }

    private var activeDayCountText: String {
        hasEntries ? AppLocalization.format("%d 天", activeDayCount) : "--"
    }

    private var currentStreakDaysText: String {
        hasEntries ? AppLocalization.format("%d 天", currentStreakDays) : "--"
    }
}

#Preview {
    NavigationStack {
        DiaryView()
            .environment(AppContainer.preview)
    }
}
