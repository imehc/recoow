import Foundation
import GRDB

/// 一条本地账单记录。金额以分为单位保存，避免小数计算误差。
struct BillRecord: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "bills"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var title: String
    var originalAmountCents: Int64
    var discountAmountCents: Int64
    var finalAmountCents: Int64
    var transactionType: String
    var category: String
    var paymentMethod: String
    var note: String?
    var startLocation: String?
    var endLocation: String?
    var transportLines: String?
    var occurredAt: Int64
    var imageData: Data?
    var settlementStatus: String
    var groupBuyValidUntil: Int64?
    var redeemedAt: Int64?
    var refundReason: String?

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case title
        case originalAmountCents = "original_amount_cents"
        case discountAmountCents = "discount_amount_cents"
        case finalAmountCents = "final_amount_cents"
        case transactionType = "transaction_type"
        case category
        case paymentMethod = "payment_method"
        case note
        case startLocation = "start_location"
        case endLocation = "end_location"
        case transportLines = "transport_lines"
        case occurredAt = "occurred_at"
        case imageData = "image_data"
        case settlementStatus = "settlement_status"
        case groupBuyValidUntil = "group_buy_valid_until"
        case redeemedAt = "redeemed_at"
        case refundReason = "refund_reason"
    }

    var occurredDate: Date {
        Date(timeIntervalSince1970: Double(occurredAt) / 1000)
    }

    var billCategory: BillCategory {
        BillCategory(rawValue: category) ?? .other
    }

    var billIncomeCategory: BillIncomeCategory {
        BillIncomeCategory(rawValue: category) ?? .other
    }

    var billType: BillType {
        BillType(rawValue: transactionType) ?? .expense
    }

    var billPaymentMethod: BillPaymentMethod {
        BillPaymentMethod(rawValue: paymentMethod) ?? .other
    }

    var billSettlementStatus: BillSettlementStatus {
        BillSettlementStatus(rawValue: settlementStatus) ?? .active
    }

    var groupBuyValidUntilDate: Date? {
        groupBuyValidUntil.map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    var redeemedAtDate: Date? {
        redeemedAt.map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    /// 是否为团购支出。
    var isGroupBuy: Bool {
        billType == .expense && billCategory == .groupBuy
    }

    /// 团购已过有效期且未核销（可退回）。
    var isExpired: Bool {
        guard isGroupBuy, billSettlementStatus == .active, let validUntil = groupBuyValidUntil else {
            return false
        }

        return validUntil < SyncableTimestamp.nowMilliseconds()
    }

    /// 展示用生命周期状态。
    var lifecycleState: BillLifecycleState {
        switch billSettlementStatus {
        case .refunded:
            return .refunded
        case .redeemed:
            return .redeemed
        case .active:
            // 团购过期未核销，自动视为已退款。
            return isExpired ? .refunded : .normal
        }
    }

    /// 是否已作废，不计入所属类型的合计与统计。
    var isVoided: Bool {
        billSettlementStatus == .refunded || isExpired
    }

    /// 计入合计的金额：作废后为 0。
    var countedAmountCents: Int64 {
        isVoided ? 0 : finalAmountCents
    }

    /// 计入合计的优惠金额：作废后为 0。
    var countedDiscountCents: Int64 {
        isVoided ? 0 : discountAmountCents
    }

    var hasDiscount: Bool {
        billType == .expense && discountAmountCents > 0
    }

    var normalizedStartLocation: String? {
        normalizedLocation(startLocation)
    }

    var normalizedEndLocation: String? {
        normalizedLocation(endLocation)
    }

    var normalizedTransportLines: String? {
        normalizedMultilineText(transportLines)
    }

    var transportLinesSummary: String? {
        normalizedTransportLines?.components(separatedBy: .newlines).joined(separator: " / ")
    }

    var displayAmount: String {
        switch billType {
        case .expense:
            "-\(AppFormatters.money(cents: finalAmountCents))"
        case .income:
            "+\(AppFormatters.money(cents: finalAmountCents))"
        }
    }

    static func makeNew(
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
        occurredAt: Int64,
        imageData: Data?,
        settlementStatus: BillSettlementStatus = .active,
        groupBuyValidUntil: Int64? = nil,
        redeemedAt: Int64? = nil,
        refundReason: String? = nil,
        deviceID: String
    ) -> BillRecord {
        let now = SyncableTimestamp.nowMilliseconds()
        return BillRecord(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            title: title,
            originalAmountCents: originalAmountCents,
            discountAmountCents: discountAmountCents,
            finalAmountCents: finalAmountCents,
            transactionType: billType.rawValue,
            category: categoryRawValue,
            paymentMethod: paymentMethod.rawValue,
            note: note,
            startLocation: startLocation,
            endLocation: endLocation,
            transportLines: transportLines,
            occurredAt: occurredAt,
            imageData: imageData,
            settlementStatus: settlementStatus.rawValue,
            groupBuyValidUntil: groupBuyValidUntil,
            redeemedAt: redeemedAt,
            refundReason: refundReason
        )
    }

    func duplicated(occurredAt: Int64, deviceID: String) -> BillRecord {
        let now = SyncableTimestamp.nowMilliseconds()
        var copy = self
        copy.id = UUID().uuidString
        copy.createdAt = now
        copy.updatedAt = now
        copy.deletedAt = nil
        copy.syncStatus = .pending
        copy.deviceID = deviceID
        copy.serverVersion = nil
        copy.occurredAt = occurredAt
        copy.settlementStatus = BillSettlementStatus.active.rawValue
        copy.redeemedAt = nil
        copy.refundReason = nil
        return copy
    }

    private func normalizedLocation(_ value: String?) -> String? {
        let trimmedValue = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmedValue.isEmpty ? nil : trimmedValue
    }

    private func normalizedMultilineText(_ value: String?) -> String? {
        let lines = value?
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.isEmpty == false } ?? []

        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }
}
