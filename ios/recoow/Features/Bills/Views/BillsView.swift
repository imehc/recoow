import SwiftUI

struct BillsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: BillsViewModel?
    @Namespace private var billImageTransition

    var body: some View {
        Group {
            if let viewModel {
                BillsContent(
                    viewModel: viewModel,
                    billImageTransition: billImageTransition
                )
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("记一笔")
        .navigationDestination(for: BillRoute.self) { route in
            if let viewModel {
                BillDetailView(
                    viewModel: viewModel,
                    billID: route.id,
                    billImageTransition: imageTransition(for: route)
                )
            }
        }
        .task {
            guard viewModel == nil else { return }

            let model = container.makeBillsViewModel()
            model.startObserving()
            viewModel = model
        }
    }

    private func imageTransition(for route: BillRoute) -> Namespace.ID? {
        guard viewModel?.bills.contains(where: { bill in
            bill.id == route.id && bill.imageData != nil
        }) == true else {
            return nil
        }

        return billImageTransition
    }
}

private struct BillsContent: View {
    @Bindable var viewModel: BillsViewModel
    @State private var presentedSheet: Sheet?
    @State private var billPendingDeletion: BillRecord?

    let billImageTransition: Namespace.ID

    private enum Sheet: Identifiable {
        case addBill
        case filters

        var id: String {
            switch self {
            case .addBill:
                "addBill"
            case .filters:
                "filters"
            }
        }
    }

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            BillSummarySection(
                hasBills: viewModel.bills.isEmpty == false,
                todayTotalCents: viewModel.todayTotalCents,
                todayIncomeCents: viewModel.todayIncomeCents,
                monthTotalCents: viewModel.currentMonthTotalCents,
                monthIncomeCents: viewModel.currentMonthIncomeCents,
                monthDiscountCents: viewModel.currentMonthDiscountCents
            )

            if viewModel.bills.isEmpty == false {
                BillFilterSection(viewModel: viewModel)
            }

            if viewModel.bills.isEmpty {
                ContentUnavailableView {
                    Label("暂无账单", systemImage: "receipt")
                } description: {
                    Text("添加一条收支记录")
                } actions: {
                    Button("记一笔", systemImage: "plus", action: showAddBill)
                }
            } else if viewModel.filteredBills.isEmpty {
                ContentUnavailableView("没有匹配账单", systemImage: "magnifyingglass")
            } else {
                Section("账单") {
                    ForEach(viewModel.filteredBills) { bill in
                        NavigationLink(value: BillRoute(id: bill.id)) {
                            BillRow(
                                bill: bill,
                                billImageTransition: billImageTransition
                            )
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button {
                                requestDeleteBill(bill)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                            .tint(.red)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .searchable(text: $viewModel.searchText, prompt: "搜索账单")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("筛选", systemImage: filterButtonImage, action: showFilterSheet)
                    .disabled(viewModel.bills.isEmpty)

                Button("记一笔", systemImage: "plus", action: showAddBill)
            }
        }
        .sheet(item: $presentedSheet) { sheet in
            switch sheet {
            case .addBill:
                NavigationStack {
                    BillFormView(bill: nil, viewModel: viewModel)
                }
            case .filters:
                NavigationStack {
                    BillFilterSheetView(viewModel: viewModel)
                }
                .presentationDetents([.medium])
            }
        }
        .alert(
            billPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($billPendingDeletion),
            presenting: billPendingDeletion
        ) { bill in
            Button("删除", role: .destructive) {
                confirmDeleteBill(bill)
            }
            Button("取消", role: .cancel) {
                billPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    private var filterButtonImage: String {
        hasAdditionalFilters ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle"
    }

    private var hasAdditionalFilters: Bool {
        viewModel.selectedCategory != nil
        || viewModel.selectedIncomeCategory != nil
        || viewModel.selectedPaymentMethod != nil
    }

    private func showAddBill() {
        presentedSheet = .addBill
    }

    private func showFilterSheet() {
        presentedSheet = .filters
    }

    private func requestDeleteBill(_ bill: BillRecord) {
        billPendingDeletion = bill
    }

    private func confirmDeleteBill(_ bill: BillRecord) {
        billPendingDeletion = nil

        Task {
            await viewModel.deleteBill(id: bill.id)
        }
    }
}

#Preview {
    NavigationStack {
        BillsView()
            .environment(AppContainer.preview)
    }
}
