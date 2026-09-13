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
    enum State: Equatable { case off, publishing, advertising, connected, error(String) }

    @Published var state: State = .off
    @Published var deviceName: String = ""

    /// Fired when the glasses subscribe, i.e. the link is ready (used for auto-push).
    var onReady: (() -> Void)?

    // Must match BleServer.kt / BleCentral.kt on the glasses.
    private let hudService = CBUUID(string: "6E400001-B5A3-F393-E0A9-77656B657962")
    private let hudNotify  = CBUUID(string: "6E400003-B5A3-F393-E0A9-77656B657962")

    private var manager: CBPeripheralManager!
    private var notifyChar: CBMutableCharacteristic!
    private var queue: [Data] = []
    private var subscribedCentral: CBCentral?
    /// Guards against publishing the service more than once (see startAdvertising).
    private var servicePublished = false

    /// Bytes that actually fit in one notification for the connected central. Hardcoding this
    /// silently truncates when the negotiated MTU is smaller than assumed.
    private var maxUpdateLength: Int { subscribedCentral?.maximumUpdateValueLength ?? 20 }

    override init() {
        super.init()
        // The restore identifier opts this manager into state preservation, so iOS can relaunch the
        // app into the background to service an existing link instead of the link dying with the UI.
        manager = CBPeripheralManager(
            delegate: self, queue: nil,
            options: [CBPeripheralManagerOptionRestoreIdentifierKey: "HUDCompanionPeripheral"])
    }

    /// Publish the service and start advertising so the glasses can find us.
    ///
    /// Called from more than one place (state changes, the UI), so it must be idempotent. Without
    /// the guard the service gets published twice and the glasses' GATT database ends up with two
    /// instances of it; a central that picks the orphaned one subscribes successfully and then
    /// receives nothing, because `updateValue` only reaches subscribers of the live instance.
    func startAdvertising() {
        guard manager.state == .poweredOn else { return }
        guard !servicePublished else {
            if !manager.isAdvertising { beginAdvertising() }
            return
        }
        manager.stopAdvertising()
        manager.removeAllServices()
        // Reference implementation uses empty permissions for a notify-only characteristic;
        // [.readable] here can stop iOS reporting the central's subscribe.
        notifyChar = CBMutableCharacteristic(
            type: hudNotify, properties: [.notify], value: nil, permissions: [])
        let svc = CBMutableService(type: hudService, primary: true)
        svc.characteristics = [notifyChar]
        // Advertise only once the service is actually published (see didAdd). Advertising
        // immediately after add() is a race and can leave us advertising without the service.
        // Claim the slot before add() rather than in didAdd: the callback is async, so anything that
        // calls this again in between would publish a second copy of the service.
        servicePublished = true
        manager.add(svc)
        state = .publishing
    }

    /// Send one JSON message: 4-byte big-endian length + UTF-8, chunked to the negotiated MTU.
    @discardableResult
    func send(type: String, data: [String: Any]) -> Bool {
        guard notifyChar != nil else { state = .error("service not published"); return false }
        // Without a subscriber updateValue has nobody to deliver to and silently discards the
        // payload, so reporting success here would be a lie the UI then shows to the user.
        guard subscribedCentral != nil else { state = .error("glasses not subscribed"); return false }
        let payload: [String: Any] = ["type": type, "data": data]
        guard let json = try? JSONSerialization.data(withJSONObject: payload) else { return false }
        var framed = Data()
        var len = UInt32(json.count).bigEndian
        withUnsafeBytes(of: &len) { framed.append(contentsOf: $0) }
        framed.append(json)

        let mtu = maxUpdateLength
        var offset = 0
        queue.removeAll()
        while offset < framed.count {
            let end = min(offset + mtu, framed.count)
            queue.append(framed.subdata(in: offset..<end))
            offset = end
        }
        flush()
        return true
    }

    private func beginAdvertising() {
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [hudService],
            CBAdvertisementDataLocalNameKey: "HUD",
        ])
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
    /// Called before `peripheralManagerDidUpdateState` when iOS relaunches us for a Bluetooth event.
    /// The services it hands back are already published, so republishing would create a duplicate.
    func peripheralManager(_ p: CBPeripheralManager,
                           willRestoreState dict: [String: Any]) {
        let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] ?? []
        if let restored = services.first(where: { $0.uuid == hudService }),
           let ch = restored.characteristics?.first(where: { $0.uuid == hudNotify })
                    as? CBMutableCharacteristic {
            notifyChar = ch
            servicePublished = true
        }
    }

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        switch p.state {
        case .poweredOn: startAdvertising()
        case .poweredOff: servicePublished = false; state = .off
        default: state = .error("bluetooth \(p.state.rawValue)")
        }
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentral = central
        deviceName = "Glasses"
        state = .connected
        onReady?()
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentral = nil
        queue.removeAll()
        state = .advertising
    }

    func peripheralManagerIsReady(toUpdateSubscribers p: CBPeripheralManager) { flush() }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            servicePublished = false
            state = .error("publish failed: \(error.localizedDescription)")
            return
        }
        beginAdvertising()
        // confirm the radio actually started, rather than assuming
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self else { return }
            if self.state != .connected {
                self.state = p.isAdvertising ? .advertising : .error("radio not advertising")
            }
        }
    }
}
