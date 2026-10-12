import Foundation

/// Транспорт до платы. Вынесен в протокол: настоящий сокет работает
/// через Network.framework, а тесты подставляют память вместо платы.
public protocol BoardTransport: AnyObject {
    func send(_ data: Data) async throws
    func isConnected() -> Bool
}

/// Отправка JSON на ESP8266 по TCP (тот же контракт, что у Android-версии).
public actor EspClient {

    public enum State: Equatable {
        case idle
        case connecting
        case online
        case failed(String)
    }

    public private(set) var state: State = .idle
    public private(set) var sentCount = 0
    public private(set) var lastError: String?

    private let host: String
    private let port: UInt16
    private let transport: BoardTransport?

    /// - Parameter transport: nil — использовать Network.framework.
    ///   В тестах и симуляторе передаётся `MemoryTransport`.
    public init(host: String = "192.168.4.1", port: UInt16 = 18765, transport: BoardTransport? = nil) {
        self.host = host
        self.port = port
        self.transport = transport
    }

    public func currentHost() -> String { host }
    public func currentPort() -> UInt16 { port }

    /// Отправить снапшот на плату.
    @discardableResult
    public func send(_ update: NavUpdate) async -> Bool {
        state = .connecting
        do {
            try await (transport ?? NetworkTransport(host: host, port: port)).send(update.toJSONData())
            state = .online
            sentCount += 1
            lastError = nil
            return true
        } catch {
            state = .failed(error.localizedDescription)
            lastError = error.localizedDescription
            return false
        }
    }

    /// Проверка доступности платы без отправки данных.
    public func probe() async -> Bool {
        guard let transport else { return false }
        return transport.isConnected()
    }

    public func reset() {
        state = .idle
        lastError = nil
    }
}

/// Заглушка-память: хранит всё, что в неё записали.
public final class MemoryTransport: BoardTransport {

    public private(set) var payloads: [Data] = []
    public var connected: Bool
    public var failNext: Error?

    public init(connected: Bool = true) {
        self.connected = connected
    }

    public func send(_ data: Data) async throws {
        if let failNext {
            self.failNext = nil
            throw failNext
        }
        guard connected else { throw NavSourceError.transport("плата не отвечает") }
        payloads.append(data)
    }

    public func isConnected() -> Bool { connected }

    public var lastString: String? {
        payloads.last.flatMap { String(data: $0, encoding: .utf8) }
    }
}
