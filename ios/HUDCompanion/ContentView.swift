import Combine
import SwiftUI
import EventKit

struct ContentView: View {
    @StateObject private var ble = BLEClient()
    @StateObject private var voice = VoicePipeline()
    @StateObject private var memory = MemoryStore()
    @StateObject private var chat = Conversation()
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
                    // Prominent state, because a grey caption is easy to miss while wearing
                    // the glasses and talking.
                    if voice.phase != .idle {
                        HStack(spacing: 8) {
                            switch voice.phase {
                            case .listening:
                                Circle().fill(.red).frame(width: 12, height: 12)
                                Text("Listening").font(.headline).foregroundColor(.red)
                            case .thinking:
                                ProgressView().scaleEffect(0.7)
                                Text("Thinking").font(.headline).foregroundColor(.orange)
                            case .answered:
                                Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                                Text("Answered").font(.headline).foregroundColor(.green)
                            case .failed:
                                Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.red)
                                Text("Problem").font(.headline).foregroundColor(.red)
                            case .idle:
                                EmptyView()
                            }
                            Spacer()
                        }
                        .padding(.vertical, 4)
                    }
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
                Section {
                    if memory.facts.isEmpty {
                        Text("Say \"remember that ...\" to teach the assistant something.")
                            .font(.caption).foregroundColor(.secondary)
                    } else {
                        ForEach(Array(memory.facts.enumerated()), id: \.offset) { _, fact in
                            Text(fact).font(.callout)
                        }
                        .onDelete { memory.delete(at: $0) }
                    }
                } header: { Text("Remembered") }
                Section {
                    NavigationLink {
                        ChatView(chat: chat, memory: memory)
                    } label: {
                        Label("Assistant chat", systemImage: "bubble.left.and.text.bubble.right")
                    }
                    if let last = chat.turns.last {
                        Text(last.text).font(.caption).foregroundColor(.secondary).lineLimit(2)
                    }
                } header: { Text("Assistant") }
            }
            .navigationTitle("HUD Companion")
            .onAppear {
                if !cal.authorized { cal.requestAccess() }
                ble.onReady = { autoPush() }
                VoicePipeline.requestPermission()
                voice.memory = memory
                voice.conversation = chat
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


// MARK: - Assistant conversation

/// One exchange in the conversation. Kept deliberately simple: the backend is stateless and
/// receives the whole history each time, so this is the only place dialogue lives.
struct Turn: Identifiable, Equatable {
    enum Role: String { case user, assistant }
    let id = UUID()
    let role: Role
    let text: String
}

/// Talks to /assistant and keeps the history.
///
/// Shared by the chat screen and the voice pipeline so both contribute to one conversation. This
/// also supplies the "conversation memory" layer: the backend stays stateless and we send the
/// recent turns with every request.
@MainActor
final class Conversation: ObservableObject {
    @Published var turns: [Turn] = []
    @Published var busy = false
    @Published var error = ""

    /// How many past turns travel with each request. Enough for context, bounded so a long
    /// session does not grow the prompt without limit.
    private let historyLimit = 20

    func send(_ text: String, facts: [String]) async {
        let question = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty, !busy else { return }
        turns.append(Turn(role: .user, text: question))
        busy = true
        error = ""
        defer { busy = false }

        guard let url = URL(string: Secrets.backendBaseURL + "/assistant") else {
            error = "bad backend URL"; return
        }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.setValue(Secrets.backendAPIKey, forHTTPHeaderField: "X-API-KEY")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.timeoutInterval = 60
        let history = turns.suffix(historyLimit).map { ["role": $0.role.rawValue, "content": $0.text] }
        r.httpBody = try? JSONSerialization.data(withJSONObject: ["messages": history, "facts": facts])

        do {
            let (data, resp) = try await URLSession.shared.data(for: r)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            guard code == 200 else {
                error = "backend \(code): \(String(data: data, encoding: .utf8)?.prefix(160) ?? "")"
                return
            }
            let o = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let reply = (o?["reply"] as? String) ?? ""
            if reply.isEmpty { error = "empty reply" } else {
                turns.append(Turn(role: .assistant, text: reply))
            }
        } catch {
            self.error = "unreachable: \(error.localizedDescription)"
        }
    }

    func clear() { turns.removeAll(); error = "" }
}

/// Text chat with the assistant. Decouples testing from the glasses and the microphone, and gives
/// the wearer a way to type when speaking is impractical or to read back what was said.
struct ChatView: View {
    @ObservedObject var chat: Conversation
    @ObservedObject var memory: MemoryStore
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(chat.turns) { turn in
                            HStack {
                                if turn.role == .assistant { bubble(turn); Spacer(minLength: 40) }
                                else { Spacer(minLength: 40); bubble(turn) }
                            }
                            .id(turn.id)
                        }
                        if chat.busy {
                            HStack { ProgressView(); Text("Thinking").foregroundColor(.secondary) }
                        }
                        if !chat.error.isEmpty {
                            Text(chat.error).font(.caption).foregroundColor(.red)
                        }
                    }
                    .padding()
                }
                .onChange(of: chat.turns.count) {
                    if let last = chat.turns.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }
            Divider()
            HStack(spacing: 8) {
                TextField("Ask the assistant", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                    .onSubmit(submit)
                Button(action: submit) {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty || chat.busy)
            }
            .padding()
        }
        .navigationTitle("Assistant")
        .toolbar {
            Button("Clear") { chat.clear() }.disabled(chat.turns.isEmpty)
        }
    }

    private func bubble(_ turn: Turn) -> some View {
        Text(turn.text)
            .padding(10)
            .background(turn.role == .user ? Color.accentColor.opacity(0.2) : Color.gray.opacity(0.15))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    /// "remember that ..." is handled on the phone: instant, free, no model call - the same path
    /// the voice route uses, so typing and speaking behave identically.
    private func submit() {
        let text = draft
        draft = ""
        if let confirmation = memory.capture(from: text) {
            chat.turns.append(Turn(role: .user, text: text))
            chat.turns.append(Turn(role: .assistant, text: confirmation))
            return
        }
        Task { await chat.send(text, facts: memory.facts) }
    }
}
