import SwiftUI

struct BillFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var originalAmountText: String
    @State private var discountAmountText: String
    @State private var finalAmountText: String
    @State private var billType: BillType
    @State private var category: BillCategory
    @State private var incomeCategory: BillIncomeCategory
    @State private var paymentMethod: BillPaymentMethod
    @State private var startLocation: String
    @State private var endLocation: String
    @State private var note: String
    @State private var occurredDate: Date
    @State private var imageData: Data?
    @State private var isUpdatingFinalAmount = false
    @State private var isFinalAmountManuallyEdited = false
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
        _discountAmountText = State(initialValue: initialBill.map { AppFormatters.amountInput(cents: $0.discountAmountCents) } ?? "")
        _finalAmountText = State(initialValue: initialBill.map { AppFormatters.amountInput(cents: $0.finalAmountCents) } ?? "")
        _billType = State(initialValue: initialBill?.billType ?? .expense)
        _category = State(initialValue: initialBill?.billCategory ?? .dining)
        _incomeCategory = State(initialValue: initialBill?.billIncomeCategory ?? .salary)
        _paymentMethod = State(initialValue: initialBill?.billPaymentMethod ?? .wechat)
        _startLocation = State(initialValue: initialBill?.startLocation ?? "")
        _endLocation = State(initialValue: initialBill?.endLocation ?? "")
        _note = State(initialValue: initialBill?.note ?? "")
        _occurredDate = State(initialValue: initialBill?.occurredDate ?? Date())
        _imageData = State(initialValue: initialBill?.imageData)
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

                    LabeledContent("优惠") {
                        TextField("请输入优惠", text: $discountAmountText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .focused($focusedField, equals: "discountAmount")
                    }
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
                            Label(category.title, systemImage: category.systemImage)
                            .tag(category)
                        }
                    }
                } else {
                    Picker("收入类型", selection: $incomeCategory) {
                        ForEach(BillIncomeCategory.allCases) { category in
                            Label(category.title, systemImage: category.systemImage)
                                .tag(category)
                        }
                    }
                }

                Picker(billType == .expense ? "支付方式" : "收入渠道", selection: $paymentMethod) {
                    ForEach(BillPaymentMethod.allCases) { method in
                        Label(method.title, systemImage: method.systemImage)
                            .tag(method)
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
                }
            }

            EditablePhotoInputSection(
                imageData: $imageData,
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
            imageData: $imageData
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
            updateFinalAmountIfNeeded()
        }
        .onChange(of: discountAmountText) {
            updateFinalAmountIfNeeded()
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

    private var showsTransportFields: Bool {
        billType == .expense && category == .transport
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

    private var normalizedAmountValues: (original: Int64, discount: Int64, final: Int64)? {
        switch billType {
        case .expense:
            guard let originalAmountCents,
                  let discountAmountCents,
                  let finalAmountCents,
                  originalAmountCents > 0,
                  discountAmountCents <= originalAmountCents,
                  finalAmountCents >= 0
            else {
                return nil
            }

            return (originalAmountCents, discountAmountCents, finalAmountCents)
        case .income:
            guard let finalAmountCents, finalAmountCents > 0 else {
                return nil
            }

            return (finalAmountCents, 0, finalAmountCents)
        }
    }

    private var isSaveDisabled: Bool {
        trimmedTitle.isEmpty || normalizedAmountValues == nil
    }

    private func cancel() {
        dismiss()
    }

    private func updateFinalAmountIfNeeded() {
        guard isFinalAmountManuallyEdited == false,
              let originalAmountCents,
              let discountAmountCents
        else {
            return
        }

        let calculatedFinalAmount = max(0, originalAmountCents - discountAmountCents)
        isUpdatingFinalAmount = true
        finalAmountText = AppFormatters.amountInput(cents: calculatedFinalAmount)
    }

    private func handleFinalAmountChange() {
        if isUpdatingFinalAmount {
            isUpdatingFinalAmount = false
        } else {
            isFinalAmountManuallyEdited = true
        }
    }

    private func handleBillTypeChange() {
        switch billType {
        case .expense:
            if originalAmountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                originalAmountText = finalAmountText
            }
            updateFinalAmountIfNeeded()
        case .income:
            if finalAmountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                finalAmountText = originalAmountText
            }
            discountAmountText = ""
        }
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
            occurredDate: occurredDate,
            imageData: imageData
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
        record.occurredAt = BillsViewModel.milliseconds(for: occurredDate)
        record.imageData = imageData

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
}
