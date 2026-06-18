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
    var category: String
    var paymentMethod: String
    var note: String?
    var occurredAt: Int64
    var imageData: Data?

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
        case category
        case paymentMethod = "payment_method"
        case note
        case occurredAt = "occurred_at"
        case imageData = "image_data"
    }

    var occurredDate: Date {
        Date(timeIntervalSince1970: Double(occurredAt) / 1000)
    }

    var billCategory: BillCategory {
        BillCategory(rawValue: category) ?? .other
    }

    var billPaymentMethod: BillPaymentMethod {
        BillPaymentMethod(rawValue: paymentMethod) ?? .other
    }

    var hasDiscount: Bool {
        discountAmountCents > 0
    }

    static func makeNew(
        title: String,
        originalAmountCents: Int64,
        discountAmountCents: Int64,
        finalAmountCents: Int64,
        category: BillCategory,
        paymentMethod: BillPaymentMethod,
        note: String?,
        occurredAt: Int64,
        imageData: Data?,
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
            category: category.rawValue,
            paymentMethod: paymentMethod.rawValue,
            note: note,
            occurredAt: occurredAt,
            imageData: imageData
        )
    }
}
