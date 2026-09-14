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

/// Which brain answers. Stored by raw value, so the names are part of the settings format.
enum AssistantRoute: String, CaseIterable, Identifiable {
    /// Apple triages and answers what it can on the device; the rest goes to the cloud.
    case orchestrated
    /// Apple only. Free, offline, private, and admits it has no internet.
    case apple
    /// Gemini straight from the phone, with web search. No backend, no cold start.
    case gemini
    /// Our backend. The only route that knows the weather, and the only one that sleeps.
    case backend

    var id: String { rawValue }

    var label: String {
        switch self {
        case .orchestrated: return "Apple first, then Gemini"
        case .apple: return "Apple only"
        case .gemini: return "Gemini"
        case .backend: return "Backend"
        }
    }
}

/// A provider failure that is worth showing to the wearer verbatim.
struct AssistantError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Talks to whichever route is configured and keeps the history.
///
/// Shared by the chat screen and the voice pipeline so both contribute to one conversation. This
/// also supplies the "conversation memory" layer: every provider is stateless and we send the
/// recent turns with each request.
@MainActor
final class Conversation: ObservableObject {
    @Published var turns: [Turn] = []
    @Published var busy = false
    @Published var error = ""
    /// Who produced the last reply, e.g. "Apple" or "Gemini, after Apple escalated". Shown so the
    /// wearer can tell whether an answer came from the small local model or the cloud.
    @Published var lastRoute = ""

    /// How many past turns travel with each request. Enough for context, bounded so a long
    /// session does not grow the prompt without limit.
    let historyLimit = 20

    /// Set by ContentView. Decides which route answers.
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

