import SwiftUI

struct StatisticsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: StatisticsViewModel?
    @State private var billsViewModel: BillsViewModel?

    let openHistory: () -> Void

    var body: some View {
        Group {
            if let viewModel {
                StatisticsContent(
                    viewModel: viewModel,
                    billsViewModel: billsViewModel,
                    openHistory: openHistory
                )
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("统计")
        .task {
            guard viewModel == nil else { return }

            let billsModel = BillsViewModel(
                repository: container.billRepository,
                syncEngine: container.syncEngine
            )
            billsModel.startObserving()
            billsViewModel = billsModel

            let model = StatisticsViewModel(
                trackRepository: container.trackRepository,
                decisionRepository: container.decisionRepository,
                itemLocatorRepository: container.itemLocatorRepository,
                reminderRepository: container.reminderRepository,
                billRepository: container.billRepository,
                anniversaryRepository: container.anniversaryRepository
            )
            model.startObserving()
            viewModel = model
        }
    }
}

#Preview {
    NavigationStack {
        StatisticsView(openHistory: {})
            .environment(AppContainer.preview)
    }
}
