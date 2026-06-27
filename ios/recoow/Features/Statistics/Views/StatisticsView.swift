import SwiftUI

private enum StatisticsNavigationRoute: Hashable {
    case history
}

struct StatisticsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: StatisticsViewModel?
    @State private var billsViewModel: BillsViewModel?
    @Binding private var tabBarVisibility: Visibility

    init(tabBarVisibility: Binding<Visibility> = .constant(.visible)) {
        _tabBarVisibility = tabBarVisibility
    }

    var body: some View {
        let language = container.appPreferences.language

        Group {
            if let viewModel {
                StatisticsContent(
                    viewModel: viewModel,
                    billsViewModel: billsViewModel,
                    tabBarVisibility: $tabBarVisibility
                )
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle(AppLocalization.string("统计", language: language))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: StatisticsNavigationRoute.history) {
                    Image(systemName: "clock")
                }
                .accessibilityLabel(AppLocalization.string("历史", language: language))
            }
        }
        .navigationDestination(for: StatisticsNavigationRoute.self) { route in
            switch route {
            case .history:
                TrackHistoryView()
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
