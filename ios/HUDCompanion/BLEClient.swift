import CoreBluetooth
import Foundation
import Combine

/// Acts as the BLE **peripheral** so the glasses can connect to us.
///
/// The obvious design (glasses advertise, phone connects) is impossible: the Rokid firmware cannot
/// advertise from a third-party app — `startAdvertising` never completes even with a clean Bluetooth
/// stack, a free advertiser slot and permissions granted. Scanning from the glasses does work, so the
/// roles are flipped. iOS supports peripheral mode well while the app is in the foreground, which is
/// all we need for a calendar push.
final class BLEClient: NSObject, ObservableObject {
    enum State: Equatable { case off, advertising, connected, error(String) }

    @Published var state: State = .off
    @Published var deviceName: String = ""

    /// Fired when the glasses subscribe, i.e. the link is ready (used for auto-push).
    var onReady: (() -> Void)?

    // Must match BleServer.kt / BleCentral.kt on the glasses.
    private let hudService = CBUUID(string: "6E5D0001-B00B-4B1D-8B00-0000000000A1")
    private let hudNotify  = CBUUID(string: "6E5D0003-B00B-4B1D-8B00-0000000000A1")

    private var manager: CBPeripheralManager!
    private var notifyChar: CBMutableCharacteristic!
    private var queue: [Data] = []

    override init() {
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: nil)
    }

    /// Publish the service and start advertising so the glasses can find us.
    func startAdvertising() {
        guard manager.state == .poweredOn else { return }
        manager.removeAllServices()
        notifyChar = CBMutableCharacteristic(
            type: hudNotify, properties: [.notify], value: nil, permissions: [.readable])
        let svc = CBMutableService(type: hudService, primary: true)
        svc.characteristics = [notifyChar]
        manager.add(svc)
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [hudService],
            CBAdvertisementDataLocalNameKey: "HUD",
        ])
        state = .advertising
    }

    /// Send one JSON message: 4-byte big-endian length + UTF-8, chunked to the negotiated MTU.
    func send(type: String, data: [String: Any]) {
        guard state == .connected, notifyChar != nil else { state = .error("glasses not connected"); return }
        let payload: [String: Any] = ["type": type, "data": data]
        guard let json = try? JSONSerialization.data(withJSONObject: payload) else { return }
        var framed = Data()
        var len = UInt32(json.count).bigEndian
        withUnsafeBytes(of: &len) { framed.append(contentsOf: $0) }
        framed.append(json)

        // 20 is the safe floor before MTU negotiation; the glasses request 512.
        let mtu = 180
        var offset = 0
        queue.removeAll()
        while offset < framed.count {
            let end = min(offset + mtu, framed.count)
            queue.append(framed.subdata(in: offset..<end))
            offset = end
        }
        flush()
    }

    private func flush() {
        while let next = queue.first {
            let ok = manager.updateValue(next, for: notifyChar, onSubscribedCentrals: nil)
            if !ok { return }              // buffer full; resumes in peripheralManagerIsReady
            queue.removeFirst()
        }
    }
}

extension BLEClient: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        switch p.state {
        case .poweredOn: startAdvertising()
        case .poweredOff: state = .off
        default: state = .error("bluetooth \(p.state.rawValue)")
        }
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        deviceName = "Glasses"
        state = .connected
        onReady?()
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        state = .advertising
    }

    func peripheralManagerIsReady(toUpdateSubscribers p: CBPeripheralManager) { flush() }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error { state = .error("publish failed: \(error.localizedDescription)") }
    }
}
