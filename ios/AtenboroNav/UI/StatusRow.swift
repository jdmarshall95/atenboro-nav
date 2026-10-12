import UIKit

/// Строка статуса: точка + название + значение.
///
/// Аналог `rowEsp` / `rowGisApi` / `rowInet` из Android-вёрстки.
public final class StatusRow: UIStackView {

    public enum Level {
        case ok
        case pending
        case off
        case error

        var color: UIColor {
            switch self {
            case .ok: return Theme.statusOK
            case .pending: return Theme.statusPending
            case .off: return Theme.statusOff
            case .error: return Theme.statusError
            }
        }
    }

    private let dot = UIView()
    private let titleLabel = UILabel()
    private let valueLabel = UILabel()

    public init(title: String) {
        super.init(frame: .zero)
        axis = .horizontal
        spacing = 10
        alignment = .center
        distribution = .equalSpacing

        dot.translatesAutoresizingMaskIntoConstraints = false
        dot.widthAnchor.constraint(equalToConstant: 10).isActive = true
        dot.heightAnchor.constraint(equalToConstant: 10).isActive = true
        dot.layer.cornerRadius = 5
        dot.backgroundColor = Theme.statusOff

        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 15, weight: .medium)
        titleLabel.textColor = Theme.onSurface

        valueLabel.font = .systemFont(ofSize: 15, weight: .semibold)
        valueLabel.textColor = Theme.onSurfaceMuted
        valueLabel.setContentHuggingPriority(.required, for: .horizontal)

        addArrangedSubview(dot)
        addArrangedSubview(titleLabel)
        addArrangedSubview(UIView())
        addArrangedSubview(valueLabel)
    }

    required init(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public func render(_ level: Level, value: String) {
        dot.backgroundColor = level.color
        valueLabel.text = value
        valueLabel.textColor = level == .error ? Theme.statusError : Theme.onSurfaceMuted
    }
}
