import SwiftUI

struct StatisticsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: StatisticsViewModel?
    @State private var billsViewModel: BillsViewModel?

    var body: some View {
        Group {
            if let viewModel {
                StatisticsContent(
                    viewModel: viewModel,
                    billsViewModel: billsViewModel
                )
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle(AppLocalization.string("统计"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    TrackHistoryView()
                } label: {
                    Image(systemName: "clock")
                }
                .accessibilityLabel(AppLocalization.string("历史"))
            }
        }
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
        StatisticsView()
            .environment(AppContainer.preview)
    }
}
