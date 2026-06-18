import SwiftUI

struct HistoryFilterMenu: View {
    @Binding var selectedRoute: ToolRoute?

    let activeFilter: HistoryFilter?
    let isFiltering: Bool
    let clearFilter: () -> Void

    var body: some View {
        Menu {
            Picker("分类", selection: $selectedRoute) {
                Text("全部分类").tag(nil as ToolRoute?)

                ForEach(ToolRoute.allCases) { route in
                    Label(route.titleKey, systemImage: route.systemImage)
                        .tag(route as ToolRoute?)
                }
            }

            if activeFilter != nil {
                Divider()

                Button("清除筛选", systemImage: "xmark.circle", action: clearFilter)
            }
        } label: {
            Label("筛选", systemImage: filterButtonImage)
        }
        .tint(isFiltering ? .blue : nil)
    }

    private var filterButtonImage: String {
        isFiltering ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle"
    }
}