        let route = settings?.effectiveRoute ?? .backend
        do {
            let (reply, via): (String, String)
            switch route {
            case .orchestrated: (reply, via) = try await orchestrate(question, facts: facts)
            case .apple: (reply, via) = (try await askApple(question, facts: facts), "Apple")
            case .gemini: (reply, via) = (try await askGemini(question, facts: facts), "Gemini")
            case .backend: (reply, via) = (try await askBackend(facts: facts), "backend")
            }
            turns.append(Turn(role: .assistant, text: reply))
            lastRoute = via
        } catch {
            self.error = error.localizedDescription
        }
    }

    func clear() { turns.removeAll(); error = ""; lastRoute = "" }

    // MARK: shared context

    /// What every provider is told about the wearer: local time, upcoming events, remembered facts.
    ///
    /// "start" is epoch MILLISECONDS as an Int (see CalendarSync.events). Passing it through
    /// String(describing:) would hand the model "Gym at 1789329600000", which it would likely
    /// paper over by inventing a time.
    func contextBlock(facts: [String]) -> String {
        var s = "Current local time: " + Date().formatted(date: .complete, time: .shortened) + ".\n"
        if let events = calendar?.events(), !events.isEmpty {
            let fmt = DateFormatter()
            fmt.dateFormat = "EEE d MMM HH:mm"
            let lines = events.prefix(5).compactMap { e -> String? in
                guard let title = e["title"] as? String else { return nil }
                var line = "- " + title
                if let ms = e["start"] as? Int {
                    line += " at " + fmt.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
                }
                if let loc = e["location"] as? String, !loc.isEmpty { line += " (" + loc + ")" }
                return line
            }
            if !lines.isEmpty { s += "Upcoming events:\n" + lines.joined(separator: "\n") + "\n" }
        }
        if !facts.isEmpty {
            s += "Facts the wearer asked you to remember:\n"
                + facts.map { "- " + $0 }.joined(separator: "\n") + "\n"
        }
        return s
    }

    // MARK: backend route

    /// Our FastAPI backend. The user turn is already in `turns`, so the history carries the question.
    func askBackend(facts: [String]) async throws -> String {
        let base = settings?.backendBaseURL ?? Secrets.backendBaseURL
        let apiKey = settings?.backendAPIKey ?? Secrets.backendAPIKey
        guard let url = URL(string: base + "/assistant") else { throw AssistantError("bad backend URL") }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.setValue(apiKey, forHTTPHeaderField: "X-API-KEY")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Generous, because a cold Render instance took 237s to wake.
        r.timeoutInterval = 300
        let history = turns.suffix(historyLimit).map { ["role": $0.role.rawValue, "content": $0.text] }
        r.httpBody = try? JSONSerialization.data(withJSONObject: ["messages": history, "facts": facts])

        let data: Data, resp: URLResponse
        do { (data, resp) = try await URLSession.shared.data(for: r) }
        catch { throw AssistantError("unreachable: \(error.localizedDescription)") }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else {
            throw AssistantError("backend \(code): \(String(data: data, encoding: .utf8)?.prefix(160) ?? "")")
        }
        let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        let reply = (o?["reply"] as? String) ?? ""
        guard !reply.isEmpty else { throw AssistantError("empty reply") }
        return reply
    }
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
                        if !chat.lastRoute.isEmpty, chat.turns.last?.role == .assistant {
                            Text("via " + chat.lastRoute).font(.caption2).foregroundColor(.secondary)
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
            chat.lastRoute = "phone memory"
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
    /// What the wearer asked for. `effectiveRoute` is what they will actually get.
    @Published var route: AssistantRoute {
        didSet { UserDefaults.standard.set(route.rawValue, forKey: "hud.route") }
    }

    init() {
        let d = UserDefaults.standard
        backendBaseURL = d.string(forKey: "hud.backendURL") ?? Secrets.backendBaseURL
        geminiModel = d.string(forKey: "hud.geminiModel") ?? "gemini-3.6-flash"
        let storedBackend = Keychain.get("hud.backendKey")
        backendAPIKey = storedBackend.isEmpty ? Secrets.backendAPIKey : storedBackend
        geminiAPIKey = Keychain.get("hud.geminiKey")
        if let raw = d.string(forKey: "hud.route"), let r = AssistantRoute(rawValue: raw) {
            route = r
        } else if d.object(forKey: "hud.useApple") != nil || d.object(forKey: "hud.preferDirect") != nil {
            // Migrate the two booleans this replaced, preserving what the wearer had chosen.
            if d.bool(forKey: "hud.useApple") { route = .apple }
            else if d.object(forKey: "hud.preferDirect") as? Bool ?? true { route = .gemini }
            else { route = .backend }
        } else {
            route = .orchestrated
        }
    }

    /// Direct only when we actually hold a provider key.
    var useDirectProvider: Bool { !geminiAPIKey.isEmpty }

    /// The route that will really answer, after removing anything unavailable right now.
    /// Apple needs the model downloaded; Gemini needs a key; the backend needs nothing.
    var effectiveRoute: AssistantRoute {
        switch route {
        case .orchestrated:
            if AppleModel.isReady { return .orchestrated }
            return useDirectProvider ? .gemini : .backend
        case .apple:
            if AppleModel.isReady { return .apple }
            return useDirectProvider ? .gemini : .backend
        case .gemini:
            return useDirectProvider ? .gemini : .backend
        case .backend:
            return .backend
        }
    }

    var routeDescription: String {
        let fallback = route != effectiveRoute ? " (\(route.label) unavailable: \(AppleModel.isReady ? "no Gemini key" : AppleModel.statusText))" : ""
        switch effectiveRoute {
        case .orchestrated:
            return "Apple answers on the device; anything needing the internet goes to "
                + (useDirectProvider ? "Gemini" : "the backend") + fallback
        case .apple:
            return AppleModel.statusText + fallback
        case .gemini:
            return "Calling Gemini directly (no backend, no cold start)" + fallback
        case .backend:
            return "Via backend — first request after idle can take minutes" + fallback
        }
    }
}

struct SettingsView: View {
    @ObservedObject var settings: AppSettings

