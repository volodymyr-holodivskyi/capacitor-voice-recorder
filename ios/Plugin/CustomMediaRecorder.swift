import Foundation
import AVFoundation

class CustomMediaRecorder {

    var options: RecordOptions!
    private var recordingSession: AVAudioSession!
    private var audioRecorder: AVAudioRecorder!
    private var audioFilePath: URL!
    private var originalRecordingSessionCategory: AVAudioSession.Category!
    private var status = CurrentRecordingStatus.NONE
    private var silenceTimer: DispatchSourceTimer?
    private var onSilenceCallback: (() -> Void)?
    private var silenceCounter: Int = 0
    private let consecutiveSilenceChecksRequired = 6 // 6 checks * 0.5s = 3 seconds of silence

    private let settings = [
        AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
        AVSampleRateKey: 44100,
        AVNumberOfChannelsKey: 1,
        AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
    ]

    private func getDirectoryToSaveAudioFile() -> URL {
        if let directory = getDirectory(directory: options.directory),
           var outputDirURL = FileManager.default.urls(for: directory, in: .userDomainMask).first {
            if let subDirectory = options.subDirectory?.trimmingCharacters(in: CharacterSet(charactersIn: "/")) {
                options.setSubDirectory(to: subDirectory)
                outputDirURL = outputDirURL.appendingPathComponent(subDirectory, isDirectory: true)

                do {
                    if !FileManager.default.fileExists(atPath: outputDirURL.path) {
                        try FileManager.default.createDirectory(at: outputDirURL, withIntermediateDirectories: true)
                    }
                } catch {
                    print("Error creating directory: \(error)")
                }
            }

            return outputDirURL
        }

        return URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    }

    func startRecording(recordOptions: RecordOptions) -> Bool {
        do {
            options = recordOptions
            silenceCounter = 0
            recordingSession = AVAudioSession.sharedInstance()
            originalRecordingSessionCategory = recordingSession.category
            try recordingSession.setCategory(AVAudioSession.Category.playAndRecord)
            try recordingSession.setActive(true)
            audioFilePath = getDirectoryToSaveAudioFile().appendingPathComponent("recording-\(Int(Date().timeIntervalSince1970 * 1000)).aac")
            audioRecorder = try AVAudioRecorder(url: audioFilePath, settings: settings)
            audioRecorder.isMeteringEnabled = true
            audioRecorder.record()
            status = CurrentRecordingStatus.RECORDING

            if recordOptions.stopOnSilence == true {
                startSilenceMonitoring()
            }

            return true
        } catch {
            return false
        }
    }

    func stopRecording() {
        do {
            silenceTimer?.cancel()
            silenceTimer = nil
            silenceCounter = 0
            audioRecorder.stop()
            try recordingSession.setActive(false)
            try recordingSession.setCategory(originalRecordingSessionCategory)
            originalRecordingSessionCategory = nil
            audioRecorder = nil
            recordingSession = nil
            status = CurrentRecordingStatus.NONE
        } catch {}
    }

    func getOutputFile() -> URL {
        return audioFilePath
    }

    func getDirectory(directory: String?) -> FileManager.SearchPathDirectory? {
        if let directory = directory {
            switch directory {
            case "CACHE":
                return .cachesDirectory
            case "LIBRARY":
                return .libraryDirectory
            default:
                return .documentDirectory
            }
        }
        return nil
    }

    func pauseRecording() -> Bool {
        if status == CurrentRecordingStatus.RECORDING {
            silenceTimer?.cancel()
            silenceTimer = nil
            silenceCounter = 0
            audioRecorder.pause()
            status = CurrentRecordingStatus.PAUSED
            return true
        } else {
            return false
        }
    }

    func resumeRecording() -> Bool {
        if status == CurrentRecordingStatus.PAUSED {
            audioRecorder.record()
            status = CurrentRecordingStatus.RECORDING
            silenceCounter = 0
            if options.stopOnSilence == true {
                startSilenceMonitoring()
            }
            return true
        } else {
            return false
        }
    }

    func getCurrentStatus() -> CurrentRecordingStatus {
        return status
    }

    func getMaxAmplitude() -> Float {
        guard audioRecorder != nil else { return 0.0 }
        audioRecorder.updateMeters()
        let peakPower = audioRecorder.peakPower(forChannel: 0)
        // Convert dB to linear scale (0-1)
        let amplitude = pow(10.0, peakPower / 20.0)
        return amplitude
    }

    func setOnSilenceCallback(_ callback: @escaping () -> Void) {
        self.onSilenceCallback = callback
    }

    private func startSilenceMonitoring() {
        guard options.stopOnSilence == true else {
            return
        }
        silenceCounter = 0

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        // Start monitoring after 3 seconds delay to allow recording to stabilize
        timer.schedule(deadline: .now() + 3.0, repeating: .milliseconds(500))
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }

            let amplitude = self.getMaxAmplitude()
            // Threshold for silence - below 0.06 is considered silence
            let silenceThreshold: Float = 0.06

            // print("AMPLITUDE (\(self.silenceCounter)/\(self.consecutiveSilenceChecksRequired)): amplitude \(amplitude)")
            if amplitude < silenceThreshold {
                self.silenceCounter += 1
                if self.silenceCounter >= self.consecutiveSilenceChecksRequired {
                    self.silenceTimer?.cancel()
                    self.silenceTimer = nil
                    self.onSilenceCallback?()
                }
            } else {
                // Reset counter if sound detected
                if self.silenceCounter > 0 {
					// Left for future debugging and improvements
                    // print("RECORDING Sound detected (amplitude \(amplitude)), resetting silence counter")
                }
                self.silenceCounter = 0
            }
        }
        timer.resume()
        silenceTimer = timer
    }

}
