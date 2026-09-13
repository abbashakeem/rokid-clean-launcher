import CoreBluetooth
import Foundation

/// Connects to the Rokid glasses and talks to OUR launcher's GATT service.
///
/// The glasses' controller allows only one advertiser and Rokid already uses it, so our launcher
/// cannot advertise its own UUID. Instead we find the glasses by Rokid's advertised service, connect,
/// and discover our service among theirs on the same peripheral.
final class BLEClient: NSObject, ObservableObject {
    enum State: Equatable { case off, scanning, connecting, ready, error(String) }

    @Published var state: State = .off
    @Published var deviceName: String = ""

    // Our launcher's service (must match BleServer.kt)
    private let hudService = CBUUID(string: "6E5D0001-B00B-4B1D-8B00-0000000000A1")
    private let hudWrite   = CBUUID(string: "6E5D0002-B00B-4B1D-8B00-0000000000A1")
    private let hudNotify  = CBUUID(string: "6E5D0003-B00B-4B1D-8B00-0000000000A1")
    // Rokid's advertised service, used only to discover the glasses peripheral.
    private let rokidService = CBUUID(string: "00009400-0000-1000-8000-00805F9B34FB")

    private var central: CBCentralManager!
    private var glasses: CBPeripheral?
    private var writeChar: CBCharacteristic?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func startScan() {
        guard central.state == .poweredOn else { return }
        state = .scanning
        // Scan broadly: some stacks don't surface Rokid's service in the advertisement, so match by
        // name too (see didDiscover).
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
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
        glasses = p
        deviceName = name.isEmpty ? "Glasses" : name
        state = .connecting
        c.stopScan()
        c.connect(p, options: nil)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.delegate = self
        p.discoverServices([hudService])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        state = .error("connect failed"); startScan()
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        writeChar = nil; state = .scanning; startScan()
    }
}

extension BLEClient: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = p.services?.first(where: { $0.uuid == hudService }) else {
            // our service is not on this peripheral; keep looking
            state = .error("HUD service not found"); return
        }
        p.discoverCharacteristics([hudWrite, hudNotify], for: svc)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor svc: CBService, error: Error?) {
        for ch in svc.characteristics ?? [] {
            if ch.uuid == hudWrite { writeChar = ch }
            if ch.uuid == hudNotify { p.setNotifyValue(true, for: ch) }
        }
        state = writeChar != nil ? .ready : .error("write characteristic missing")
    }
}
