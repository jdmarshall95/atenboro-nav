import Foundation

/// Снапшот для прошивки: то же самое, что отдаёт Android-версия
/// (`NavUpdate.toJson()`), чтобы ESP8266 не знал, с какой платформы данные.
public struct NavUpdate: Equatable, Sendable {

    /// Токен стрелки для прошивки: left / right / slight_left / …
    public var turn: String
    /// Исходный тип манёвра (для отладки и Live Activity).
    public var maneuverType: ManeuverType
    /// Расстояние до манёвра, метры (−1 — нет данных).
    public var distM: Int
    public var camera: Bool
    public var camM: Int
    public var camKmh: Int
    public var camPct: Int
    public var ts: Date
    public var street: String?
    public var navigating: Bool
    public var navMode: String
    public var progress: Int
    public var trafficLightColor: String
    public var trafficLightCountdown: Int
    public var jamMin: Int
    /// Имя источника, из которого пришёл снапшот (`liveactivity`, `carplay`, …).
    public var source: String
    public var lastError: String?

    public init(
        turn: String = "none",
        maneuverType: ManeuverType = .undef,
        distM: Int = -1,
        camera: Bool = false,
        camM: Int = -1,
        camKmh: Int = -1,
        camPct: Int = -1,
        ts: Date = Date(),
        street: String? = nil,
        navigating: Bool = false,
        navMode: String = "",
        progress: Int = -1,
        trafficLightColor: String = "",
        trafficLightCountdown: Int = -1,
        jamMin: Int = -1,
        source: String = "",
        lastError: String? = nil
    ) {
        self.turn = turn
        self.maneuverType = maneuverType
        self.distM = distM
        self.camera = camera
        self.camM = camM
        self.camKmh = camKmh
        self.camPct = camPct
        self.ts = ts
        self.street = street
        self.navigating = navigating
        self.navMode = navMode
        self.progress = progress
        self.trafficLightColor = trafficLightColor
        self.trafficLightCountdown = trafficLightCountdown
        self.jamMin = jamMin
        self.source = source
        self.lastError = lastError
    }

    /// Из внутреннего дампа 2ГИС — в снапшот для прошивки.
    public init(info: DashboardInfo, source: String) {
        self.init(
            turn: info.maneuverType.hudToken,
            maneuverType: info.maneuverType,
            distM: info.maneuverDistance,
            camera: info.cameraPresent,
            camM: info.cameraDistance,
            camKmh: info.cameraSpeedLimit,
            camPct: info.cameraDistancePercent,
            ts: info.timestamp,
            street: info.street,
            navigating: info.isNavigating,
            navMode: info.activeNavigationMode,
            progress: info.progress,
            trafficLightColor: info.trafficLightColor,
            trafficLightCountdown: info.trafficLightCountdown,
            jamMin: info.jamInfoDuration,
            source: source
        )
    }
}

public extension NavUpdate {

    func toJSONData(pretty: Bool = false) -> Data {
        var raw: [String: Any] = [
            "turn": turn,
            "dist_m": distM,
            "camera": camera,
            "cam_m": camM,
            "cam_kmh": camKmh,
            "ts": Int(ts.timeIntervalSince1970)
        ]
        if maneuverType != .undef { raw["maneuver_type"] = maneuverType.rawValue }
        if camPct >= 0 { raw["cam_pct"] = camPct }
        if !navMode.isEmpty { raw["nav_mode"] = navMode }
        if progress >= 0 { raw["progress"] = progress }
        if !trafficLightColor.isEmpty { raw["tl"] = trafficLightColor }
        if trafficLightCountdown >= 0 { raw["tl_s"] = trafficLightCountdown }
        if jamMin > 0 { raw["jam_min"] = jamMin }
        if let street, !street.isEmpty { raw["street"] = street }
        if !source.isEmpty { raw["source"] = source }
        let opts: JSONSerialization.WritingOptions = pretty
            ? [.prettyPrinted, .sortedKeys]
            : [.sortedKeys]
        return (try? JSONSerialization.data(withJSONObject: raw, options: opts)) ?? Data("{}".utf8)
    }
}
