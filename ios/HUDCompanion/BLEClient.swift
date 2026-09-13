import CoreBluetooth
import Foundation
import Combine

/// Connects to the Rokid glasses and talks to OUR launcher's GATT service.
///
/// The glasses' controller allows only one advertiser and Rokid already uses it, so our launcher
/// cannot advertise its own UUID. And because the glasses are already connected to iOS via the Rokid
/// app, a normal scan will NOT surface them. So we primarily use retrieveConnectedPeripherals to grab
/// the already-connected glasses, then discover our service among theirs; a broad scan is a fallback.
final class BLEClient: NSObject, ObservableObject {
    enum State: Equatable { case off, scanning, connecting, ready, error(String) }

    @Published var state: State = .off
    @Published var deviceName: String = ""

    /// Fired when the write channel is ready (used for auto-push).
    var onReady: (() -> Void)?

    // Our launcher's service (must match BleServer.kt)
    private let hudService = CBUUID(string: "6E5D0001-B00B-4B1D-8B00-0000000000A1")
    private let hudWrite   = CBUUID(string: "6E5D0002-B00B-4B1D-8B00-0000000000A1")
    private let hudNotify  = CBUUID(string: "6E5D0003-B00B-4B1D-8B00-0000000000A1")
    // Services the glasses are known to expose, used only to find the already-connected peripheral.
    private let rokidService = CBUUID(string: "00009400-0000-1000-8000-00805F9B34FB")

    private var central: CBCentralManager!
    private var glasses: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var retryTimer: Timer?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    /// Find the glasses: prefer the already-connected device, fall back to scanning, and keep retrying.
    func startScan() {
        guard central.state == .poweredOn else { return }
        if state != .ready { state = .scanning }
        if tryConnected() { return }
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        scheduleRetry()
    }

    private func tryConnected() -> Bool {
        let candidates = central.retrieveConnectedPeripherals(withServices: [rokidService, hudService])
        guard let p = candidates.first else { return false }
        connect(p, name: p.name ?? "Glasses")
        return true
    }

    private func scheduleRetry() {
        retryTimer?.invalidate()
        retryTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: true) { [weak self] _ in
            guard let self, self.state != .ready else { self?.retryTimer?.invalidate(); return }
            _ = self.tryConnected()
        }
    }

    private func connect(_ p: CBPeripheral, name: String) {
        glasses = p
        deviceName = name
        state = .connecting
        central.stopScan()
        central.connect(p, options: nil)
    }

    /// Send one JSON message, framed as 4-byte big-endian length + UTF-8, chunked to the MTU.
    func send(type: String, data: [String: Any]) {
        guard let p = glasses, let ch = writeChar else { state = .error("not connected"); return }
        let payload: [String: Any] = ["type": type, "data": data]
        guard let json = try? JSONSerialization.data(withJSONObject: payload) else { return }
        var framed = Data()
        var len = UInt32(json.count).bigEndian
        withUnsafeBytes(of: &len) { framed.append(contentsOf: $0) }
        framed.append(json)

        let mtu = max(20, p.maximumWriteValueLength(for: .withoutResponse))
        var offset = 0
        while offset < framed.count {
            let end = min(offset + mtu, framed.count)
            p.writeValue(framed.subdata(in: offset..<end), for: ch, type: .withoutResponse)
            offset = end
        }
    }
}

extension BLEClient: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn: startScan()
        case .poweredOff: state = .off
        default: state = .error("bluetooth \(c.state.rawValue)")
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi: NSNumber) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? (p.name ?? "")
        let services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []
        let looksLikeGlasses = services.contains(rokidService)
            || name.lowercased().contains("rokid") || name.lowercased().contains("glass")
        guard looksLikeGlasses else { return }
        connect(p, name: name.isEmpty ? "Glasses" : name)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.delegate = self
        p.discoverServices([hudService])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        state = .error("connect failed"); startScan()
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        writeChar = nil
        if state == .ready { state = .scanning }
        startScan()
    }
}

extension BLEClient: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = p.services?.first(where: { $0.uuid == hudService }) else {
            // this peripheral doesn't have our service; drop it and keep looking
            state = .scanning
            central.cancelPeripheralConnection(p)
            startScan()
            return
        }
        p.discoverCharacteristics([hudWrite, hudNotify], for: svc)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor svc: CBService, error: Error?) {
        for ch in svc.characteristics ?? [] {
            if ch.uuid == hudWrite { writeChar = ch }
            if ch.uuid == hudNotify { p.setNotifyValue(true, for: ch) }
        }
        if writeChar != nil {
            retryTimer?.invalidate()
            state = .ready
            onReady?()
        } else {
            state = .error("write characteristic missing")
        }
    }
}