    var body: some View {
        Form {
            Section {
                Picker("Route", selection: $settings.route) {
                    ForEach(AssistantRoute.allCases) { r in Text(r.label).tag(r) }
                }
                Text(settings.routeDescription).font(.caption).foregroundColor(.secondary)
                if settings.route == .orchestrated || settings.route == .apple {
                    Text(AppleModel.statusText).font(.caption)
                        .foregroundColor(AppleModel.isReady ? .green : .orange)
                }
                SecureField("Gemini API key", text: $settings.geminiAPIKey)
                TextField("Gemini model", text: $settings.geminiModel)
            } header: { Text("Assistant") } footer: {
                Text("Apple first: the on-device model decides whether it can answer from general "
                     + "knowledge, your calendar and remembered facts. Anything needing current "
                     + "information is handed to Gemini, and an Apple answer that admits it lacks "
                     + "the information is retried on Gemini automatically. Direct Gemini avoids "
                     + "the backend entirely; the backend runs on a free tier that sleeps when idle "
                     + "(a cold start was measured at 237 seconds) and is the only route with weather.")
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
    /// device. The user turn is already in `turns`, so the history carries the question.
    func askGemini(_ question: String, facts: [String]) async throws -> String {
        guard let settings, !settings.geminiAPIKey.isEmpty else { throw AssistantError("no Gemini key") }
        let model = settings.geminiModel
        guard let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/"
                            + model + ":generateContent") else { throw AssistantError("bad Gemini model name") }
        var contents: [[String: Any]] = []
        for t in turns.suffix(historyLimit) {
            contents.append(["role": t.role == .assistant ? "model" : "user",
                             "parts": [["text": t.text]]])
        }
        let system = Prompts.base + "\n" + Prompts.capabilities(web: true) + "\n\n" + contextBlock(facts: facts)

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
        let data: Data, resp: URLResponse
        do { (data, resp) = try await URLSession.shared.data(for: r) }
        catch { throw AssistantError("gemini unreachable: \(error.localizedDescription)") }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else {
            throw AssistantError("gemini \(code): \(String(data: data, encoding: .utf8)?.prefix(160) ?? "")")
        }
        let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        let cands = o?["candidates"] as? [[String: Any]] ?? []
        let parts = (cands.first?["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
        let text = parts.compactMap { $0["text"] as? String }.joined()
        guard !text.isEmpty else { throw AssistantError("empty reply") }
        return text
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

/// The triage verdict, produced by guided generation so the model fills in a schema rather than
/// composing a tool call. Free-form tool calling from this model proved unreliable ("this
/// weekend" went wrong); a single constrained boolean is the narrowest possible decision.
@available(iOS 26, *)
@Generable
struct Triage {
    @Guide(description: "true when a good answer needs information the assistant does not have: "
           + "anything current (weather, news, prices, scores, traffic, opening hours), facts "
           + "about specific real places, businesses, people or products, or any web lookup. "
           + "false when general knowledge, arithmetic, conversions, the current date or time, "
           + "the wearer's calendar or remembered facts, or plain conversation are enough.")
    let needsInternet: Bool
}

extension Conversation {
    /// Ask Apple's on-device model. No key, no network, nothing leaves the device.
    ///
    /// History and context are folded into the prompt because the session is created per request;
    /// keeping a long-lived session would be faster but would hold context we cannot inspect.
    /// `orchestrated` swaps in the capability text that asks for the ESCALATE sentinel.
    func askApple(_ question: String, facts: [String], orchestrated: Bool = false) async throws -> String {
        guard #available(iOS 26, *) else { throw AssistantError("Apple model needs iOS 26 or newer") }
        let instructions = Prompts.base + "\n"
            + (orchestrated ? Prompts.capabilitiesWithEscalation : Prompts.capabilities(web: false))
        var prompt = contextBlock(facts: facts)
        let recent = recentTranscript()
        if !recent.isEmpty { prompt += "\nRecent conversation:\n" + recent + "\n" }
        prompt += "\nWearer asks: " + question
        do {
            let session = LanguageModelSession(instructions: instructions)
            let response = try await session.respond(to: prompt)
            let text = response.content.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { throw AssistantError("Apple model returned nothing") }
            return text
        } catch let e as AssistantError {
            throw e
        } catch {
            throw AssistantError("Apple model failed: \(error.localizedDescription)")
        }
    }

    /// The last few turns before the current question, as plain text for the local model.
    func recentTranscript() -> String {
        turns.suffix(8).dropLast().map {
            ($0.role == .user ? "Wearer: " : "You: ") + $0.text
        }.joined(separator: "\n")
    }

    /// Constrained triage: does this question need the internet? Returns nil if the model could
    /// not decide, so the caller can choose a fallback rather than guessing on its behalf.
    func triage(_ question: String) async -> Bool? {
        guard #available(iOS 26, *) else { return nil }
        let instructions = "You classify a question for a voice assistant on smart glasses. The "
            + "assistant knows the current date and time, the wearer's upcoming calendar events and "
            + "facts the wearer asked it to remember, plus ordinary general knowledge. It has no "
            + "internet unless it asks for it. Decide only whether answering needs the internet."
        var prompt = "Today is " + Date().formatted(date: .complete, time: .omitted) + ".\n"
        let recent = recentTranscript()
        if !recent.isEmpty { prompt += "Recent conversation:\n" + recent + "\n" }
        prompt += "Question: " + question
        do {
            let session = LanguageModelSession(instructions: instructions)
            let response = try await session.respond(to: prompt, generating: Triage.self,
                                                     options: GenerationOptions(samplingMode: .greedy))
            return response.content.needsInternet
        } catch {
            return nil
        }
    }

    /// Apple first, cloud when needed.
    ///
    /// Two independent escalation triggers, so neither is a single point of failure: triage
    /// before answering, and inspection of the answer afterwards. Any Apple failure at either step
    /// also goes to the cloud, so the wearer always gets a reply while a key is configured.
    /// Returns the reply and a description of who produced it.
    func orchestrate(_ question: String, facts: [String]) async throws -> (String, String) {
        let cloud: (String) async throws -> (String, String) = { [self] why in
            guard let settings else { throw AssistantError("no settings wired") }
            if settings.useDirectProvider { return (try await askGemini(question, facts: facts), "Gemini, " + why) }
            return (try await askBackend(facts: facts), "backend, " + why)
        }
        let verdict = await triage(question)
        switch verdict {
        case .some(true):
            return try await cloud("Apple triage said it needs the internet")
        case .none:
            return try await cloud("Apple triage failed")
        case .some(false):
            break
        }
        let reply: String
        do { reply = try await askApple(question, facts: facts, orchestrated: true) }
        catch { return try await cloud("Apple failed: \(error.localizedDescription)") }
        if let admission = Escalation.reason(in: reply) {
            return try await cloud("Apple escalated (\(admission))")
        }
        return (reply, "Apple")
    }
}

/// Recognises an Apple answer that should be retried on the cloud.
enum Escalation {
    /// What the orchestrated prompt asks the model to say when it cannot answer.
    static let sentinel = "ESCALATE"

    /// Phrases the model uses when it apologises instead of using the sentinel. Deliberately
    /// specific to lacking information; "I don't know" alone would also catch honest answers
    /// about the wearer's own life ("I don't know where you parked").
    static let admissions = [
        "no internet", "not have internet", "without internet",
        "don't have access", "do not have access", "cannot access", "can't access", "unable to access",
        "real-time", "real time information", "up-to-date information", "current information",
        "latest information", "cannot browse", "can't browse", "unable to browse",
        "i cannot look up", "i can't look up", "unable to look up", "cannot check", "can't check",
    ]

    /// Tolerant on purpose. On the first device test the model answered "ESCALE" - the
    /// sentinel with letters dropped - and an exact match let it through to the screen. A short
    /// reply that starts with "ESCAL" is the sentinel, however it was spelled.
    static func reason(in reply: String) -> String? {
        let letters = reply.uppercased().filter { $0.isLetter }
        if letters.contains(sentinel) { return "said " + sentinel }
        if letters.count <= 12, letters.hasPrefix("ESCAL") { return "said \(reply.prefix(12)) (sentinel misspelled)" }
        let lower = reply.lowercased()
        return admissions.first { lower.contains($0) }.map { "said \"\($0)\"" }
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

    /// The orchestrated variant: instead of apologising, hand the question on with one word.
    static let capabilitiesWithEscalation: String =
        "What you can actually see: the wearer's current local time, their upcoming calendar "
        + "events, and any facts they asked you to remember. These are supplied to you in this "
        + "prompt. You cannot see their screen, read their messages, or browse their files. You "
        + "have no internet access. If a good answer needs current information or a web lookup "
        + "(weather, news, prices, opening hours, specific places or products), reply with the "
        + "single word " + Escalation.sentinel + " and nothing else, and another assistant with "
        + "internet access will answer instead. Never guess at current information."
}
