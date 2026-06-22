import AVFoundation
import Foundation
import Observation

@MainActor
@Observable
final class MediaAudioRecorder {
    var isRecording = false
    var elapsedSeconds: Double = 0
    var meterLevel: Double = 0
    var meterLevels: [Double] = Array(repeating: 0.08, count: meterSampleCount)
    var errorMessage: String?

    @ObservationIgnored private static let meterSampleCount = 44
    @ObservationIgnored private var recorder: AVAudioRecorder?
    @ObservationIgnored private var recordingURL: URL?
    @ObservationIgnored private var meterTask: Task<Void, Never>?

    func startRecording() async {
        guard isRecording == false else { return }

        let granted = await requestRecordPermission()
        guard granted else {
            errorMessage = AppLocalization.string("请在系统设置中允许麦克风权限")
            return
        }

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try session.setActive(true)

            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension("m4a")
            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
            ]
            let recorder = try AVAudioRecorder(url: url, settings: settings)
            recorder.isMeteringEnabled = true
            recorder.prepareToRecord()
            recorder.record()

            self.recorder = recorder
            recordingURL = url
            isRecording = true
            elapsedSeconds = 0
            meterLevel = 0
            resetMeterLevels()
            errorMessage = nil
            startMetering()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stopRecording(
        ownerType: MediaAttachmentOwnerType,
        ownerID: String,
        deviceID: String
    ) -> MediaAttachment? {
        guard let recorder, let recordingURL else { return nil }

        let duration = recorder.currentTime
        recorder.stop()
        stopMetering()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)

        defer {
            self.recorder = nil
            self.recordingURL = nil
            isRecording = false
            elapsedSeconds = 0
            meterLevel = 0
            resetMeterLevels()
            try? FileManager.default.removeItem(at: recordingURL)
        }

        do {
            let data = try Data(contentsOf: recordingURL)
            return MediaAttachment.makeNew(
                ownerType: ownerType,
                ownerID: ownerID,
                kind: .audio,
                title: AppLocalization.format("语音 %@", DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .short)),
                data: data,
                mimeType: "audio/mp4",
                durationSeconds: duration,
                deviceID: deviceID
            )
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func cancelRecording() {
        recorder?.stop()
        stopMetering()
        if let recordingURL {
            try? FileManager.default.removeItem(at: recordingURL)
        }

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        recorder = nil
        recordingURL = nil
        isRecording = false
        elapsedSeconds = 0
        meterLevel = 0
        resetMeterLevels()
    }

    private func startMetering() {
        meterTask?.cancel()
        meterTask = Task { @MainActor [weak self] in
            while Task.isCancelled == false {
                try? await Task.sleep(for: .milliseconds(60))
                guard let self, let recorder = self.recorder else { return }

                recorder.updateMeters()
                elapsedSeconds = recorder.currentTime
                let nextLevel = Self.normalizedLevel(from: recorder.averagePower(forChannel: 0))
                meterLevel = Self.smoothedLevel(previous: meterLevel, next: nextLevel)
                appendMeterLevel(meterLevel)
            }
        }
    }

    private func stopMetering() {
        meterTask?.cancel()
        meterTask = nil
    }

    private static func normalizedLevel(from decibels: Float) -> Double {
        guard decibels.isFinite else { return 0 }
        let floor: Float = -60
        let clamped = max(floor, min(0, decibels))
        let normalized = pow(10, clamped / 28)
        return min(1, max(0.04, Double(normalized)))
    }

    private static func smoothedLevel(previous: Double, next: Double) -> Double {
        if next > previous {
            previous * 0.35 + next * 0.65
        } else {
            previous * 0.72 + next * 0.28
        }
    }

    private func appendMeterLevel(_ level: Double) {
        var nextLevels = meterLevels
        nextLevels.append(level)

        if nextLevels.count > Self.meterSampleCount {
            nextLevels.removeFirst(nextLevels.count - Self.meterSampleCount)
        }

        meterLevels = nextLevels
    }

    private func resetMeterLevels() {
        meterLevels = Array(repeating: 0.08, count: Self.meterSampleCount)
    }

    private func requestRecordPermission() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            return true
        case .notDetermined:
            return await withCheckedContinuation { continuation in
                AVCaptureDevice.requestAccess(for: .audio) { granted in
                    continuation.resume(returning: granted)
                }
            }
        case .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }
}
