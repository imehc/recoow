import SwiftUI

struct DecisionOptionsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: DecisionOptionsViewModel?

    let collectionID: String

    var body: some View {
        Group {
            if let viewModel {
                DecisionOptionsContent(viewModel: viewModel)
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle(viewModel?.collection?.title ?? "选什么")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(for: DecisionChoiceRecordRoute.self) { route in
            DecisionChoiceRecordDetailView(recordID: route.id)
        }
        .task(id: collectionID) {
            if viewModel == nil {
                let model = DecisionOptionsViewModel(
                    collectionID: collectionID,
                    repository: container.decisionRepository,
                    syncEngine: container.syncEngine
                )
                model.startObserving()
                viewModel = model
            }
        }
    }
}
