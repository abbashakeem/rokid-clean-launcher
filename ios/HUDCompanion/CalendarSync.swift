import EventKit
import Foundation
import Combine

/// Reads the iPhone's calendars via EventKit and lets the user choose which ones feed the glasses.
/// This is the clean fix for the original problem: only the ticked calendars are pushed, so hidden
/// or unwanted calendars never reach the HUD.
final class CalendarSync: ObservableObject {
    struct Cal: Identifiable { let id: String; let title: String; let color: CGColor? }

    @Published var calendars: [Cal] = []
    @Published var selected: Set<String> = []
    @Published var authorized = false

    private let store = EKEventStore()
    private let selectionKey = "hud_selected_calendars"

    init() {
        selected = Set(UserDefaults.standard.stringArray(forKey: selectionKey) ?? [])
    }

    func requestAccess() {
        let handler: (Bool, Error?) -> Void = { [weak self] ok, _ in
            DispatchQueue.main.async {
                self?.authorized = ok
                if ok { self?.loadCalendars() }
            }
        }
        if #available(iOS 17.0, *) {
            store.requestFullAccessToEvents { ok, err in handler(ok, err) }
        } else {
            store.requestAccess(to: .event) { ok, err in handler(ok, err) }
        }
    }

    func loadCalendars() {
        calendars = store.calendars(for: .event)
            .map { Cal(id: $0.calendarIdentifier, title: $0.title, color: $0.cgColor) }
            .sorted { $0.title.lowercased() < $1.title.lowercased() }
        // default to all on first run
        if selected.isEmpty { selected = Set(calendars.map { $0.id }) }
    }

    func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
        UserDefaults.standard.set(Array(selected), forKey: selectionKey)
    }

    /// Events in the next `days` days, from the selected calendars only, as the launcher's JSON shape.
    func events(days: Int = 7) -> [[String: Any]] {
        let cals = store.calendars(for: .event).filter { selected.contains($0.calendarIdentifier) }
        guard !cals.isEmpty else { return [] }
        let start = Date()
        let end = Calendar.current.date(byAdding: .day, value: days, to: start)!
        let predicate = store.predicateForEvents(withStart: start, end: end, calendars: cals)
        return store.events(matching: predicate)
            .sorted { $0.startDate < $1.startDate }
            .map { ev in
                [
                    "title": ev.title ?? "(untitled)",
                    "start": Int(ev.startDate.timeIntervalSince1970 * 1000),
                    "end": Int(ev.endDate.timeIntervalSince1970 * 1000),
                    "allDay": ev.isAllDay,
                    "location": ev.location ?? "",
                ]
            }
    }
}
