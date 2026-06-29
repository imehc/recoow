import SwiftUI

struct FoodJournalView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: FoodJournalViewModel?
    @State private var billsViewModel: BillsViewModel?
    @Namespace private var billImageTransition

    var body: some View {
        Group {
            if let viewModel, let billsViewModel {
                FoodJournalContent(
                    viewModel: viewModel,
                    billsViewModel: billsViewModel
                )
            } else {
                ProgressView(AppLocalization.string("正在加载"))
            }
        }
        .navigationTitle(AppLocalization.string("饮食记录"))
        .navigationDestination(for: FoodJournalRoute.self) { route in
            if let viewModel, let billsViewModel {
                FoodDayDetailView(
                    viewModel: viewModel,
                    billsViewModel: billsViewModel,
                    dayStart: route.dayStart,
                    billImageTransition: billImageTransition
                )
            }
        }
        .task {
            if viewModel == nil {
                let model = container.makeFoodJournalViewModel()
                model.startObserving()
                viewModel = model
            }

            if billsViewModel == nil {
                let model = container.makeBillsViewModel()
                model.startObserving()
                billsViewModel = model
            }
        }
    }
}

private struct FoodJournalContent: View {
    @Bindable var viewModel: FoodJournalViewModel
    @Bindable var billsViewModel: BillsViewModel
    @State private var presentedSheet: Sheet?
    @State private var groupPendingDeletion: FoodDayGroup?

    private enum Sheet: Identifiable {
        case addEntry

        var id: String {
            switch self {
            case .addEntry:
                "addEntry"
            }
        }
    }

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            FoodJournalSummarySection(
                hasEntries: viewModel.entries.isEmpty == false,
                dayCount: viewModel.allDayGroups.count,
                todayEntryCount: viewModel.todayEntries.count,
                todayMealKindCount: viewModel.todayMealKindCount,
                latestEntryDate: viewModel.latestEntryDate,
                currentWeekSnackCount: viewModel.currentWeekSnackCount,
                currentWeekDrinkCount: viewModel.currentWeekDrinkCount,
                currentWeekLateNightCount: viewModel.currentWeekLateNightCount,
                currentMonthMilkTeaCount: viewModel.currentMonthMilkTeaCount
            )

            if viewModel.entries.isEmpty {
                ContentUnavailableView {
                    Label(AppLocalization.string("暂无饮食记录"), systemImage: "fork.knife")
                } description: {
                    Text(AppLocalization.string("按天记录三餐、零食与饮品"))
                } actions: {
                    Button(AppLocalization.string("添加饮食"), systemImage: "plus", action: showAddEntry)
                }
            } else if viewModel.dayGroups.isEmpty {
                ContentUnavailableView(AppLocalization.string("没有匹配饮食记录"), systemImage: "magnifyingglass")
            } else {
                Section(AppLocalization.string("每天饮食")) {
                    ForEach(viewModel.dayGroups) { group in
                        NavigationLink(value: FoodJournalRoute(dayStart: group.date)) {
                            FoodDayGroupRow(
                                group: group,
                                coverPhoto: coverPhoto(for: group),
                                billCount: billCount(for: group)
                            )
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button {
                                groupPendingDeletion = group
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
        .searchable(text: $viewModel.searchText, prompt: Text(AppLocalization.string("搜索饮食记录")))
        .toolbar {
            Button(AppLocalization.string("添加饮食"), systemImage: "plus", action: showAddEntry)
        }
        .sheet(item: $presentedSheet) { sheet in
            switch sheet {
            case .addEntry:
                NavigationStack {
                    FoodEntryFormView(
                        entry: nil,
                        attachments: [],
                        viewModel: viewModel,
                        billsViewModel: billsViewModel
                    )
                }
            }
        }
        .alert(
            groupPendingDeletion.map { AppLocalization.format("删除“%@”？", deletionTitle(for: $0)) } ?? "",
            isPresented: .isPresent($groupPendingDeletion),
            presenting: groupPendingDeletion
        ) { group in
            Button(AppLocalization.string("删除"), role: .destructive) {
                deleteGroup(group)
            }
            Button(AppLocalization.string("取消"), role: .cancel) {
                groupPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该日期下的饮食条目都会从历史中移除。"))
        }
    }

    private func showAddEntry() {
        presentedSheet = .addEntry
    }

    private func deleteGroup(_ group: FoodDayGroup) {
        groupPendingDeletion = nil

        Task {
            await viewModel.deleteDay(dayStart: group.date)
        }
    }

    private func billCount(for group: FoodDayGroup) -> Int {
        Set(group.entries.flatMap(\.billIDs)).count
    }

    private func coverPhoto(for group: FoodDayGroup) -> MediaAttachment? {
        for entry in group.sortedEntries {
            if let photo = viewModel.attachments(for: entry.id).first(where: { $0.kind == .photo }) {
                return photo
            }
        }

        return nil
    }

    private func deletionTitle(for group: FoodDayGroup) -> String {
        group.title ?? AppFormatters.date(milliseconds: Int64(group.date.timeIntervalSince1970 * 1000))
    }
}

#Preview {
    NavigationStack {
        FoodJournalView()
            .environment(AppContainer.preview)
    }
}
