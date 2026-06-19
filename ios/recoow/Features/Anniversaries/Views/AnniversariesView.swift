import Combine
import SwiftUI

struct AnniversariesView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: AnniversariesViewModel?
    @State private var currentDate = Date()

    var body: some View {
        Group {
            if let viewModel {
                AnniversariesContent(viewModel: viewModel, currentDate: currentDate)
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("纪念日")
        .navigationDestination(for: AnniversaryRoute.self) { route in
            if let viewModel {
                AnniversaryDetailView(viewModel: viewModel, anniversaryID: route.id)
            }
        }
        .task {
            guard viewModel == nil else { return }

            let model = container.makeAnniversariesViewModel()
            model.startObserving()
            viewModel = model
        }
        .onReceive(Timer.publish(every: 60, on: .main, in: .common).autoconnect()) { date in
            currentDate = date
        }
    }
}

private struct AnniversariesContent: View {
    @Bindable var viewModel: AnniversariesViewModel
    @State private var isShowingAddAnniversary = false
    @State private var anniversaryPendingDeletion: AnniversaryRecord?

    let currentDate: Date

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            if let notificationMessage = viewModel.notificationMessage {
                Section {
                    Label(notificationMessage, systemImage: "bell.slash")
                        .foregroundStyle(.orange)
                }
            }

            if viewModel.anniversaries.isEmpty {
                ContentUnavailableView {
                    Label("暂无纪念日", systemImage: "calendar.badge.plus")
                } description: {
                    Text("添加一个重要日期，查看倒计时和提醒")
                } actions: {
                    Button("添加纪念日", systemImage: "plus", action: showAddAnniversary)
                }
            } else {
                let upcoming = viewModel.upcomingAnniversaries(from: currentDate)
                let past = viewModel.pastAnniversaries

                if upcoming.isEmpty == false {
                    Section("即将到来") {
                        ForEach(upcoming) { anniversary in
                            NavigationLink(value: AnniversaryRoute(id: anniversary.id)) {
                                AnniversaryRow(anniversary: anniversary, referenceDate: currentDate)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    requestDeleteAnniversary(anniversary)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                        }
                    }
                }

                if past.isEmpty == false {
                    Section("已过去") {
                        ForEach(past) { anniversary in
                            NavigationLink(value: AnniversaryRoute(id: anniversary.id)) {
                                AnniversaryRow(anniversary: anniversary, referenceDate: currentDate)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    requestDeleteAnniversary(anniversary)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .toolbar {
            Button("添加纪念日", systemImage: "plus", action: showAddAnniversary)
        }
        .sheet(isPresented: $isShowingAddAnniversary) {
            NavigationStack {
                AnniversaryFormView(anniversary: nil, viewModel: viewModel)
            }
        }
        .alert(
            anniversaryPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($anniversaryPendingDeletion),
            presenting: anniversaryPendingDeletion
        ) { anniversary in
            Button("删除", role: .destructive) {
                confirmDeleteAnniversary(anniversary)
            }
            Button("取消", role: .cancel) {
                anniversaryPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    private func showAddAnniversary() {
        isShowingAddAnniversary = true
    }

    private func requestDeleteAnniversary(_ anniversary: AnniversaryRecord) {
        anniversaryPendingDeletion = anniversary
    }

    private func confirmDeleteAnniversary(_ anniversary: AnniversaryRecord) {
        anniversaryPendingDeletion = nil

        Task {
            await viewModel.deleteAnniversary(id: anniversary.id)
        }
    }
}

#Preview {
    NavigationStack {
        AnniversariesView()
            .environment(AppContainer.preview)
    }
}
