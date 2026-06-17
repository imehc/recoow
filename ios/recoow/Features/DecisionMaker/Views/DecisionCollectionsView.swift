import SwiftUI

struct DecisionCollectionsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: DecisionCollectionsViewModel?

    var body: some View {
        Group {
            if let viewModel {
                DecisionCollectionsContent(viewModel: viewModel)
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("选什么")
        .navigationDestination(for: DecisionCollectionRoute.self) { route in
            DecisionOptionsView(collectionID: route.id)
        }
        .task {
            if viewModel == nil {
                let model = DecisionCollectionsViewModel(
                    repository: container.decisionRepository,
                    syncEngine: container.syncEngine
                )
                model.startObserving()
                viewModel = model
            }
        }
    }
}

#Preview {
    NavigationStack {
        DecisionCollectionsView()
            .environment(AppContainer.preview)
    }
}
