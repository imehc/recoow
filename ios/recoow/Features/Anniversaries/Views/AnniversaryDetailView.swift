import SwiftUI

struct AnniversaryDetailView: View {
    @Environment(\.dismiss) private var dismiss
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
                LabeledContent("分类", value: anniversary.category.title)
                LabeledContent("原始日期", value: AppFormatters.date(milliseconds: anniversary.occurredAt))

                if let days = anniversary.daysUntilNext() {
                    LabeledContent(days == 0 ? "状态" : "距离下次", value: days == 0 ? "今天" : "\(days) 天")
                } else {
                    LabeledContent("状态", value: "已过去")
                }

                LabeledContent("已过去", value: "\(max(0, anniversary.daysSince)) 天")

                if anniversary.isYearly, anniversary.yearsSince > 0 {
                    LabeledContent("周年", value: "\(anniversary.yearsSince) 年")
                }
            }

            Section("提醒") {
                LabeledContent("重复", value: anniversary.isYearly ? "每年" : "不重复")
                LabeledContent("提前提醒", value: anniversary.leadTime.localizedTitle)
                LabeledContent("通知", value: anniversary.isEnabled ? "已开启" : "已关闭")

                if let nextDate = anniversary.nextOccurrenceDate {
                    LabeledContent("下次提醒", value: AppFormatters.dateTime(milliseconds: AnniversariesViewModel.milliseconds(for: nextDate)))
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
        .alert(item: $anniversaryPendingDeletion) { anniversary in
            Alert(
                title: Text(AppLocalization.format("delete.record.title", anniversary.title)),
                message: Text(AppLocalization.string("删除后该记录会从历史中移除。")),
                primaryButton: .destructive(Text("删除")) {
                    deleteAnniversary(id: anniversary.id)
                },
                secondaryButton: .cancel(Text("取消")) {
                    anniversaryPendingDeletion = nil
                }
            )
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
