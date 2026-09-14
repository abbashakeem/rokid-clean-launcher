import AudioToolbox
import AVFoundation
import Combine
import Foundation
import Speech

/// Opus frames from the glasses -> PCM -> on-device transcription -> backend reply.
///
/// The glasses cannot ship raw PCM (30KB/s measured against a BLE budget near 24KB/s), so audio
/// arrives Opus-encoded and has to be decoded here. `kAudioFormatOpus` is present in the iOS SDK
/// and decoding was confirmed against macOS CoreAudio, but NOT yet on a device - so a failure to
/// build the converter is reported with its real status code rather than silently yielding nothing.
///
/// Transcription is push-to-talk, bounded by audio_start/audio_end from the glasses.
/// `SFSpeechRecognizer` caps a single request near a minute, and continuous listening would also
/// mean the glasses stream constantly, which the battery work argues against.
@MainActor
final class VoicePipeline: ObservableObject {
    /// What the UI should show. One value rather than inferring state from three strings.
    enum Phase: Equatable { case idle, listening, thinking, answered, failed }

    @Published var phase: Phase = .idle
    @Published var transcript = ""
    @Published var reply = ""
    @Published var status = ""

    /// Phone-owned remembered facts, sent with each question and captured from "remember ...".
    var memory: MemoryStore?
    /// Shared with the chat screen so spoken and typed questions form one conversation.
    var conversation: Conversation?

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en_US"))
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var converter: AudioConverterRef?
    private var pcmFormat: AVAudioFormat?

    static func requestPermission() {
        SFSpeechRecognizer.requestAuthorization { _ in }
    }

    // MARK: session lifecycle, driven by the glasses

    func begin() {
        transcript = ""; reply = ""; phase = .listening; status = "listening"
        guard let recognizer, recognizer.isAvailable else {
            status = "recogniser unavailable"; phase = .failed; return
        }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        // Keep audio on the device. If the model is not present this silently falls back to
        // network recognition, so report which one we actually got.
        req.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
        status = recognizer.supportsOnDeviceRecognition ? "listening (on-device)" : "listening (network)"
        request = req
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            guard let self else { return }
            if let result {
                self.transcript = result.bestTranscription.formattedString
            }
            if let error {
                self.status = "recognition failed: \(error.localizedDescription)"
                self.phase = .failed
            }
        }
    }

    func end() {
        request?.endAudio()
        request = nil
        task = nil
        tearDownConverter()
        let text = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { status = "nothing heard"; phase = .idle; return }

        // "remember ..." is handled entirely on the phone: instant, free, and no model call.
        if let confirmation = memory?.capture(from: text) {
            reply = confirmation
            status = "remembered"
            phase = .answered
            return
        }

        status = "thinking"
        phase = .thinking
        // Route through the shared conversation when available, so history and the chat screen
        // stay in step; fall back to the standalone call if it has not been wired.
        if let conversation {
            Task {
                await conversation.send(text, facts: memory?.facts ?? [])
                reply = conversation.turns.last?.text ?? ""
                status = conversation.error.isEmpty ? "answered via " + conversation.lastRoute : conversation.error
                phase = conversation.error.isEmpty ? .answered : .failed
            }
        } else {
            status = "no conversation wired"
            phase = .failed
        }
    }

    // MARK: Opus -> PCM

    private func makeConverter() -> Bool {
        var src = AudioStreamBasicDescription(
            mSampleRate: 16000, mFormatID: kAudioFormatOpus, mFormatFlags: 0,
            mBytesPerPacket: 0, mFramesPerPacket: 320, mBytesPerFrame: 0,
            mChannelsPerFrame: 1, mBitsPerChannel: 0, mReserved: 0)
        var dst = AudioStreamBasicDescription(
            mSampleRate: 16000, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 2, mFramesPerPacket: 1, mBytesPerFrame: 2,
            mChannelsPerFrame: 1, mBitsPerChannel: 16, mReserved: 0)
        var c: AudioConverterRef?
        let st = AudioConverterNew(&src, &dst, &c)
        guard st == noErr, let c else {
            status = "opus decode unavailable (status \(st))"
            phase = .failed
            return false
        }
        converter = c
        pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000,
                                  channels: 1, interleaved: true)
        return true
    }

    private func tearDownConverter() {
        if let converter { AudioConverterDispose(converter) }
        converter = nil
    }

    /// Feed one Opus frame decoded from the glasses. Called for every frame in every batch.
    func feed(opus: Data) {
        guard request != nil else { return }
        if converter == nil, !makeConverter() { return }
        guard let converter, let pcmFormat else { return }

        var input = opus
        var packetDesc = AudioStreamPacketDescription(
            mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(opus.count))
        var outFrames: UInt32 = 960                  // room for up to 60ms at 16kHz
        guard let buffer = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: outFrames),
              let channel = buffer.int16ChannelData?[0] else { return }

        var abl = AudioBufferList(
            mNumberBuffers: 1,
            mBuffers: AudioBuffer(mNumberChannels: 1,
                                  mDataByteSize: outFrames * 2,
                                  mData: UnsafeMutableRawPointer(channel)))

        let st = input.withUnsafeMutableBytes { raw -> OSStatus in
            var ctx = DecodeContext(bytes: raw.baseAddress, count: opus.count, desc: &packetDesc,
                                    consumed: false)
            return withUnsafeMutablePointer(to: &ctx) { ctxPtr in
                AudioConverterFillComplexBuffer(converter, decodeCallback, ctxPtr,
                                                &outFrames, &abl, nil)
            }
        }
        guard st == noErr || st == kNoMoreDataErr, outFrames > 0 else { return }
        buffer.frameLength = outFrames
        request?.append(buffer)
    }

    // MARK: backend

}

/// Hands a single Opus packet to the converter exactly once.
private nonisolated struct DecodeContext {
    let bytes: UnsafeMutableRawPointer?
    let count: Int
    let desc: UnsafeMutablePointer<AudioStreamPacketDescription>
    var consumed: Bool
}

private nonisolated let kNoMoreDataErr: OSStatus = -1
private nonisolated func decodeCallback(
    _ conv: AudioConverterRef,
    _ numberPackets: UnsafeMutablePointer<UInt32>,
    _ ioData: UnsafeMutablePointer<AudioBufferList>,
    _ outDesc: UnsafeMutablePointer<UnsafeMutablePointer<AudioStreamPacketDescription>?>?,
    _ userData: UnsafeMutableRawPointer?
) -> OSStatus {
    guard let userData else { numberPackets.pointee = 0; return kNoMoreDataErr }
    let ctx = userData.assumingMemoryBound(to: DecodeContext.self)
    if ctx.pointee.consumed { numberPackets.pointee = 0; return kNoMoreDataErr }
    ctx.pointee.consumed = true
    numberPackets.pointee = 1
    ioData.pointee.mNumberBuffers = 1
    ioData.pointee.mBuffers.mNumberChannels = 1
    ioData.pointee.mBuffers.mDataByteSize = UInt32(ctx.pointee.count)
    ioData.pointee.mBuffers.mData = ctx.pointee.bytes
    outDesc?.pointee = ctx.pointee.desc
    return noErr
}
