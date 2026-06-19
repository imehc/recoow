import SwiftUI

struct BillMetadataLineView: View {
    let bill: BillRecord

    var body: some View {
        MetadataLineView {
            MetadataItemView(
                titleKey: bill.billType.titleKey,
                systemImage: bill.billType.systemImage
            )

            if bill.billType == .expense {
                MetadataItemView(
                    titleKey: bill.billCategory.titleKey,
                    systemImage: bill.billCategory.systemImage
                )
            } else {
                MetadataItemView(
                    titleKey: bill.billIncomeCategory.titleKey,
                    systemImage: bill.billIncomeCategory.systemImage
                )
            }

            MetadataItemView(
                titleKey: bill.billPaymentMethod.titleKey,
                systemImage: bill.billPaymentMethod.systemImage
            )
        }
    }
}
