import SwiftUI

struct BillIconView: View {
    let bill: BillRecord
    let size: CGFloat

    var body: some View {
        AppIconTileView(systemImage: systemImage, tint: tint, size: size)
    }

    private var systemImage: String {
        switch bill.billType {
        case .expense:
            bill.billCategory.systemImage
        case .income:
            bill.billIncomeCategory.systemImage
        case .storedValueUse:
            "wallet.pass.fill"
        }
    }

    private var tint: Color {
        switch bill.billType {
        case .expense:
            AppPalette.teal
        case .income:
            AppPalette.green
        case .storedValueUse:
            AppPalette.amber
        }
    }
}
