import SwiftUI

struct DecisionOptionsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: DecisionOptionsViewModel?
    @Namespace private var choiceRecordImageTransition

    let collectionID: String

    var body: some View {
        Group {
            if let viewModel {
                DecisionOptionsContent(
                    viewModel: viewModel,
                    choiceRecordImageTransition: choiceRecordImageTransition
                )
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle(viewModel?.collection?.title ?? "选什么")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(for: DecisionChoiceRecordRoute.self) { route in
            DecisionChoiceRecordDetailView(
                recordID: route.id,
                choiceRecordImageTransition: imageTransition(for: route)
            )
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

    private func imageTransition(for route: DecisionChoiceRecordRoute) -> Namespace.ID? {
        guard viewModel?.choiceRecords.contains(where: { record in
            record.id == route.id && record.optionImageData != nil
        }) == true else {
            return nil
        }

        return choiceRecordImageTransition
    }
}
