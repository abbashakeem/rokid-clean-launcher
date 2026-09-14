import Combine
import SwiftUI
import EventKit

struct ContentView: View {
    @StateObject private var ble = BLEClient()
    @StateObject private var voice = VoicePipeline()
    @StateObject private var memory = MemoryStore()
    @StateObject private var chat = Conversation()
    @StateObject private var settings = AppSettings()
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
                    NavigationLink {
                        SettingsView(settings: settings)
                    } label: {
                        Label("Assistant settings", systemImage: "gearshape")
                    }
                    Text(settings.routeDescription).font(.caption).foregroundColor(.secondary)
                } header: { Text("Assistant") }
            }
            .navigationTitle("HUD Companion")
            .onAppear {
                if !cal.authorized { cal.requestAccess() }
                ble.onReady = { autoPush() }
                VoicePipeline.requestPermission()
                voice.memory = memory
                voice.conversation = chat
                chat.settings = settings
                chat.calendar = cal
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

    /// Set by ContentView. Decides whether we call our backend or the provider directly.
    var settings: AppSettings?
    /// Used to build context locally when bypassing the backend.
    var calendar: CalendarSync?

    func send(_ text: String, facts: [String]) async {
        let question = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty, !busy else { return }
        turns.append(Turn(role: .user, text: question))
        busy = true
        error = ""
        defer { busy = false }

        // Direct to the provider when a key is configured: our backend is on Render's free tier
        // and a cold start measured 237 seconds, which no voice assistant can tolerate.
        // Apple's on-device model first when asked for: no key, no network, nothing leaves the
        // phone. Smaller than the cloud models and with no current world knowledge, so it is
        // opt-in rather than the default.
        if let settings, settings.useAppleModel, AppleModel.isReady {
            await sendToAppleModel(question, facts: facts)
            return
        }
        if let settings, settings.useDirectProvider {
            await sendDirectToGemini(question, facts: facts, settings: settings)
            return
        }

        let base = settings?.backendBaseURL ?? Secrets.backendBaseURL
        let apiKey = settings?.backendAPIKey ?? Secrets.backendAPIKey
        guard let url = URL(string: base + "/assistant") else {
            error = "bad backend URL"; return
        }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.setValue(apiKey, forHTTPHeaderField: "X-API-KEY")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Generous, because a cold Render instance took 237s to wake.
        r.timeoutInterval = 300
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


// MARK: - Credentials and routing

/// Small Keychain wrapper. API keys do not belong in UserDefaults.
enum Keychain {
    static func set(_ value: String, for key: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrAccount as String: key]
        SecItemDelete(q as CFDictionary)
        guard !value.isEmpty else { return }
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ key: String) -> String {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrAccount as String: key,
                                kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let d = out as? Data, let s = String(data: d, encoding: .utf8) else { return "" }
        return s
    }
}

/// Editable settings, so keys and routing can change without rebuilding the app.
@MainActor
final class AppSettings: ObservableObject {
    @Published var backendBaseURL: String {
        didSet { UserDefaults.standard.set(backendBaseURL, forKey: "hud.backendURL") }
    }
    @Published var backendAPIKey: String { didSet { Keychain.set(backendAPIKey, for: "hud.backendKey") } }
    @Published var geminiAPIKey: String { didSet { Keychain.set(geminiAPIKey, for: "hud.geminiKey") } }
    @Published var geminiModel: String {
        didSet { UserDefaults.standard.set(geminiModel, forKey: "hud.geminiModel") }
    }
    @Published var preferDirect: Bool {
        didSet { UserDefaults.standard.set(preferDirect, forKey: "hud.preferDirect") }
    }
    /// Apple's on-device model: free, offline, private, and smaller than the cloud models.
    @Published var useAppleModel: Bool {
        didSet { UserDefaults.standard.set(useAppleModel, forKey: "hud.useApple") }
    }

    init() {
        let d = UserDefaults.standard
        backendBaseURL = d.string(forKey: "hud.backendURL") ?? Secrets.backendBaseURL
        geminiModel = d.string(forKey: "hud.geminiModel") ?? "gemini-3.6-flash"
        preferDirect = d.object(forKey: "hud.preferDirect") as? Bool ?? true
        useAppleModel = d.object(forKey: "hud.useApple") as? Bool ?? false
        let storedBackend = Keychain.get("hud.backendKey")
        backendAPIKey = storedBackend.isEmpty ? Secrets.backendAPIKey : storedBackend
        geminiAPIKey = Keychain.get("hud.geminiKey")
    }

    /// Direct only when we actually hold a provider key.
    var useDirectProvider: Bool { preferDirect && !geminiAPIKey.isEmpty }

    var routeDescription: String {
        if useAppleModel { return AppleModel.statusText }
        return useDirectProvider
            ? "Calling Gemini directly (no backend, no cold start)"
            : "Via backend — first request after idle can take minutes"
    }
}

struct SettingsView: View {
    @ObservedObject var settings: AppSettings

    var body: some View {
        Form {
            Section {
                Toggle("Use Apple on-device model", isOn: $settings.useAppleModel)
                    .disabled(!AppleModel.isSupported)
                if settings.useAppleModel {
                    Text(AppleModel.statusText).font(.caption)
                        .foregroundColor(AppleModel.isReady ? .green : .orange)
                }
                Toggle("Call Gemini directly", isOn: $settings.preferDirect)
                    .disabled(settings.useAppleModel)
                SecureField("Gemini API key", text: $settings.geminiAPIKey)
                TextField("Gemini model", text: $settings.geminiModel)
                Text(settings.routeDescription).font(.caption).foregroundColor(.secondary)
            } header: { Text("Assistant") } footer: {
                Text("Direct avoids the backend entirely. Our backend runs on a free tier that "
                     + "sleeps when idle; a cold start was measured at 237 seconds. Weather is "
                     + "only available on the backend route.")
            }
            Section {
                TextField("Backend URL", text: $settings.backendBaseURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Backend API key", text: $settings.backendAPIKey)
            } header: { Text("Backend") } footer: {
                Text("Still used for the glasses' calendar and weather, and as the assistant "
                     + "route when no Gemini key is set.")
            }
        }
        .navigationTitle("Assistant settings")
    }
}

// MARK: - Direct provider call

extension Conversation {
    /// Call Gemini from the phone, building context locally.
    ///
    /// Weather is not included here: the backend holds that key, and fetching it would reintroduce
    /// the dependency this route exists to avoid. Calendar, facts and local time all come from the
    /// device.
    func sendDirectToGemini(_ question: String, facts: [String], settings: AppSettings) async {
        let model = settings.geminiModel
        let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/"
                      + model + ":generateContent")!
        var contents: [[String: Any]] = []
        for t in turns.suffix(20) {
            contents.append(["role": t.role == .assistant ? "model" : "user",
                             "parts": [["text": t.text]]])
        }
        var system = Prompts.base + "\n" + Prompts.capabilities(web: true) + "\n\n"
        system += "Current local time: " + Date().formatted(date: .complete, time: .shortened) + ".\n"
        if let events = calendar?.events(), !events.isEmpty {
            // "start" is epoch MILLISECONDS as an Int (see CalendarSync.events). Passing it
            // through String(describing:) would hand the model "Gym at 1789329600000", which it
            // would likely paper over by inventing a time.
            let fmt = DateFormatter()
            fmt.dateFormat = "EEE d MMM HH:mm"
            let lines = events.prefix(5).compactMap { e -> String? in
                guard let title = e["title"] as? String else { return nil }
                var line = "- " + title
                if let ms = e["start"] as? Int {
                    let when = Date(timeIntervalSince1970: Double(ms) / 1000)
                    line += " at " + fmt.string(from: when)
                }
                if let loc = e["location"] as? String, !loc.isEmpty { line += " (" + loc + ")" }
                return line
            }
            if !lines.isEmpty { system += "Upcoming events:\n" + lines.joined(separator: "\n") + "\n" }
        }
        if !facts.isEmpty {
            system += "Facts the wearer asked you to remember:\n"
                + facts.map { "- " + $0 }.joined(separator: "\n")
        }

        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.setValue(settings.geminiAPIKey, forHTTPHeaderField: "x-goog-api-key")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.timeoutInterval = 45
        r.httpBody = try? JSONSerialization.data(withJSONObject: [
            "contents": contents,
            "systemInstruction": ["parts": [["text": system]]],
            "generationConfig": ["maxOutputTokens": 512],
            "tools": [["google_search": [:]]],
        ])
        do {
            let (data, resp) = try await URLSession.shared.data(for: r)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            guard code == 200 else {
                error = "gemini \(code): \(String(data: data, encoding: .utf8)?.prefix(160) ?? "")"
                return
            }
            let o = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let cands = o?["candidates"] as? [[String: Any]] ?? []
            let parts = (cands.first?["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
            let text = parts.compactMap { $0["text"] as? String }.joined()
            if text.isEmpty { error = "empty reply" } else {
                turns.append(Turn(role: .assistant, text: text))
            }
        } catch {
            self.error = "gemini unreachable: \(error.localizedDescription)"
        }
    }
}


// MARK: - Apple on-device model

import FoundationModels

/// Apple's on-device language model, used when the wearer opts in.
///
/// Availability is genuinely conditional - the device must be eligible, Apple Intelligence must be
/// switched on, and the model must have downloaded - so the reason is surfaced rather than letting
/// a request fail blankly.
enum AppleModel {
    /// False on anything below iOS 26, where the framework does not exist at all.
    static var isSupported: Bool {
        if #available(iOS 26, *) { return true }
        return false
    }

    static var isReady: Bool {
        guard #available(iOS 26, *) else { return false }
        if case .available = SystemLanguageModel.default.availability { return true }
        return false
    }

    static var statusText: String {
        guard #available(iOS 26, *) else {
            return "Apple model needs iOS 26 or newer"
        }
        switch SystemLanguageModel.default.availability {
        case .available:
            return "Apple on-device model ready (free, offline, private)"
        case .unavailable(let reason):
            switch reason {
            case .deviceNotEligible:
                return "Apple model unavailable: this device is not eligible"
            case .appleIntelligenceNotEnabled:
                return "Apple model unavailable: turn on Apple Intelligence in Settings"
            case .modelNotReady:
                return "Apple model still downloading — try again shortly"
            @unknown default:
                return "Apple model unavailable"
            }
        @unknown default:
            return "Apple model availability unknown"
        }
    }
}

extension Conversation {
    /// Ask Apple's on-device model. No key, no network, nothing leaves the device.
    ///
    /// History and context are folded into the prompt because the session is created per request;
    /// keeping a long-lived session would be faster but would hold context we cannot inspect.
    func sendToAppleModel(_ question: String, facts: [String]) async {
        var context = "Current local time: "
            + Date().formatted(date: .complete, time: .shortened) + ".\n"
        if let events = calendar?.events(), !events.isEmpty {
            let fmt = DateFormatter()
            fmt.dateFormat = "EEE d MMM HH:mm"
            let lines = events.prefix(5).compactMap { e -> String? in
                guard let title = e["title"] as? String else { return nil }
                var line = "- " + title
                if let ms = e["start"] as? Int {
                    line += " at " + fmt.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
                }
                return line
            }
            if !lines.isEmpty { context += "Upcoming events:\n" + lines.joined(separator: "\n") + "\n" }
        }
        if !facts.isEmpty {
            context += "Facts the wearer asked you to remember:\n"
                + facts.map { "- " + $0 }.joined(separator: "\n") + "\n"
        }
        let recent = turns.suffix(8).dropLast().map {
            ($0.role == .user ? "Wearer: " : "You: ") + $0.text
        }.joined(separator: "\n")

        let instructions = Prompts.base + "\n" + Prompts.capabilities(web: false)
        var prompt = context
        if !recent.isEmpty { prompt += "\nRecent conversation:\n" + recent + "\n" }
        prompt += "\nWearer asks: " + question

        guard #available(iOS 26, *) else {
            error = "Apple model needs iOS 26 or newer"
            return
        }
        do {
            let session = LanguageModelSession(instructions: instructions)
            let response = try await session.respond(to: prompt)
            let text = response.content.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { error = "Apple model returned nothing" } else {
                turns.append(Turn(role: .assistant, text: text))
            }
        } catch {
            self.error = "Apple model failed: \(error.localizedDescription)"
        }
    }
}


// MARK: - Shared prompt text

/// One place for the system prompt, so every provider describes itself accurately.
///
/// Added because the on-device model, asked what it could access, answered "only what's on your
/// screen right now" - false, and invented. Small models confabulate about their own capabilities
/// when not told, so state them.
enum Prompts {
    static let base =
        "You are a voice assistant on smart glasses. Replies are read on a tiny monocular "
        + "display and spoken aloud, so answer in at most two short sentences. No markdown, "
        + "no lists, no preamble."

    static func capabilities(web: Bool) -> String {
        var s = "What you can actually see: the wearer's current local time, their upcoming "
            + "calendar events, and any facts they asked you to remember. These are supplied to "
            + "you in this prompt. You cannot see their screen, read their messages, or browse "
            + "their files."
        s += web
            ? " You can search the web, so you may answer questions about current events."
            : " You have no internet access, so say so plainly for anything needing current"
              + " information rather than guessing."
        s += " If asked what you can access, answer from this list rather than speculating."
        return s
    }
}
