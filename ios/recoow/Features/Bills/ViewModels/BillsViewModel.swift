import Foundation
import Observation

@MainActor
@Observable
final class BillsViewModel {
    var bills: [BillRecord] = []
    var storedValueAccounts: [StoredValueAccount] = []
    var searchText = ""
    var selectedBillType: BillType?
    var selectedCategory: BillCategory?
    var selectedIncomeCategory: BillIncomeCategory?
    var selectedPaymentMethod: BillPaymentMethod?
    var errorMessage: String?

    @ObservationIgnored private let repository: BillRepository
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?
    @ObservationIgnored private var storedValueAccountsObservationTask: Task<Void, Never>?

    init(repository: BillRepository, syncEngine: any SyncEngine) {
        self.repository = repository
        self.syncEngine = syncEngine
    }

    deinit {
        observationTask?.cancel()
        storedValueAccountsObservationTask?.cancel()
    }

    var filteredBills: [BillRecord] {
        bills.filter { bill in
            let matchesType = selectedBillType == nil || bill.billType == selectedBillType
            let matchesCategory = selectedCategory == nil || ((bill.billType == .expense || bill.billType == .storedValueUse) && bill.billCategory == selectedCategory)
            let matchesIncomeCategory = selectedIncomeCategory == nil || (bill.billType == .income && bill.billIncomeCategory == selectedIncomeCategory)
            let matchesPaymentMethod = selectedPaymentMethod == nil || bill.billPaymentMethod == selectedPaymentMethod
            guard matchesType && matchesCategory && matchesIncomeCategory && matchesPaymentMethod else { return false }

            let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard query.isEmpty == false else { return true }

            return searchableText(for: bill).localizedCaseInsensitiveContains(query)
        }
    }

    var currentMonthBills: [BillRecord] {
        bills.filter { bill in
            Calendar.current.isDate(bill.occurredDate, equalTo: Date(), toGranularity: .month)
        }
    }

    var currentMonthExpenseBills: [BillRecord] {
        currentMonthBills.filter { $0.billType == .expense }
    }

    var currentMonthIncomeBills: [BillRecord] {
        currentMonthBills.filter { $0.billType == .income }
    }

    var currentMonthStoredValueUseBills: [BillRecord] {
        currentMonthBills.filter { $0.billType == .storedValueUse }
    }

    var currentMonthTotalCents: Int64 {
        currentMonthExpenseBills.reduce(0) { $0 + $1.countedAmountCents }
    }

    var currentMonthIncomeCents: Int64 {
        currentMonthIncomeBills.reduce(0) { $0 + $1.countedAmountCents }
    }

    var currentMonthDiscountCents: Int64 {
        currentMonthExpenseBills.reduce(0) { $0 + $1.countedDiscountCents }
    }

    var currentMonthStoredValueUseCents: Int64 {
        currentMonthStoredValueUseBills.reduce(0) { $0 + $1.countedAmountCents }
    }

    var todayTotalCents: Int64 {
        bills
            .filter { Calendar.current.isDateInToday($0.occurredDate) }
            .filter { $0.billType == .expense }
            .reduce(0) { $0 + $1.countedAmountCents }
    }

    var todayIncomeCents: Int64 {
        bills
            .filter { Calendar.current.isDateInToday($0.occurredDate) }
            .filter { $0.billType == .income }
            .reduce(0) { $0 + $1.countedAmountCents }
    }

    var todayDiscountCents: Int64 {
        bills
            .filter { Calendar.current.isDateInToday($0.occurredDate) }
            .filter { $0.billType == .expense }
            .reduce(0) { $0 + $1.countedDiscountCents }
    }

    var todayStoredValueRechargeCents: Int64 {
        bills
            .filter { Calendar.current.isDateInToday($0.occurredDate) }
            .filter { $0.billType == .expense && $0.storedValueAccountID != nil }
            .reduce(0) { $0 + $1.countedAmountCents }
    }

    var todayStoredValueUseCents: Int64 {
        bills
            .filter { Calendar.current.isDateInToday($0.occurredDate) }
            .filter { $0.billType == .storedValueUse }
            .reduce(0) { $0 + $1.countedAmountCents }
    }

