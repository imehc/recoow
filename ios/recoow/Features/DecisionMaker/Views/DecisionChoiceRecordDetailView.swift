import SwiftUI

struct DecisionChoiceRecordDetailView: View {
    @Environment(AppContainer.self) private var container
    @State private var record: DecisionChoiceRecord?
    @State private var errorMessage: String?

    let recordID: String

    var body: some View {
        Group {
            if let record {
                Form {
                    Section {
                        HStack {
                            Spacer()
                            PhotoThumbnailView(imageData: record.optionImageData, systemImage: "sparkles", size: 180)
                            Spacer()
                        }
                        .padding(.vertical, 8)
                    }

                    Section("结果") {
                        LabeledContent("集合", value: record.collectionTitle)
                        LabeledContent("选中", value: record.optionTitle)
                        LabeledContent("时间", value: AppFormatters.dateTime(milliseconds: record.selectedAt))
                    }

                    if hasExtraInfo(record) {
                        Section("详情") {
                            if let detail = record.optionDetail, detail.isEmpty == false {
                                LabeledContent("描述", value: detail)
                            }

                            if let customInfo = record.optionCustomInfo, customInfo.isEmpty == false {
                                LabeledContent("自定义信息", value: customInfo)
                            }
                        }
                    }
                }
            } else if let errorMessage {
                ContentUnavailableView(errorMessage, systemImage: "exclamationmark.triangle")
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle(record?.optionTitle ?? "选择记录")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: recordID) {
            load()
        }
    }

    private func load() {
        do {
            record = try container.decisionRepository.fetchChoiceRecord(id: recordID)
            errorMessage = record == nil ? "记录不存在" : nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func hasExtraInfo(_ record: DecisionChoiceRecord) -> Bool {
        record.optionDetail?.isEmpty == false ||
            record.optionCustomInfo?.isEmpty == false
    }
}
