import Foundation
import Network

/// TCP-транспорт на Network.framework: короткое соединение на каждый снапшот.
///
/// Плата ESP8266 принимает JSON строкой, как и в Android-версии, поэтому
/// держать сокет открытым незачем — проще подключиться, отправить и закрыть.
public final class NetworkTransport: BoardTransport, @unchecked Sendable {

    private let host: String
    private let port: UInt16
    private let queue = DispatchQueue(label: "com.atenboro.nav.transport")
    private var lastConnected = false

    public init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }

    public func isConnected() -> Bool { lastConnected }

    public func send(_ data: Data) async throws {
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? 18765
        )
        let connection = NWConnection(to: endpoint, using: .tcp)
        let _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Bool, Error>) in
            var finished = false
            let finish: (Result<Bool, Error>) -> Void = { result in
                if finished { return }
                finished = true
                connection.cancel()
                switch result {
                case .success(let value):
                    self.lastConnected = value
                    continuation.resume(returning: value)
                case .failure(let error):
                    self.lastConnected = false
                    continuation.resume(throwing: error)
                }
            }

            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    connection.send(content: data, completion: .contentProcessed { error in
                        if let error {
                            finish(.failure(NavSourceError.transport(error.localizedDescription)))
                        } else {
                            finish(.success(true))
                        }
                    })
                case .failed(let error):
                    finish(.failure(NavSourceError.transport(error.localizedDescription)))
                case .cancelled:
                    finish(.success(false))
                default:
                    break
                }
            }

            let timeout = DispatchWorkItem {
                finish(.failure(NavSourceError.transport("таймаут подключения к \(self.host)")))
            }
            queue.asyncAfter(deadline: .now() + 5, execute: timeout)
            connection.start(queue: queue)
        }
    }
}
