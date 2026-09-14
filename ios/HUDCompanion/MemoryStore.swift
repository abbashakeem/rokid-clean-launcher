import Combine
import Foundation
import SwiftUI

/// Facts the wearer has asked the assistant to remember, owned by the phone.
///
/// The backend runs on an ephemeral filesystem, so anything it writes is lost on every restart or
/// redeploy - the wrong place for something whose entire value is persistence. The phone is always
/// with the wearer, its storage genuinely survives, and it costs nothing.
///
/// Note the facts still travel to the backend and into the model prompt with each question, the
/// same as calendar and weather already do. They are private at rest, not in transit; anything
/// that must stay on the device should not be stored here at all.
@MainActor
final class MemoryStore: ObservableObject {
    @Published private(set) var facts: [String] = []

    private let key = "hud.remembered.facts"
    private let limit = 200

    init() { load() }

    private func load() {
        facts = UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    private func save() {
        UserDefaults.standard.set(facts, forKey: key)
    }

    /// Detects an explicit "remember ..." request and stores it. Returns the confirmation to speak
    /// back, or nil when the utterance was an ordinary question.
    ///
    /// Explicit only, on purpose: letting the model decide what to keep accumulates junk and makes
    /// what it knows unpredictable. This is trivially reversible and obvious to the wearer.
    func capture(from transcript: String) -> String? {
        let text = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = text.lowercased()
        for prefix in ["remember that ", "remember "] where lower.hasPrefix(prefix) {
            let fact = String(text.dropFirst(prefix.count))
                .trimmingCharacters(in: CharacterSet(charactersIn: " ."))
            guard !fact.isEmpty else { return nil }
            add(fact)
            return "Noted: \(fact)."
        }
        return nil
    }

    func add(_ fact: String) {
        facts.append(fact)
        if facts.count > limit { facts.removeFirst(facts.count - limit) }
        save()
    }

    func delete(at offsets: IndexSet) {
        facts.remove(atOffsets: offsets)
        save()
    }

    func clear() {
        facts.removeAll()
        save()
    }
}
