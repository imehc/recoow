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
            bill.id == route.id && bill.hasImage
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
    @State private var billPendingRefund: BillRecord?

    let billImageTransition: Namespace.ID

    private enum Sheet: Identifiable {
        case addBill
        case copyBill(BillRecord)
        case filters
        case storedValueAccounts

        var id: String {
            switch self {
            case .addBill:
                "addBill"
            case .copyBill(let bill):
                "copyBill:\(bill.id)"
            case .filters:
                "filters"
            case .storedValueAccounts:
                "storedValueAccounts"
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
                todayDiscountCents: viewModel.todayDiscountCents,
                monthTotalCents: viewModel.currentMonthTotalCents,
                monthIncomeCents: viewModel.currentMonthIncomeCents,
                monthDiscountCents: viewModel.currentMonthDiscountCents,
                todayStoredValueRechargeCents: viewModel.todayStoredValueRechargeCents,
                todayStoredValueUseCents: viewModel.todayStoredValueUseCents,
                monthStoredValueUseCents: viewModel.currentMonthStoredValueUseCents
            )

            if viewModel.bills.isEmpty == false || viewModel.storedValueAccounts.isEmpty == false {
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
                                duplicateBill(bill)
                            } label: {
                                Label("复制", systemImage: "doc.on.doc")
                            }
                            .tint(.blue)

                            Button {
                                requestDeleteBill(bill)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                            .tint(.red)
                        }
                        .swipeActions(edge: .leading, allowsFullSwipe: false) {
                            settlementSwipeActions(for: bill)
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
                    
                Button(action: showStoredValueAccounts) {
                    Label("储值账户", systemImage: "wallet.pass")
                        .labelStyle(.iconOnly)
                }
                .accessibilityLabel("储值账户")

                Button("记一笔", systemImage: "plus", action: showAddBill)
            }
        }
        .sheet(item: $presentedSheet) { sheet in
            switch sheet {
            case .addBill:
                NavigationStack {
                    BillFormView(bill: nil, viewModel: viewModel)
                }
            case .copyBill(let bill):
                NavigationStack {
                    BillFormView(bill: nil, viewModel: viewModel, prefillBill: bill)
                }
            case .filters:
                NavigationStack {
                    BillFilterSheetView(viewModel: viewModel)
                }
                .presentationDetents([.medium])
            case .storedValueAccounts:
                StoredValueAccountManagementView(viewModel: viewModel)
                    .presentationDetents([.height(storedValueAccountsSheetHeight), .large])
                    .presentationDragIndicator(.visible)
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
        .sheet(item: $billPendingRefund) { bill in
            RefundReasonSheet(bill: bill, viewModel: viewModel)
        }
    }

    @ViewBuilder
    private func settlementSwipeActions(for bill: BillRecord) -> some View {
        switch bill.lifecycleState {
        case .normal:
            if bill.isGroupBuy {
                Button {
                    redeemBill(bill)
                } label: {
                    Label("核销", systemImage: "checkmark.seal")
                }
                .tint(.green)
            }

            Button {
                billPendingRefund = bill
            } label: {
                Label("退款", systemImage: "arrow.uturn.backward")
            }
            .tint(.orange)
        case .redeemed, .expired, .refunded:
            EmptyView()
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

    private var storedValueAccountsSheetHeight: CGFloat {
        let rowHeight = CGFloat(max(viewModel.storedValueAccounts.count, 1)) * 56
        return min(560, max(300, 180 + rowHeight))
    }

    private func showAddBill() {
        presentedSheet = .addBill
    }

    private func showFilterSheet() {
        presentedSheet = .filters
    }

    private func showStoredValueAccounts() {
        presentedSheet = .storedValueAccounts
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

    private func duplicateBill(_ bill: BillRecord) {
        let draft = viewModel.makeDuplicateDraft(from: bill)
        presentedSheet = .copyBill(draft)
    }

    private func redeemBill(_ bill: BillRecord) {
        Task {
            await viewModel.redeem(bill)
        }
    }
}

private struct StoredValueAccountManagementView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: BillsViewModel
    @State private var presentedSheet: Sheet?
    @State private var accountPendingDeletion: StoredValueAccount?

    private enum Sheet: Identifiable {
        case add
        case edit(StoredValueAccount)

        var id: String {
            switch self {
            case .add:
                "add"
            case .edit(let account):
                "edit:\(account.id)"
            }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if viewModel.storedValueAccounts.isEmpty {
                    ContentUnavailableView {
                        Label("暂无储值账户", systemImage: "wallet.pass")
                    } description: {
                        Text("添加后可以记录交通卡、饭卡等储值余额。")
                    } actions: {
                        Button("添加储值账户", systemImage: "plus", action: showAddAccount)
                    }
                } else {
                    Section {
                        ForEach(viewModel.storedValueAccounts) { account in
                            StoredValueAccountManagementRow(
                                account: account,
                                canDelete: viewModel.canDeleteStoredValueAccount(account),
                                edit: editAccount,
                                requestDelete: requestDeleteAccount
                            )
                        }
                    } header: {
                        Text("储值账户")
                    } footer: {
                        Text("已关联账单的储值账户不能删除。")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("储值账户")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成", action: dismiss.callAsFunction)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("添加储值账户", systemImage: "plus", action: showAddAccount)
                }
            }
            .sheet(item: $presentedSheet) { sheet in
                Group {
                    switch sheet {
                    case .add:
                        StoredValueAccountFormView(viewModel: viewModel) { _ in }
                    case .edit(let account):
                        StoredValueAccountFormView(account: account, viewModel: viewModel) { _ in }
                    }
                }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .alert(
                accountPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.name) } ?? "",
                isPresented: .isPresent($accountPendingDeletion),
                presenting: accountPendingDeletion
            ) { account in
                Button("删除", role: .destructive) {
                    deleteAccount(account)
                }
                Button("取消", role: .cancel) {
                    accountPendingDeletion = nil
                }
            } message: { _ in
                Text("删除后该储值账户会从列表中移除。")
            }
        }
    }

    private func showAddAccount() {
        presentedSheet = .add
    }

    private func editAccount(_ account: StoredValueAccount) {
        presentedSheet = .edit(account)
    }

    private func requestDeleteAccount(_ account: StoredValueAccount) {
        accountPendingDeletion = account
    }

    private func deleteAccount(_ account: StoredValueAccount) {
        accountPendingDeletion = nil

        Task {
            await viewModel.deleteStoredValueAccount(account)
        }
    }
}

private struct StoredValueAccountManagementRow: View {
    let account: StoredValueAccount
    let canDelete: Bool
    let edit: (StoredValueAccount) -> Void
    let requestDelete: (StoredValueAccount) -> Void

    var body: some View {
        Button {
            edit(account)
        } label: {
            StoredValueAccountBalanceRow(account: account)
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                edit(account)
            } label: {
                Label("编辑", systemImage: "square.and.pencil")
            }
            .tint(.blue)

            if canDelete {
                Button(role: .destructive) {
                    requestDelete(account)
                } label: {
                    Label("删除", systemImage: "trash")
                }
            }
        }
    }
}

private struct StoredValueAccountBalanceRow: View {
    let account: StoredValueAccount

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: account.accountKind.systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(.orange.gradient, in: .rect(cornerRadius: AppDesign.iconCornerRadius))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(account.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Text(account.accountKind.localizedTitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 12)

            Text(AppFormatters.money(cents: account.balanceCents))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    NavigationStack {
        BillsView()
            .environment(AppContainer.preview)
    }
}
