import UIKit

/// Экран настроек (шестерёнка): источники данных и вторичные статусы.
/// Аналог `SettingsActivity` из Android-версии.
public final class SettingsViewController: UIViewController {

    private let prefs: AppPrefs
    private let proxy: NavProxy

    private var switches: [SourceID: UISwitch] = [:]
    private var rows: [SourceID: StatusRow] = [:]
    private let themeButton = UIButton(type: .system)

    public init(prefs: AppPrefs, proxy: NavProxy) {
        self.prefs = prefs
        self.proxy = proxy
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.background
        title = "Настройки"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done, target: self, action: #selector(close)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "sun.max"),
            style: .plain, target: self, action: #selector(cycleTheme)
        )
        navigationItem.rightBarButtonItem?.accessibilityIdentifier = "btnTheme"
        buildHierarchy()
        refresh()
    }

    private func buildHierarchy() {
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 18
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)

        stack.addArrangedSubview(Self.sectionHeader("Источник данных"))

        for source in SourceID.allCases {
            let row = sourceRow(source)
            stack.addArrangedSubview(row)
        }

        stack.addArrangedSubview(Self.sectionHeader("Плата"))

        let hostField = UITextField()
        hostField.text = prefs.espHost
        hostField.placeholder = "адрес платы"
        hostField.textColor = Theme.onSurface
        hostField.backgroundColor = Theme.surface
        hostField.layer.cornerRadius = 14
        hostField.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 48))
        hostField.leftViewMode = .always
        hostField.accessibilityIdentifier = "fieldHost"
        hostField.heightAnchor.constraint(equalToConstant: 48).isActive = true
        hostField.addTarget(self, action: #selector(hostChanged(_:)), for: .editingDidEnd)
        stack.addArrangedSubview(hostField)

        stack.addArrangedSubview(Self.sectionHeader("Статус"))

        let boardRow = StatusRow(title: "Плата ESP8266")
        boardRow.accessibilityIdentifier = "rowBoard"
        rows[.mock] = boardRow
        stack.addArrangedSubview(boardRow)

        let hint = UILabel()
        hint.font = .systemFont(ofSize: 12)
        hint.textColor = Theme.onSurfaceMuted
        hint.numberOfLines = 0
        hint.text = "Live Activity — точный источник 2ГИС на iOS: межпроцессного "
            + "API у приложения нет, наружу публикуется только состояние Dynamic Island."
        stack.addArrangedSubview(hint)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),

            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -20),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24)
        ])
    }

    private func sourceRow(_ source: SourceID) -> UIView {
        let card = UIView()
        card.backgroundColor = Theme.surface
        Theme.decorate(card, radius: 18)

        let title = UILabel()
        title.text = source.titleRu
        title.font = .systemFont(ofSize: 16, weight: .semibold)
        title.textColor = Theme.onSurface

        let subtitle = UILabel()
        subtitle.text = source.isPrimary ? "основной, всегда включён" : source.subtitleRu
        subtitle.font = .systemFont(ofSize: 12)
        subtitle.textColor = Theme.onSurfaceMuted

        let switchView = UISwitch()
        switchView.isOn = prefs.isEnabled(source)
        switchView.isEnabled = !source.isPrimary
        switchView.onTintColor = Theme.gold
        switchView.accessibilityIdentifier = "sw_\(source.rawValue)"
        switchView.addTarget(self, action: #selector(sourceToggled(_:)), for: .valueChanged)

        let textStack = UIStackView(arrangedSubviews: [title, subtitle])
        textStack.axis = .vertical
        textStack.spacing = 3

        let container = UIStackView(arrangedSubviews: [textStack, UIView(), switchView])
        container.axis = .horizontal
        container.spacing = 12
        container.alignment = .center
        container.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(container)

        NSLayoutConstraint.activate([
            container.topAnchor.constraint(equalTo: card.topAnchor, constant: 14),
            container.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            container.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            container.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -14)
        ])

        switches[source] = switchView
        return card
    }

    private static func sectionHeader(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text.uppercased()
        label.font = .systemFont(ofSize: 12, weight: .bold)
        label.textColor = Theme.gold
        return label
    }

    private func refresh() {
        let gisLive = proxy.liveActivity.isLive
        rows[.mock]?.render(gisLive ? .ok : .off, value: gisLive ? "поток есть" : "ожидание маршрута")
    }

    @objc private func close() {
        dismiss(animated: true)
    }

    @objc private func cycleTheme() {
        prefs.cycleTheme()
        prefs.applyTheme()
    }

    @objc private func sourceToggled(_ sender: UISwitch) {
        let key = sender.accessibilityIdentifier?
            .replacingOccurrences(of: "sw_", with: "")
        guard let key, let id = SourceID(rawValue: key) else { return }
        prefs.setEnabled(sender.isOn, for: id)
        proxy.applySourceSettings()
    }

    @objc private func hostChanged(_ sender: UITextField) {
        if let text = sender.text, !text.isEmpty {
            prefs.espHost = text
        }
    }
}

