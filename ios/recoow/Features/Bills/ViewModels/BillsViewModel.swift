import Foundation
import Observation

@MainActor
@Observable
final class BillsViewModel {
    var bills: [BillRecord] = []
    var searchText = ""
    var selectedCategory: BillCategory?
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
            let matchesCategory = selectedCategory == nil || bill.billCategory == selectedCategory
            let matchesPaymentMethod = selectedPaymentMethod == nil || bill.billPaymentMethod == selectedPaymentMethod
            guard matchesCategory && matchesPaymentMethod else { return false }

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

    var currentMonthTotalCents: Int64 {
        currentMonthBills.reduce(0) { $0 + $1.finalAmountCents }
    }

    var currentMonthDiscountCents: Int64 {
        currentMonthBills.reduce(0) { $0 + $1.discountAmountCents }
    }

    var todayTotalCents: Int64 {
        bills
            .filter { Calendar.current.isDateInToday($0.occurredDate) }
            .reduce(0) { $0 + $1.finalAmountCents }
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

    func makeBill(
        title: String,
        originalAmountCents: Int64,
        discountAmountCents: Int64,
        finalAmountCents: Int64,
        category: BillCategory,
        paymentMethod: BillPaymentMethod,
        note: String?,
        occurredDate: Date,
        imageData: Data?
    ) -> BillRecord {
        BillRecord.makeNew(
            title: title,
            originalAmountCents: originalAmountCents,
            discountAmountCents: discountAmountCents,
            finalAmountCents: finalAmountCents,
            category: category,
            paymentMethod: paymentMethod,
            note: note,
            occurredAt: Self.milliseconds(for: occurredDate),
            imageData: imageData,
            deviceID: repository.deviceID
        )
    }

    func save(_ bill: BillRecord) async {
        do {
            _ = try repository.saveBill(bill)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteBill(id: String) async {
        await deleteBills(ids: [id])
    }

    func deleteBills(ids: [String]) async {
        guard ids.isEmpty == false else { return }

        do {
            try repository.deleteBills(ids: ids)
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
            bill.billCategory.localizedTitle,
            bill.billPaymentMethod.localizedTitle,
            bill.note
        ]
        .compactMap(\.self)
        .joined(separator: " ")
    }
}
