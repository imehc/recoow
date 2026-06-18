import Foundation
import SwiftUI

enum BillPaymentMethod: String, CaseIterable, Identifiable, Codable, Sendable {
    case wechat
    case alipay
    case douyinPay
    case unionPay
    case bankCard
    case cash
    case creditCard
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .wechat:
            "微信"
        case .alipay:
            "支付宝"
        case .douyinPay:
            "抖音"
        case .unionPay:
            "云闪付"
        case .bankCard:
            "银行卡"
        case .cash:
            "现金"
        case .creditCard:
            "信用卡"
        case .other:
            "其他"
        }
    }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(title)
    }

    var localizedTitle: String {
        AppLocalization.string(title)
    }

    var systemImage: String {
        switch self {
        case .wechat, .alipay, .douyinPay, .unionPay:
            "qrcode"
        case .bankCard:
            "building.columns.fill"
        case .cash:
            "banknote.fill"
        case .creditCard:
            "creditcard.fill"
        case .other:
            "wallet.pass.fill"
        }
    }
}
