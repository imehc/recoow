import SwiftUI

struct BillFormView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var originalAmountText: String
    @State private var discountAmountText: String
    @State private var discountRateText: String
    @State private var finalAmountText: String
    @State private var billType: BillType
    @State private var category: BillCategory
    @State private var incomeCategory: BillIncomeCategory
    @State private var paymentMethod: BillPaymentMethod
    @State private var startLocation: String
    @State private var endLocation: String
    @State private var transportLines: String
    @State private var note: String
    @State private var occurredDate: Date
    @State private var groupBuyValidUntil: Date
    @State private var hasGroupBuyValidUntil: Bool
    @State private var imageData: Data?
    @State private var imageAssetID: String?
    @State private var isUpdatingAmountFields = false
    @State private var amountCompanionField: String?
    @State private var photoInputCoordinator = EditablePhotoInputCoordinator()
    @FocusState private var focusedField: String?

    let bill: BillRecord?
    let viewModel: BillsViewModel

    init(bill: BillRecord?, viewModel: BillsViewModel, prefillBill: BillRecord? = nil) {
        self.bill = bill
        self.viewModel = viewModel

        let initialBill = bill ?? prefillBill
        _title = State(initialValue: initialBill?.title ?? "")
        _originalAmountText = State(initialValue: initialBill.map { AppFormatters.amountInput(cents: $0.originalAmountCents) } ?? "")
        _discountAmountText = State(initialValue: Self.discountAmountInput(for: initialBill))
        _discountRateText = State(initialValue: Self.discountRateInput(for: initialBill))
        _finalAmountText = State(initialValue: initialBill.map { AppFormatters.amountInput(cents: $0.finalAmountCents) } ?? "")
        _billType = State(initialValue: initialBill?.billType ?? .expense)
        _category = State(initialValue: initialBill?.billCategory ?? .dining)
        _incomeCategory = State(initialValue: initialBill?.billIncomeCategory ?? .salary)
        _paymentMethod = State(initialValue: initialBill?.billPaymentMethod ?? .wechat)
        _startLocation = State(initialValue: initialBill?.startLocation ?? "")
        _endLocation = State(initialValue: initialBill?.endLocation ?? "")
        _transportLines = State(initialValue: initialBill?.transportLines ?? "")
        _note = State(initialValue: initialBill?.note ?? "")
        _occurredDate = State(initialValue: initialBill?.occurredDate ?? Date())
        _groupBuyValidUntil = State(initialValue: initialBill?.groupBuyValidUntilDate ?? Date())
        _hasGroupBuyValidUntil = State(initialValue: initialBill?.groupBuyValidUntilDate != nil)
        _imageData = State(initialValue: initialBill?.imageData)
        _imageAssetID = State(initialValue: initialBill?.imageAssetID)
        _amountCompanionField = State(initialValue: initialBill == nil ? "discountAmount" : "finalAmount")
    }

    var body: some View {
        Form {
            Section("基础信息") {
                Picker("类型", selection: $billType) {
                    ForEach(BillType.allCases) { type in
                        Label(type.titleKey, systemImage: type.systemImage)
                            .tag(type)
                    }
                }
                .pickerStyle(.segmented)

                LabeledContent("标题") {
                    TextField("请输入标题", text: $title)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "title")
                }

                DatePicker(
                    "日期",
                    selection: $occurredDate,
                    displayedComponents: [.date, .hourAndMinute]
                )
            }

            Section("金额") {
                if billType == .expense {
                    LabeledContent("原价") {
                        TextField("请输入原价", text: $originalAmountText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .focused($focusedField, equals: "originalAmount")
                    }

                    discountInputRow
                }

                LabeledContent(billType == .expense ? "实付" : "金额") {
                    TextField(billType == .expense ? "请输入实付金额" : "请输入金额", text: $finalAmountText)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "finalAmount")
                }
            }

            Section("分类") {
                if billType == .expense {
                    Picker("分类", selection: $category) {
                        ForEach(BillCategory.allCases) { category in
                            Label(category.titleKey, systemImage: category.systemImage)
                            .tag(category)
                        }
                    }
                } else {
                    Picker("收入类型", selection: $incomeCategory) {
                        ForEach(BillIncomeCategory.allCases) { category in
                            Label(category.titleKey, systemImage: category.systemImage)
                                .tag(category)
                        }
                    }
                }

                Picker(billType == .expense ? "支付方式" : "收入渠道", selection: $paymentMethod) {
                    ForEach(BillPaymentMethod.allCases) { method in
                        Label(method.titleKey, systemImage: method.systemImage)
                            .tag(method)
                    }
                }
            }

            if showsGroupBuyFields {
                Section("团购") {
                    Toggle("设置有效期", isOn: $hasGroupBuyValidUntil)

                    if hasGroupBuyValidUntil {
                        DatePicker(
                            "有效期",
                            selection: $groupBuyValidUntil,
                            displayedComponents: [.date, .hourAndMinute]
                        )
                    }
                }
            }

            if showsTransportFields {
                Section("出行信息") {
                    LabeledContent("起点") {
                        TextField("请输入起点", text: $startLocation)
                            .multilineTextAlignment(.trailing)
                            .textInputAutocapitalization(.never)
                            .focused($focusedField, equals: "startLocation")
                    }

                    LabeledContent("终点") {
                        TextField("请输入终点", text: $endLocation)
                            .multilineTextAlignment(.trailing)
                            .textInputAutocapitalization(.never)
                            .focused($focusedField, equals: "endLocation")
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text("线路")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        TextField("请输入线路", text: $transportLines, axis: .vertical)
                            .lineLimit(1...4)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: "transportLines")
                    }
                }
            }

            EditablePhotoInputSection(
                imageData: $imageData,
                imageAssetID: $imageAssetID,
                placeholderSystemImage: "receipt",
                coordinator: photoInputCoordinator
            )

            Section("备注") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("备注")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField("请输入备注", text: $note, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "note")
                }
            }
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(bill == nil ? "添加账单" : "编辑账单")
        .navigationBarTitleDisplayMode(.inline)
        .editablePhotoInputPresentation(
            coordinator: photoInputCoordinator,
            imageData: $imageData,
            imageAssetID: $imageAssetID,
            mediaAssetRepository: container.mediaAssetRepository
        )
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存", action: save)
                    .disabled(isSaveDisabled)
            }
        }
        .onChange(of: originalAmountText) {
            handleOriginalAmountChange()
        }
        .onChange(of: discountAmountText) {
            handleDiscountAmountChange()
        }
        .onChange(of: discountRateText) {
            handleDiscountRateChange()
        }
        .onChange(of: finalAmountText) {
            handleFinalAmountChange()
        }
        .onChange(of: billType) {
            handleBillTypeChange()
        }
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedNote: String? {
        let value = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedStartLocation: String? {
        guard showsTransportFields else { return nil }

        let value = startLocation.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedEndLocation: String? {
        guard showsTransportFields else { return nil }

        let value = endLocation.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedTransportLines: String? {
        guard showsTransportFields else { return nil }

        let lines = transportLines
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.isEmpty == false }

        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }

    private var showsTransportFields: Bool {
        billType == .expense && category == .transport
    }

    private var showsGroupBuyFields: Bool {
        billType == .expense && category == .groupBuy
    }

    private var groupBuyValidUntilMilliseconds: Int64? {
        guard showsGroupBuyFields, hasGroupBuyValidUntil else { return nil }
        return BillsViewModel.milliseconds(for: groupBuyValidUntil)
    }

    private var discountInputRow: some View {
        LabeledContent("优惠") {
            HStack(spacing: 8) {
                TextField("优惠金额", text: $discountAmountText)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .focused($focusedField, equals: "discountAmount")
                    .frame(minWidth: 72)

                Divider()
                    .frame(height: 22)

                HStack(spacing: 2) {
                    TextField("折扣", text: $discountRateText)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "discountRate")
                        .frame(minWidth: 46, maxWidth: 64)

                    Text("折")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var originalAmountCents: Int64? {
        AppFormatters.cents(from: originalAmountText)
    }

    private var discountAmountCents: Int64? {
        if discountAmountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return 0
        }

        return AppFormatters.cents(from: discountAmountText)
    }

    private var finalAmountCents: Int64? {
        AppFormatters.cents(from: finalAmountText)
    }

    private var discountRateFraction: Decimal? {
        Self.discountRateFraction(from: discountRateText)
    }

    private var normalizedAmountValues: (original: Int64, discount: Int64, final: Int64)? {
        switch billType {
        case .expense:
            return normalizedExpenseAmountValues
        case .income:
            guard let finalAmountCents, finalAmountCents > 0 else {
                return nil
            }

            return (finalAmountCents, 0, finalAmountCents)
        }
    }

    private var normalizedExpenseAmountValues: (original: Int64, discount: Int64, final: Int64)? {
        guard let originalAmountCents, originalAmountCents > 0 else {
            return nil
        }

        switch focusedField {
        case "discountAmount":
            return expenseAmountValuesFromDiscountAmount(originalCents: originalAmountCents)
        case "discountRate":
            return expenseAmountValuesFromDiscountRate(originalCents: originalAmountCents)
        case "originalAmount":
            return expenseAmountValuesFromOriginalAmount(originalCents: originalAmountCents)
        default:
            return expenseAmountValuesFromFinalAmount(originalCents: originalAmountCents)
                ?? expenseAmountValuesFromDiscountAmount(originalCents: originalAmountCents)
                ?? expenseAmountValuesFromDiscountRate(originalCents: originalAmountCents)
        }
    }

    private func expenseAmountValuesFromOriginalAmount(originalCents: Int64) -> (original: Int64, discount: Int64, final: Int64)? {
        switch amountCompanionField {
        case "discountRate":
            return expenseAmountValuesFromDiscountRate(originalCents: originalCents)
        case "discountAmount":
            return expenseAmountValuesFromDiscountAmount(originalCents: originalCents)
        case "finalAmount":
            return expenseAmountValuesFromFinalAmount(originalCents: originalCents)
        default:
            return expenseAmountValuesFromFinalAmount(originalCents: originalCents)
                ?? expenseAmountValuesFromDiscountAmount(originalCents: originalCents)
                ?? expenseAmountValuesFromDiscountRate(originalCents: originalCents)
        }
    }

    private func expenseAmountValuesFromFinalAmount(originalCents: Int64) -> (original: Int64, discount: Int64, final: Int64)? {
        guard let finalAmountCents,
              (0...originalCents).contains(finalAmountCents) else {
            return nil
        }

        return (originalCents, originalCents - finalAmountCents, finalAmountCents)
    }

    private func expenseAmountValuesFromDiscountAmount(originalCents: Int64) -> (original: Int64, discount: Int64, final: Int64)? {
        guard let discountAmountCents,
              (0...originalCents).contains(discountAmountCents) else {
            return nil
        }

        return (originalCents, discountAmountCents, originalCents - discountAmountCents)
    }

    private func expenseAmountValuesFromDiscountRate(originalCents: Int64) -> (original: Int64, discount: Int64, final: Int64)? {
        guard let discountRateFraction,
              discountRateFraction >= 0,
              discountRateFraction <= 1,
              let finalCents = Self.finalCents(originalCents: originalCents, rateFraction: discountRateFraction) else {
            return nil
        }

        return (originalCents, originalCents - finalCents, finalCents)
    }

    private var isSaveDisabled: Bool {
        if trimmedTitle.isEmpty || normalizedAmountValues == nil {
            return true
        }

        // 团购有效期必填。
        if showsGroupBuyFields, hasGroupBuyValidUntil == false {
            return true
        }

        return false
    }

    private func cancel() {
        dismiss()
    }

    private func handleOriginalAmountChange() {
        guard focusedField == "originalAmount" else { return }
        guard beginAmountFieldUpdateIfNeeded() else { return }
        defer { isUpdatingAmountFields = false }
        syncAmountsFromOriginalAmount()
    }

    private func handleDiscountAmountChange() {
        guard focusedField == "discountAmount" else { return }
        guard beginAmountFieldUpdateIfNeeded() else { return }
        defer { isUpdatingAmountFields = false }
        amountCompanionField = "discountAmount"
        syncAmountsFromDiscountAmount()
    }

    private func handleDiscountRateChange() {
        guard focusedField == "discountRate" else { return }
        guard beginAmountFieldUpdateIfNeeded() else { return }
        defer { isUpdatingAmountFields = false }
        amountCompanionField = "discountRate"
        syncAmountsFromDiscountRate()
    }

    private func handleFinalAmountChange() {
        guard focusedField == "finalAmount" else { return }
        guard beginAmountFieldUpdateIfNeeded() else { return }
        defer { isUpdatingAmountFields = false }
        amountCompanionField = "finalAmount"
        syncAmountsFromFinalAmount()
    }

    private func handleBillTypeChange() {
        switch billType {
        case .expense:
            if originalAmountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                originalAmountText = finalAmountText
            }
            guard beginAmountFieldUpdateIfNeeded() else { return }
            defer { isUpdatingAmountFields = false }
            syncAmountsFromOriginalAmount()
        case .income:
            if finalAmountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                finalAmountText = originalAmountText
            }
            discountAmountText = ""
            discountRateText = ""
        }
    }

    private func beginAmountFieldUpdateIfNeeded() -> Bool {
        if isUpdatingAmountFields {
            return false
        }

        isUpdatingAmountFields = true
        return true
    }

    private func syncAmountsFromOriginalAmount() {
        guard billType == .expense,
              let originalAmountCents,
              originalAmountCents > 0
        else {
            updateFinalAndDiscountFields(finalCents: nil, originalCents: originalAmountCents)
            updateDiscountRateText(finalCents: nil, originalCents: originalAmountCents)
            return
        }

        switch amountCompanionField {
        case "discountRate" where discountRateFraction != nil:
            syncAmountsFromDiscountRate()
        case "discountAmount" where discountAmountCents != nil:
            syncAmountsFromDiscountAmount()
        case "finalAmount" where finalAmountCents != nil:
            syncAmountsFromFinalAmount()
        default:
            if discountRateFraction != nil {
                syncAmountsFromDiscountRate()
            } else if discountAmountCents != nil {
                syncAmountsFromDiscountAmount()
            } else if finalAmountCents != nil {
                syncAmountsFromFinalAmount()
            } else {
                updateFinalAndDiscountFields(finalCents: originalAmountCents, originalCents: originalAmountCents)
                updateDiscountRateText(finalCents: originalAmountCents, originalCents: originalAmountCents)
            }
        }
    }

    private func syncAmountsFromFinalAmount() {
        guard billType == .expense,
              let originalAmountCents,
              originalAmountCents > 0,
              let finalAmountCents
        else {
            updateDiscountFields(discountCents: nil, originalCents: originalAmountCents)
            return
        }

        guard finalAmountCents <= originalAmountCents else {
            updateDiscountFields(discountCents: nil, originalCents: originalAmountCents)
            return
        }

        updateDiscountFields(
            discountCents: originalAmountCents - finalAmountCents,
            originalCents: originalAmountCents
        )
    }

    private func syncAmountsFromDiscountAmount() {
        guard billType == .expense,
              let originalAmountCents,
              originalAmountCents > 0,
              let discountAmountCents
        else {
            updateFinalAndRateFields(finalCents: nil, originalCents: originalAmountCents)
            return
        }

        guard discountAmountCents <= originalAmountCents else {
            updateFinalAndRateFields(finalCents: nil, originalCents: originalAmountCents)
            return
        }

        updateFinalAndRateFields(
            finalCents: originalAmountCents - discountAmountCents,
            originalCents: originalAmountCents
        )
    }

    private func syncAmountsFromDiscountRate() {
        guard billType == .expense,
              let originalAmountCents,
              originalAmountCents > 0,
              let rateFraction = discountRateFraction
        else {
            updateFinalAndDiscountFields(finalCents: nil, originalCents: originalAmountCents)
            return
        }

        let normalizedRateFraction = min(max(rateFraction, Decimal(0)), Decimal(1))
        guard let finalCents = Self.finalCents(originalCents: originalAmountCents, rateFraction: normalizedRateFraction) else {
            updateFinalAndDiscountFields(finalCents: nil, originalCents: originalAmountCents)
            return
        }

        updateFinalAndDiscountFields(finalCents: finalCents, originalCents: originalAmountCents)
    }

    private func updateDiscountFields(discountCents: Int64?, originalCents: Int64?) {
        if let discountCents, focusedField != "discountAmount" {
            discountAmountText = AppFormatters.amountInput(cents: discountCents)
        } else if focusedField != "discountAmount" {
            discountAmountText = ""
        }

        updateDiscountRateText(
            finalCents: originalCents.flatMap { original in
                discountCents.map { max(0, original - $0) }
            },
            originalCents: originalCents
        )
    }

    private func updateFinalAndRateFields(finalCents: Int64?, originalCents: Int64?) {
        if let finalCents, focusedField != "finalAmount" {
            finalAmountText = AppFormatters.amountInput(cents: finalCents)
        } else if focusedField != "finalAmount" {
            finalAmountText = ""
        }

        updateDiscountRateText(finalCents: finalCents, originalCents: originalCents)
    }

    private func updateFinalAndDiscountFields(finalCents: Int64?, originalCents: Int64?) {
        if let finalCents, focusedField != "finalAmount" {
            finalAmountText = AppFormatters.amountInput(cents: finalCents)
        } else if focusedField != "finalAmount" {
            finalAmountText = ""
        }

        if let originalCents, let finalCents, focusedField != "discountAmount" {
            discountAmountText = AppFormatters.amountInput(cents: max(0, originalCents - finalCents))
        } else if focusedField != "discountAmount" {
            discountAmountText = ""
        }
    }

    private func updateDiscountRateText(finalCents: Int64?, originalCents: Int64?) {
        guard focusedField != "discountRate" else { return }

        guard let originalCents,
              originalCents > 0,
              let finalCents
        else {
            discountRateText = ""
            return
        }

        discountRateText = Self.discountRateInput(originalCents: originalCents, finalCents: finalCents)
    }

    private func save() {
        guard let amountValues = normalizedAmountValues else {
            return
        }

        var record = bill ?? viewModel.makeBill(
            title: trimmedTitle,
            originalAmountCents: amountValues.original,
            discountAmountCents: amountValues.discount,
            finalAmountCents: amountValues.final,
            billType: billType,
            categoryRawValue: categoryRawValue,
            paymentMethod: paymentMethod,
            note: normalizedNote,
            startLocation: normalizedStartLocation,
            endLocation: normalizedEndLocation,
            transportLines: normalizedTransportLines,
            occurredDate: occurredDate,
            imageData: imageReference.independentData,
            imageAssetID: imageReference.assetID
        )

        record.title = trimmedTitle
        record.originalAmountCents = amountValues.original
        record.discountAmountCents = amountValues.discount
        record.finalAmountCents = amountValues.final
        record.transactionType = billType.rawValue
        record.category = categoryRawValue
        record.paymentMethod = paymentMethod.rawValue
        record.note = normalizedNote
        record.startLocation = normalizedStartLocation
        record.endLocation = normalizedEndLocation
        record.transportLines = normalizedTransportLines
        record.occurredAt = BillsViewModel.milliseconds(for: occurredDate)
        record.setImageReference(imageReference)
        record.groupBuyValidUntil = groupBuyValidUntilMilliseconds

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }

    private var categoryRawValue: String {
        switch billType {
        case .expense:
            category.rawValue
        case .income:
            incomeCategory.rawValue
        }
    }

    private var imageReference: ImageReference {
        ImageReference(data: imageData, assetID: imageAssetID)
    }

    private static func discountRateInput(for bill: BillRecord?) -> String {
        guard let bill, bill.billType == .expense else { return "" }
        return discountRateInput(originalCents: bill.originalAmountCents, finalCents: bill.finalAmountCents)
    }

    private static func discountAmountInput(for bill: BillRecord?) -> String {
        guard let bill else { return "" }

        if bill.billType == .expense,
           bill.originalAmountCents >= bill.finalAmountCents {
            return AppFormatters.amountInput(cents: bill.originalAmountCents - bill.finalAmountCents)
        }

        return AppFormatters.amountInput(cents: bill.discountAmountCents)
    }

    private static func discountRateInput(originalCents: Int64, finalCents: Int64) -> String {
        guard originalCents > 0 else { return "" }

        let rate = truncatedDecimal(Decimal(finalCents) / Decimal(originalCents) * Decimal(10), scale: 2)
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 2
        return formatter.string(from: NSDecimalNumber(decimal: rate)) ?? "\(rate)"
    }

    private static func discountRateFraction(from text: String) -> Decimal? {
        let sanitized = text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: "")
            .replacingOccurrences(of: "折", with: "")
            .replacingOccurrences(of: "%", with: "")

        guard sanitized.isEmpty == false,
              let value = Decimal(string: sanitized),
              value >= 0 else {
            return nil
        }

        if value <= 10 {
            return value / 10
        }

        if value <= 100 {
            return value / 100
        }

        return nil
    }

    private static func finalCents(originalCents: Int64, rateFraction: Decimal) -> Int64? {
        let cents = truncatedDecimal(Decimal(originalCents) * rateFraction, scale: 0)
        return NSDecimalNumber(decimal: cents).int64Value
    }

    private static func truncatedDecimal(_ value: Decimal, scale: Int) -> Decimal {
        var source = value
        var result = Decimal()
        NSDecimalRound(&result, &source, scale, .down)
        return result
    }
}
