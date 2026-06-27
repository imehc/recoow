import Foundation
import Observation

@MainActor
@Observable
final class BillsViewModel {
    var bills: [BillRecord] = []
    var searchText = ""
    var selectedBillType: BillType?
    var selectedCategory: BillCategory?
    var selectedIncomeCategory: BillIncomeCategory?
    var selectedPaymentMethod: BillPaymentMethod?
    var errorMessage: String?

    @ObservationIgnored private let repository: BillRepository
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?

    init(repository: BillRepository, syncEngine: any SyncEngine) {
        self.repository = repository
        self.syncEngine = syncEngine
    }

    deinit {
        observationTask?.cancel()
    }

    var filteredBills: [BillRecord] {
        bills.filter { bill in
            let matchesType = selectedBillType == nil || bill.billType == selectedBillType
            let matchesCategory = selectedCategory == nil || (bill.billType == .expense && bill.billCategory == selectedCategory)
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

    var currentMonthTotalCents: Int64 {
        currentMonthExpenseBills.reduce(0) { $0 + $1.countedAmountCents }
    }

    var currentMonthIncomeCents: Int64 {
        currentMonthIncomeBills.reduce(0) { $0 + $1.countedAmountCents }
    }

    var currentMonthDiscountCents: Int64 {
        currentMonthExpenseBills.reduce(0) { $0 + $1.countedDiscountCents }
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
    }

    func bill(id: String) -> BillRecord? {
        bills.first { $0.id == id }
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
        groupBuyValidUntil: Int64? = nil
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
            deviceID: repository.deviceID
        )
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
            bill.billType == .expense ? bill.billCategory.localizedTitle : bill.billIncomeCategory.localizedTitle,
            bill.billPaymentMethod.localizedTitle,
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
}
