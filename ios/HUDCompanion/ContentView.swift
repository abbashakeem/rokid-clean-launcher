import SwiftUI

struct ContentView: View {
    @StateObject private var ble = BLEClient()
    @StateObject private var cal = CalendarSync()
    @State private var lastPush = ""

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
                    Button {
                        let events = cal.events()
                        ble.send(type: "calendar", data: ["events": events])
                        lastPush = "Pushed \(events.count) events at \(Date().formatted(date: .omitted, time: .shortened))"
                    } label: {
                        Label("Push to glasses", systemImage: "arrow.up.circle.fill")
                    }
                    .disabled(ble.state != .ready || !cal.authorized)
                    if !lastPush.isEmpty { Text(lastPush).font(.caption).foregroundColor(.secondary) }
                }
            }
            .navigationTitle("HUD Companion")
            .onAppear { if !cal.authorized { cal.requestAccess() } }
        }
    }

    private var statusColor: Color {
        switch ble.state {
        case .ready: return .green
        case .scanning, .connecting: return .yellow
        default: return .red
        }
    }
    private var statusText: String {
        switch ble.state {
        case .off: return "Bluetooth off"
        case .scanning: return "Searching for glasses…"
        case .connecting: return "Connecting…"
        case .ready: return "Connected"
        case .error(let m): return m
        }
    }
}
