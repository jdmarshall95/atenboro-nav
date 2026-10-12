import UIKit

/// Палитра и градиенты: чёрный / серый / металлическое золото.
///
/// Цвета объявлены через `UIColor { traitCollection in … }`, чтобы один и тот
/// же код давал разные значения в светлом и тёмном режиме — аналог
/// `values/` и `values-night/` из Android-версии.
public enum Theme {

    // MARK: - Базовые роли

    public static var background: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.04, green: 0.04, blue: 0.05, alpha: 1)
                : UIColor(red: 0.98, green: 0.98, blue: 0.97, alpha: 1)
        }
    }

    public static var surface: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.10, green: 0.10, blue: 0.11, alpha: 0.92)
                : UIColor(red: 1.00, green: 1.00, blue: 1.00, alpha: 0.86)
        }
    }

    public static var onSurface: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.95, green: 0.95, blue: 0.94, alpha: 1)
                : UIColor(red: 0.09, green: 0.09, blue: 0.10, alpha: 1)
        }
    }

    public static var onSurfaceMuted: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.66, green: 0.66, blue: 0.64, alpha: 1)
                : UIColor(red: 0.42, green: 0.42, blue: 0.44, alpha: 1)
        }
    }

    // MARK: - Золото

    public static var gold: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 1.00, green: 0.80, blue: 0.25, alpha: 1)
                : UIColor(red: 0.72, green: 0.55, blue: 0.10, alpha: 1)
        }
    }

    public static var goldDeep: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.86, green: 0.66, blue: 0.16, alpha: 1)
                : UIColor(red: 0.55, green: 0.41, blue: 0.06, alpha: 1)
        }
    }

    /// Свечение ауры: почти незаметно днём, читается ночью.
    public static var glow: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 1.00, green: 0.80, blue: 0.25, alpha: 0.30)
                : UIColor(red: 0.93, green: 0.82, blue: 0.52, alpha: 0.16)
        }
    }

    // MARK: - Статусы

    public static var statusOK: UIColor { gold }

    public static var statusPending: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.62, green: 0.62, blue: 0.60, alpha: 1)
                : UIColor(red: 0.58, green: 0.58, blue: 0.60, alpha: 1)
        }
    }

    public static var statusOff: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.34, green: 0.34, blue: 0.35, alpha: 1)
                : UIColor(red: 0.78, green: 0.78, blue: 0.79, alpha: 1)
        }
    }

    public static var statusError: UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.94, green: 0.35, blue: 0.31, alpha: 1)
                : UIColor(red: 0.78, green: 0.22, blue: 0.20, alpha: 1)
        }
    }

    // MARK: - Градиенты

    /// Градиент окна: сверху почти чёрный, к центру — тёплый отсвет золота.
    public static func windowGradient(in size: CGSize) -> CAGradientLayer {
        let layer = CAGradientLayer()
        layer.colors = [
            background.resolvedColor(with: .init(userInterfaceStyle: .dark)).cgColor,
            background.cgColor,
            glow.cgColor
        ]
        layer.locations = [0.0, 0.45, 1.0]
        layer.startPoint = CGPoint(x: 0.5, y: 0.0)
        layer.endPoint = CGPoint(x: 0.5, y: 1.0)
        layer.frame = CGRect(origin: .zero, size: size)
        return layer
    }

    /// Градиент карточки-героя: диагональ из золота в графит.
    public static func heroGradient(in size: CGSize) -> CAGradientLayer {
        let layer = CAGradientLayer()
        layer.colors = [gold.cgColor, goldDeep.cgColor]
        layer.startPoint = CGPoint(x: 0.0, y: 0.0)
        layer.endPoint = CGPoint(x: 1.0, y: 1.0)
        layer.frame = CGRect(origin: .zero, size: size)
        return layer
    }

    /// Радиальное свечение под героем — та самая «аура».
    public static func glowLayer(in size: CGSize) -> CAGradientLayer {
        let layer = CAGradientLayer()
        layer.type = .radial
        layer.colors = [glow.cgColor, glow.cgColor, UIColor.clear.cgColor]
        layer.locations = [0.0, 0.55, 1.0]
        layer.startPoint = CGPoint(x: 0.5, y: 0.35)
        layer.endPoint = CGPoint(x: 1.0, y: 1.0)
        layer.frame = CGRect(origin: .zero, size: size)
        return layer
    }

    /// Шрифт со скруглёнными цифрами (SF Rounded) — для манёвра и дистанции.
    public static func roundedFont(_ size: CGFloat, weight: UIFont.Weight) -> UIFont {
        let base = UIFont.systemFont(ofSize: size, weight: weight)
        guard let descriptor = base.fontDescriptor.withDesign(.rounded) else { return base }
        return UIFont(descriptor: descriptor, size: size)
    }

    /// Скругление и тень карточки.
    public static func decorate(_ view: UIView, radius: CGFloat = 22) {
        view.layer.cornerRadius = radius
        view.layer.cornerCurve = .continuous
        view.layer.shadowColor = UIColor.black.cgColor
        view.layer.shadowOpacity = 0.18
        view.layer.shadowOffset = CGSize(width: 0, height: 8)
        view.layer.shadowRadius = 18
    }
}
