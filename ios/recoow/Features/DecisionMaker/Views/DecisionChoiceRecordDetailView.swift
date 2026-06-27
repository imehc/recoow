import SwiftUI

struct DecisionChoiceRecordDetailView: View {
    @Environment(AppContainer.self) private var container
    @State private var record: DecisionChoiceRecord?
    @State private var errorMessage: String?

    let recordID: String
    var choiceRecordImageTransition: Namespace.ID? = nil

    var body: some View {
        content
            .navigationTitle(record?.optionTitle ?? "选择记录")
            .navigationBarTitleDisplayMode(.inline)
            .navigationTransitionIfAvailable(sourceID: recordID, in: choiceRecordImageTransition)
            .task(id: recordID) {
                load()
            }
    }

    @ViewBuilder
    private var content: some View {
        if let record {
            Form {
                if let imageData = record.resolvedImageData {
                    Section {
                        PhotoSquareImageView(imageData: imageData, systemImage: "sparkles")
                            .padding(.vertical, 8)
                    }
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

private extension View {
    @ViewBuilder
    func navigationTransitionIfAvailable(sourceID: String, in namespace: Namespace.ID?) -> some View {
        if let namespace {
            navigationTransition(.zoom(sourceID: sourceID, in: namespace))
        } else {
            self
        }
    }
}
