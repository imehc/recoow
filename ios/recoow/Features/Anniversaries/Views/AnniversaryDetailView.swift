import SwiftUI

struct AnniversaryDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Bindable var viewModel: AnniversariesViewModel
    @State private var anniversaryForEditing: AnniversaryRecord?
    @State private var anniversaryPendingDeletion: AnniversaryRecord?

    let anniversaryID: String

    var body: some View {
        Group {
            if let anniversary = viewModel.anniversary(id: anniversaryID) {
                form(for: anniversary)
            } else {
                ContentUnavailableView("纪念日不存在", systemImage: "calendar.badge.exclamationmark")
            }
        }
        .navigationTitle("纪念日详情")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $anniversaryForEditing) { anniversary in
            NavigationStack {
                AnniversaryFormView(anniversary: anniversary, viewModel: viewModel)
            }
        }
        .task(id: anniversaryID) {
            await viewModel.loadAnniversaryIfNeeded(id: anniversaryID)
        }
    }

    private func form(for anniversary: AnniversaryRecord) -> some View {
        List {
            Section("概览") {
                LabeledContent("标题", value: anniversary.title)
                LabeledContent("分类", value: anniversary.category.localizedTitle)
                LabeledContent("原始日期", value: AppFormatters.date(milliseconds: anniversary.occurredAt, locale: locale))

                if let days = anniversary.daysUntilNext() {
                    LabeledContent(
                        days == 0 ? "状态" : "距离下次",
                        value: days == 0 ? AppLocalization.string("今天") : AppLocalization.format("%d 天", days)
                    )
                } else {
                    LabeledContent("状态", value: AppLocalization.string("已过去"))
                }

                LabeledContent("已过去", value: AppLocalization.format("%d 天", max(0, anniversary.daysSince)))

                if anniversary.isYearly, anniversary.yearsSince > 0 {
                    LabeledContent("周年", value: AppLocalization.format("%d 年", anniversary.yearsSince))
                }
            }

            Section("提醒") {
                LabeledContent("重复", value: AppLocalization.string(anniversary.isYearly ? "每年" : "不重复"))
                LabeledContent("提前提醒", value: anniversary.leadTime.localizedTitle)
                LabeledContent("通知", value: AppLocalization.string(anniversary.isEnabled ? "已开启" : "已关闭"))

                if let nextDate = anniversary.nextOccurrenceDate {
                    LabeledContent(
                        "下次提醒",
                        value: AppFormatters.dateTime(
                            milliseconds: AnniversariesViewModel.milliseconds(for: nextDate),
                            locale: locale
                        )
                    )
                }
            }

            if let note = anniversary.note {
                Section("备注") {
                    Text(note)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("编辑", systemImage: "pencil") {
                    anniversaryForEditing = anniversary
                }
            }

            ToolbarItem(placement: .bottomBar) {
                Button("删除", systemImage: "trash", role: .destructive) {
                    anniversaryPendingDeletion = anniversary
                }
            }
        }
        .alert(
            anniversaryPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($anniversaryPendingDeletion),
            presenting: anniversaryPendingDeletion
        ) { anniversary in
            Button("删除", role: .destructive) {
                deleteAnniversary(id: anniversary.id)
            }
            Button("取消", role: .cancel) {
                anniversaryPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    private func deleteAnniversary(id: String) {
        anniversaryPendingDeletion = nil

        Task {
            await viewModel.deleteAnniversary(id: id)
            dismiss()
        }
    }
}
