import SwiftUI

struct BillIconView: View {
    let bill: BillRecord
    let size: CGFloat

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(tint.gradient, in: RoundedRectangle(cornerRadius: 8))
    }

    private var systemImage: String {
        switch bill.billType {
        case .expense:
            bill.billCategory.systemImage
        case .income:
            bill.billIncomeCategory.systemImage
        }
    }

    private var tint: Color {
        switch bill.billType {
        case .expense:
            .teal
        case .income:
            .green
        }
    }
}
