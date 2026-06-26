import Foundation

/// 账单的结算状态。团购买入即计入支出，可在有效期内核销，亦可退款/过期退回。
enum BillSettlementStatus: String, CaseIterable, Codable, Sendable {
    /// 正常：买入即计入支出，尚未核销也未退款。
    case active
    /// 已核销：团购在有效期内核销，彻底确认支出。
    case redeemed
    /// 已退款 / 已退回：从支出与统计中扣除（终态，不可撤销）。
    case refunded
}
