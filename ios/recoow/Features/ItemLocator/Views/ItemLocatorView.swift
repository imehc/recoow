import SwiftUI

struct ItemLocatorView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: ItemLocatorViewModel?
    @Namespace private var itemImageTransition

    var body: some View {
        Group {
            if let viewModel {
                ItemLocatorContent(viewModel: viewModel, itemImageTransition: itemImageTransition)
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("在哪里")
        .navigationDestination(for: StoredItemRoute.self) { route in
            if let viewModel {
                StoredItemDetailView(
                    viewModel: viewModel,
                    itemID: route.id,
                    itemImageTransition: itemImageTransition
                )
            }
        }
        .task {
            if viewModel == nil {
                let model = ItemLocatorViewModel(
                    repository: container.itemLocatorRepository,
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
        ItemLocatorView()
            .environment(AppContainer.preview)
    }
}