    func startObserving() {
        guard observationTask == nil else { return }

        observationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeBills() {
                switch result {
                case .success(let bills):
                    self.bills = bills
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }

        storedValueAccountsObservationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeStoredValueAccounts() {
                switch result {
                case .success(let accounts):
                    self.storedValueAccounts = accounts
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func bill(id: String) -> BillRecord? {
        bills.first { $0.id == id }
    }

    func storedValueAccount(id: String?) -> StoredValueAccount? {
        guard let id else { return nil }
        return storedValueAccounts.first { $0.id == id }
    }

    func storedValueAccountName(id: String?) -> String? {
        storedValueAccount(id: id)?.name
    }

    func canDeleteStoredValueAccount(_ account: StoredValueAccount) -> Bool {
        bills.contains { bill in
            bill.storedValueAccountID == account.id
        } == false
    }

    func loadBillIfNeeded(id: String) async {
        guard bill(id: id) == nil else { return }

        do {
            if let bill = try repository.fetchBill(id: id) {
                upsertBill(bill)
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func makeBill(
        title: String,
        originalAmountCents: Int64,
        discountAmountCents: Int64,
        finalAmountCents: Int64,
        billType: BillType,
        categoryRawValue: String,
        paymentMethod: BillPaymentMethod,
        note: String?,
        startLocation: String?,
        endLocation: String?,
        transportLines: String?,
        occurredDate: Date,
        imageData: Data?,
        imageAssetID: String? = nil,
        settlementStatus: BillSettlementStatus = .active,
        groupBuyValidUntil: Int64? = nil,
        storedValueAccountID: String? = nil
    ) -> BillRecord {
        BillRecord.makeNew(
            title: title,
            originalAmountCents: originalAmountCents,
            discountAmountCents: discountAmountCents,
            finalAmountCents: finalAmountCents,
            billType: billType,
            categoryRawValue: categoryRawValue,
            paymentMethod: paymentMethod,
            note: note,
            startLocation: startLocation,
            endLocation: endLocation,
            transportLines: transportLines,
            occurredAt: Self.milliseconds(for: occurredDate),
            imageData: imageData,
            imageAssetID: imageAssetID,
            settlementStatus: settlementStatus,
            groupBuyValidUntil: groupBuyValidUntil,
            storedValueAccountID: storedValueAccountID,
            deviceID: repository.deviceID
        )
    }

    func makeStoredValueAccount(
        name: String,
        kind: StoredValueAccountKind,
        balanceCents: Int64
    ) -> StoredValueAccount {
        StoredValueAccount.makeNew(
            name: name,
            kind: kind,
            balanceCents: balanceCents,
            deviceID: repository.deviceID
        )
    }

    func saveStoredValueAccount(_ account: StoredValueAccount) async -> StoredValueAccount? {
        do {
            let savedAccount = try repository.saveStoredValueAccount(account)
            upsertStoredValueAccount(savedAccount)
            errorMessage = nil
            await syncEngine.enqueueScan()
            return savedAccount
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func deleteStoredValueAccount(_ account: StoredValueAccount) async {
        do {
            try repository.deleteStoredValueAccount(id: account.id)
            storedValueAccounts.removeAll { $0.id == account.id }
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func save(_ bill: BillRecord) async {
        do {
            let savedBill = try repository.saveBill(bill)
            upsertBill(savedBill)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// 团购核销：确认彻底支出。
    func redeem(_ bill: BillRecord) async {
        guard bill.isGroupBuy, bill.billSettlementStatus == .active else { return }

        var record = bill
        record.settlementStatus = BillSettlementStatus.redeemed.rawValue
        record.redeemedAt = Self.milliseconds(for: Date())
        await save(record)
    }

    /// 退款 / 过期退回：从支出与统计中扣除（终态）。
    func refund(_ bill: BillRecord, reason: String? = nil) async {
        guard bill.billSettlementStatus != .refunded else { return }

        var record = bill
        record.settlementStatus = BillSettlementStatus.refunded.rawValue
        record.refundReason = reason
        await save(record)
    }

    func makeDuplicateDraft(from bill: BillRecord) -> BillRecord {
        bill.duplicated(
            occurredAt: Self.milliseconds(for: Date()),
            deviceID: repository.deviceID
        )
    }

    func deleteBill(id: String) async {
        await deleteBills(ids: [id])
    }

    func deleteBills(ids: [String]) async {
        guard ids.isEmpty == false else { return }

        do {
            try repository.deleteBills(ids: ids)
            bills.removeAll { ids.contains($0.id) }
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    static func milliseconds(for date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }

    private func searchableText(for bill: BillRecord) -> String {
        [
            bill.title,
            bill.billType.localizedTitle,
            bill.billType == .income ? bill.billIncomeCategory.localizedTitle : bill.billCategory.localizedTitle,
            bill.billPaymentMethod.localizedTitle,
            storedValueAccountName(id: bill.storedValueAccountID),
            bill.startLocation,
            bill.endLocation,
            bill.transportLines,
            bill.note
        ]
        .compactMap(\.self)
        .joined(separator: " ")
    }

    private func upsertBill(_ bill: BillRecord) {
        if let index = bills.firstIndex(where: { $0.id == bill.id }) {
            bills[index] = bill
        } else {
            bills.insert(bill, at: 0)
        }
    }

    private func upsertStoredValueAccount(_ account: StoredValueAccount) {
        if let index = storedValueAccounts.firstIndex(where: { $0.id == account.id }) {
            storedValueAccounts[index] = account
        } else {
            storedValueAccounts.append(account)
            storedValueAccounts.sort { $0.name < $1.name }
        }
    }
}
