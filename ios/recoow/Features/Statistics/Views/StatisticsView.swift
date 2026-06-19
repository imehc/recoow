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

            let billsModel = container.makeBillsViewModel()
            billsModel.startObserving()
            billsViewModel = billsModel

            let model = container.makeStatisticsViewModel()
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
