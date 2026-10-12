import UIKit

/// Главный экран: один экран без скролла — герой, три статуса, две кнопки.
/// Всё остальное — в шестерёнке (`SettingsViewController`).
public final class MainViewController: UIViewController {

    private let proxy: NavProxy
    private let prefs: AppPrefs

    private let glowLayer = CAGradientLayer()
    private let heroCard = UIView()
    private let heroGradient = CAGradientLayer()
    private let statusCard = UIView()

    private let maneuverLabel = UILabel()
    private let distanceLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let themeButton = UIButton(type: .system)
    private let settingsButton = UIButton(type: .system)

    private var rowGis: StatusRow!
    private var rowBoard: StatusRow!
    private var rowNet: StatusRow!

    private let startButton = UIButton(type: .system)
    private let connectButton = UIButton(type: .system)
    private let footerLabel = UILabel()

    public init(proxy: NavProxy, prefs: AppPrefs) {
        self.proxy = proxy
        self.prefs = prefs
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.background
        buildHierarchy()
        proxy.onUpdate = { [weak self] _ in
            self?.refreshNavigation()
        }
        proxy.onStateChange = { [weak self] in
            self?.refreshStatuses()
        }
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshNavigation()
        refreshStatuses()
    }

    // MARK: - Сборка

    private func buildHierarchy() {
        glowLayer.frame = view.bounds
        view.layer.insertSublayer(glowLayer, at: 0)

        heroCard.backgroundColor = Theme.surface
        Theme.decorate(heroCard, radius: 26)
        heroCard.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(heroCard)

        heroCard.layer.addSublayer(heroGradient)
        heroGradient.opacity = 0.16
        heroGradient.cornerRadius = 26

        maneuverLabel.font = Theme.roundedFont(34, weight: .bold)
        maneuverLabel.textColor = Theme.onSurface
        maneuverLabel.text = "—"
        maneuverLabel.translatesAutoresizingMaskIntoConstraints = false
        heroCard.addSubview(maneuverLabel)

        distanceLabel.font = Theme.roundedFont(22, weight: .semibold)
        distanceLabel.textColor = Theme.gold
        distanceLabel.text = "—"
        distanceLabel.translatesAutoresizingMaskIntoConstraints = false
        heroCard.addSubview(distanceLabel)

        subtitleLabel.font = .systemFont(ofSize: 13, weight: .regular)
        subtitleLabel.textColor = Theme.onSurfaceMuted
        subtitleLabel.numberOfLines = 2
        subtitleLabel.text = "2ГИС ➔ ESP8266 OLED"
        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        heroCard.addSubview(subtitleLabel)

        settingsButton.setImage(Self.icon("gearshape", fallback: "⚙"), for: .normal)
        settingsButton.tintColor = Theme.gold
        settingsButton.addTarget(self, action: #selector(openSettings), for: .touchUpInside)
        settingsButton.translatesAutoresizingMaskIntoConstraints = false
        settingsButton.accessibilityIdentifier = "btnSettings"
        heroCard.addSubview(settingsButton)

        themeButton.setImage(Self.icon("sun.max", fallback: "☀"), for: .normal)
        themeButton.tintColor = Theme.onSurface
        themeButton.addTarget(self, action: #selector(cycleTheme), for: .touchUpInside)
        themeButton.translatesAutoresizingMaskIntoConstraints = false
        themeButton.accessibilityIdentifier = "btnTheme"
        heroCard.addSubview(themeButton)

        statusCard.backgroundColor = Theme.surface
        Theme.decorate(statusCard, radius: 22)
        statusCard.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusCard)

        let statusStack = UIStackView()
        statusStack.axis = .vertical
        statusStack.spacing = 14
        statusStack.translatesAutoresizingMaskIntoConstraints = false
        statusCard.addSubview(statusStack)

        rowGis = StatusRow(title: "2ГИС · Live Activity")
        rowBoard = StatusRow(title: "Плата ESP8266")
        rowNet = StatusRow(title: "Интернет")
        [rowGis, rowBoard, rowNet].forEach { statusStack.addArrangedSubview($0) }

        configure(button: startButton, title: "Запустить прокси", filled: true)
        startButton.addTarget(self, action: #selector(toggleProxy), for: .touchUpInside)
        startButton.accessibilityIdentifier = "btnStartProxy"

        configure(button: connectButton, title: "Подключить к плате", filled: false)
        connectButton.addTarget(self, action: #selector(pushToBoard), for: .touchUpInside)
        connectButton.accessibilityIdentifier = "btnConnectEsp"

        footerLabel.font = .systemFont(ofSize: 12, weight: .regular)
        footerLabel.textColor = Theme.onSurfaceMuted
        footerLabel.textAlignment = .center
        footerLabel.numberOfLines = 2
        footerLabel.text = "Включите маршрут в 2ГИС — прокси работает в фоне"
        footerLabel.translatesAutoresizingMaskIntoConstraints = false
        footerLabel.accessibilityIdentifier = "footerHint"

        let buttons = UIStackView(arrangedSubviews: [startButton, connectButton])
        buttons.axis = .vertical
        buttons.spacing = 10
        buttons.translatesAutoresizingMaskIntoConstraints = false
        buttons.accessibilityIdentifier = "buttonStack"
        view.addSubview(buttons)
        view.addSubview(footerLabel)

        NSLayoutConstraint.activate([
            heroCard.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            heroCard.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            heroCard.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            heroCard.heightAnchor.constraint(equalToConstant: 168),

            settingsButton.topAnchor.constraint(equalTo: heroCard.topAnchor, constant: 14),
            settingsButton.trailingAnchor.constraint(equalTo: heroCard.trailingAnchor, constant: -16),
            settingsButton.widthAnchor.constraint(equalToConstant: 34),
            settingsButton.heightAnchor.constraint(equalToConstant: 34),

            themeButton.centerYAnchor.constraint(equalTo: settingsButton.centerYAnchor),
            themeButton.trailingAnchor.constraint(equalTo: settingsButton.leadingAnchor, constant: -14),
            themeButton.widthAnchor.constraint(equalToConstant: 34),
            themeButton.heightAnchor.constraint(equalToConstant: 34),

            maneuverLabel.leadingAnchor.constraint(equalTo: heroCard.leadingAnchor, constant: 20),
            maneuverLabel.topAnchor.constraint(equalTo: heroCard.topAnchor, constant: 20),
            maneuverLabel.trailingAnchor.constraint(lessThanOrEqualTo: themeButton.leadingAnchor, constant: -8),

            distanceLabel.leadingAnchor.constraint(equalTo: maneuverLabel.leadingAnchor),
            distanceLabel.topAnchor.constraint(equalTo: maneuverLabel.bottomAnchor, constant: 6),

            subtitleLabel.leadingAnchor.constraint(equalTo: maneuverLabel.leadingAnchor),
            subtitleLabel.bottomAnchor.constraint(equalTo: heroCard.bottomAnchor, constant: -18),
            subtitleLabel.trailingAnchor.constraint(lessThanOrEqualTo: heroCard.trailingAnchor, constant: -20),

            statusCard.topAnchor.constraint(equalTo: heroCard.bottomAnchor, constant: 16),
            statusCard.leadingAnchor.constraint(equalTo: heroCard.leadingAnchor),
            statusCard.trailingAnchor.constraint(equalTo: heroCard.trailingAnchor),

            statusStack.topAnchor.constraint(equalTo: statusCard.topAnchor, constant: 18),
            statusStack.leadingAnchor.constraint(equalTo: statusCard.leadingAnchor, constant: 18),
            statusStack.trailingAnchor.constraint(equalTo: statusCard.trailingAnchor, constant: -18),
            statusStack.bottomAnchor.constraint(equalTo: statusCard.bottomAnchor, constant: -18),

            buttons.topAnchor.constraint(equalTo: statusCard.bottomAnchor, constant: 22),
            buttons.leadingAnchor.constraint(equalTo: heroCard.leadingAnchor),
            buttons.trailingAnchor.constraint(equalTo: heroCard.trailingAnchor),

            footerLabel.topAnchor.constraint(equalTo: buttons.bottomAnchor, constant: 18),
            footerLabel.leadingAnchor.constraint(equalTo: heroCard.leadingAnchor),
            footerLabel.trailingAnchor.constraint(equalTo: heroCard.trailingAnchor),
            footerLabel.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12)
        ])
    }

    private func configure(button: UIButton, title: String, filled: Bool) {
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        button.layer.cornerRadius = 16
        button.layer.cornerCurve = .continuous
        button.heightAnchor.constraint(equalToConstant: 52).isActive = true
        if filled {
            button.backgroundColor = Theme.gold
            button.setTitleColor(UIColor(red: 0.09, green: 0.08, blue: 0.03, alpha: 1), for: .normal)
        } else {
            button.backgroundColor = Theme.surface
            button.setTitleColor(Theme.gold, for: .normal)
            button.layer.borderWidth = 1
            button.layer.borderColor = Theme.gold.cgColor
        }
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        glowLayer.frame = view.bounds
        heroGradient.frame = heroCard.bounds
    }

    public override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if traitCollection.hasDifferentColorAppearance(comparedTo: previousTraitCollection) {
            glowLayer.colors = Theme.glowLayer(in: view.bounds.size).colors
        }
    }

    // MARK: - Данные

    private func refreshNavigation() {
        guard let update = proxy.bus.lastUpdate else {
            maneuverLabel.text = "—"
            distanceLabel.text = "—"
            subtitleLabel.text = "2ГИС ➔ ESP8266 OLED"
            return
        }
        maneuverLabel.text = update.maneuverType.descriptionRu
        distanceLabel.text = Formatting.distance(update.distM)
        var parts: [String] = []
        if update.progress >= 0 { parts.append(Formatting.progress(update.progress)) }
        if let seconds = proxy.bus.lastInfo?.remainingTime, seconds >= 0 {
            parts.append(Formatting.duration(seconds))
        }
        parts.append("источник: \(update.source)")
        subtitleLabel.text = parts.joined(separator: " · ")
    }

    private func refreshStatuses() {
        let gisLive = proxy.liveActivity.isLive
        rowGis.render(
            gisLive ? .ok : .pending,
            value: gisLive ? "читает маршрут" : "нет активности 2ГИС"
        )

        let board = proxy.bus.lastError == nil ? "готов" : (proxy.bus.lastError ?? "—")
        rowBoard.render(gisLive ? .pending : .off, value: board)

        rowNet.render(.pending, value: "проверка…")

        startButton.setTitle(proxy.state == .running ? "Остановить прокси" : "Запустить прокси", for: .normal)
    }

    // MARK: - Действия

    @objc private func openSettings() {
        let vc = SettingsViewController(prefs: prefs, proxy: proxy)
        present(UINavigationController(rootViewController: vc), animated: true)
    }

    @objc private func cycleTheme() {
        let mode = prefs.cycleTheme()
        prefs.applyTheme()
        themeButton.setImage(
            Self.icon(mode == .night ? "moon.fill" : "sun.max",
                      fallback: mode == .night ? "☾" : "☀"),
            for: .normal
        )
    }

    @objc private func toggleProxy() {
        if proxy.state == .running {
            proxy.stop()
        } else {
            proxy.start()
        }
        refreshStatuses()
    }

    @objc private func pushToBoard() {
        Task { @MainActor in
            let ok = await proxy.pushToBoard()
            rowBoard.render(ok ? .ok : .error, value: ok ? "связь есть" : "плата не отвечает")
        }
    }

    private static func icon(_ name: String, fallback: String) -> UIImage? {
        UIImage(systemName: name) ?? UIImage(systemName: "questionmark")
            ?? UIImage(named: fallback)
    }
}
