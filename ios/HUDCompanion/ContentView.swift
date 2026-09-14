import SwiftUI
import EventKit

struct ContentView: View {
    @StateObject private var ble = BLEClient()
    @StateObject private var voice = VoicePipeline()
    @StateObject private var cal = CalendarSync()
    @State private var lastPush = ""
    /// Epoch seconds of the last automatic push; manual pushes ignore the throttle.
    @AppStorage("hud_last_auto_push") private var lastAutoPush: Double = 0
    private let autoInterval: TimeInterval = 2 * 60 * 60   // 2 hours

    var body: some View {
        NavigationView {
            List {
                Section {
                    HStack {
                        Circle().fill(statusColor).frame(width: 10, height: 10)
                        Text(statusText)
                        Spacer()
                        if !ble.deviceName.isEmpty { Text(ble.deviceName).foregroundColor(.secondary).font(.caption) }
                    }
                    if case .error = ble.state {
                        Button("Retry") { ble.startAdvertising() }
                    }
                } header: { Text("Glasses") }

                Section {
                    if cal.authorized {
                        ForEach(cal.calendars) { c in
                            Button { cal.toggle(c.id) } label: {
                                HStack {
                                    Image(systemName: cal.selected.contains(c.id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundColor(cal.selected.contains(c.id) ? .green : .secondary)
                                    Text(c.title).foregroundColor(.primary)
                                }
                            }
                        }
                    } else {
                        Button("Allow calendar access") { cal.requestAccess() }
                    }
                } header: { Text("Calendars to show on the HUD") }

                Section {
                    Button { push(manual: true) } label: {
                        Label("Push now", systemImage: "arrow.up.circle.fill")
                    }
                    .disabled(!cal.authorized)
                    if !lastPush.isEmpty { Text(lastPush).font(.caption).foregroundColor(.secondary) }
                    if !voice.status.isEmpty {
                        Text(voice.status).font(.caption).foregroundColor(.secondary)
                    }
                    if !voice.transcript.isEmpty {
                        Text("You: \(voice.transcript)").font(.callout)
                    }
                    if !voice.reply.isEmpty {
                        Text(voice.reply).font(.callout).foregroundColor(.accentColor)
                    }
                    if ble.audioFrames > 0 {
                        Text(ble.audioActive
                             ? "Receiving audio: \(ble.audioFrames) frames, \(ble.audioBytes / 1024) KB"
                             : "Audio received: \(ble.audioFrames) frames, \(ble.audioBytes / 1024) KB")
                            .font(.caption)
                            .foregroundColor(ble.audioActive ? .green : .secondary)
                    }
                    Text("Auto-pushes on connect and calendar changes, at most every 2 hours.")
                        .font(.caption2).foregroundColor(.secondary)
                }
            }
            .navigationTitle("HUD Companion")
            .onAppear {
                if !cal.authorized { cal.requestAccess() }
                ble.onReady = { autoPush() }
                VoicePipeline.requestPermission()
                ble.onAudioStart = { voice.begin() }
                ble.onAudioFrames = { frames in for f in frames { voice.feed(opus: f) } }
                ble.onAudioEnd = { voice.end() }
                cal.onChange = { autoPush() }
            }
        }
    }

    private func autoPush() {
        let now = Date().timeIntervalSince1970
        guard ble.state == .connected, cal.authorized, now - lastAutoPush >= autoInterval else { return }
        push(manual: false)
    }

    private func push(manual: Bool) {
        let events = cal.events()
        // Only claim a push happened if the transport actually accepted it, otherwise the status
        // line reports events that were never delivered.
        guard ble.send(type: "calendar", data: ["events": events]) else {
            lastPush = "Not sent: glasses not connected"
            return
        }
        if !manual { lastAutoPush = Date().timeIntervalSince1970 }
        let kind = manual ? "Pushed" : "Auto-pushed"
        lastPush = "\(kind) \(events.count) events at \(Date().formatted(date: .omitted, time: .shortened))"
    }

    private var statusColor: Color {
        switch ble.state {
        case .connected: return .green
        case .publishing, .advertising: return .yellow
        default: return .red
        }
    }
    private var statusText: String {
        switch ble.state {
        case .off: return "Bluetooth off"
        case .publishing: return "Publishing service…"
        case .advertising: return "Advertising, waiting for glasses…"
        case .connected: return "Connected"
        case .error(let m): return m
        }
    }
}
