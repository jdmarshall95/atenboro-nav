# 2ГИС для iOS: есть ли аналог Dashboard AIDL?

Исследование проведено на расшифрованном образе `ru.doublegis.grymmobile` **7.29**
(`private/ru.doublegis.grymmobile_7.29_und3fined.ipa`, 246 МБ). Цель — понять,
существует ли на iOS поверхность, эквивалентная нашему источнику навигационных
данных: сервису `ru.dublgis.api.IDashboardInformationService` с JSON-дампом.

Краткий ответ: **внешнего (inter-process) API нет**. Понятие «Dashboard» в iOS-версии
живёт, но только как внутрипроцессный реактивный канал. Единственная настоящая
«внешняя» поверхность для навигации — **CarPlay**, и там 2ГИС сам выступает
потребителем собственного ядра, ровно в той же роли, что и наше приложение на Android.

---

## 1. Карта бандла

| Компонент | Значение |
|---|---|
| Исполняемый файл | `ru.doublegis.grymmobile`, Mach-O arm64, ~218 МБ |
| Версия | `CFBundleShortVersionString` 7.29.0, build 7.29.0.14 |
| Фреймворки в `Frameworks/` | только `ClickstreamAnalytics`, `SIDSDK` — остальное статически слинковано |
| Расширения | `Dialer`, `LocationServiceExtension`, `NotificationServiceExtension`, `Stickers`, `Widgets` |
| Схемы URL | `dgis`, `grymmobile`, `ru-doublegis-grymmobile`, `urbi`, `zapravkigis` |
| UIBackgroundModes | `audio`, `fetch`, `location`, `processing`, `remote-notification`, `bluetooth-central`, `bluetooth-peripheral` |
| Entitlements | `com.apple.developer.carplay-maps` |

Слоистость кода: Objective-C (`GRK*`) + Swift-модули (`VNCarPlay`, `VNDashboard`,
`VNNavigator`, `VNI`, `VNIntentsServices`, `SwiftBindings`) поверх общего **C++-ядра**.

---

## 2. CarPlay объявлен на уровне манифеста

В `Info.plist` прописаны три сценарных роли CarPlay — это самый явный «аналог AIDL»
во всём бандле:

```
UIApplicationSceneManifest:
  CPSupportsDashboardNavigationScene = true
  CPSupportsInstrumentClusterNavigationScene = true
  UIApplicationSupportsMultipleScenes = true

  CPTemplateApplicationDashboardSceneSessionRoleApplication
      → CarPlayDashboardSceneConfiguration / CarPlayDashboardSceneDelegate
  CPTemplateApplicationInstrumentClusterSceneSessionRoleApplication
      → CarPlayInstrumentClusterSceneConfiguration / CarPlayInstrumentClusterSceneDelegate
  CPTemplateApplicationSceneSessionRoleApplication
      → CarPlayTemplateSceneConfiguration / CarPlayDelegate
  UIWindowSceneSessionRoleApplication
      → SceneDelegate
```

То есть на iOS есть и «приборка» (Instrument Cluster), и «дашборд» (Dashboard) —
те же два режима вывода, что мы реализуем на OLED-панельке. Но доступны они
только экранам CarPlay, а не сторонним приложениям.

---

## 3. Модуль `VNCarPlay` — функциональный близнец нашего потребителя

48 классов Swift-модуля, которые ровно то же самое, что делает Atenboro Nav,
но для экрана автомобиля:

**Манёвры и маршрут**

- `ICarManeuverFactory` / `CarManeuverFactory` — фабрика стрелок манёвра;
- `CarPlayTripFactory`, `CarPlayRouteManager`, `ActiveRouteInfoModel`;
- `CarPlayNavigationSessionService` — сессия навигации.

**Приборный экран и скорость**

- `CarPlayMapViewController` (+ `SpeedometerShiftInfo`), `CarPlayMapTemplateWrapper`;
- `CarPlayOverspeedAlertView`, `CarPlayLimits`;
- `CarPlayDashboardMapViewController` / `...VM`;
- `CarPlayInstrumentClusterVC` / `...VM`.

**Пробки, ETA, прочее**

- `JamInfoView` / `JamInfoVM`;
- `RouteInfoBlockViewVM`, `RouteETACountersViewVM`;
- `CarPlayContinueOnDeviceVC` / `VM`, `CarPlayVPNBlockOverlayVC`;
- `CarPlayAssembly`, `CarPlayPresentingProvider`, `CarPlaySessionConfigurationProvider`,
  `CarPlayMapThemeService`, `CarPlaySpecificUserSettings`, `Zoomer`, `Container`;
- провайдеры шаблонов: Search / Settings / VoiceControl / Bookmarks / Favorites /
  Metarubrics / History.

Вывод: 2ГИС на iOS **сам пишет «дашборд-приложение»** к своему ядру. На Android
эту работу за нас делает внешний AIDL-контракт; на iOS он не нужен, потому что
потребитель — тот же самый процесс.

---

## 4. Dashboard-концепция внутри процесса

Классы и типы, отвечающие за «дашборд»:

```
GRKDashboardInfo            GRKDashboardDetector       GRKCoreDashboardDetector
GRKDashboardBannerProvider  GRKClusterUIInfoService    GRKCoreClusterUIInfoService
N15DashboardModule13DashboardInfoE
N15DashboardModule14IDashboardInfoE
../project/v4core/Projects/DashboardModule/src/DashboardInfo.cpp
```

Передача данных — реактивная, а не IPC:

```
@"RACSignal"16@?0@"<GRKDashboardInfo>"
@"<GRKCancellable>"24@0:8@?<v@?@"<GRKDashboardInfo>">16
T{shared_ptr<const DashboardModule::IDashboardInfo>...},R,N,V_dashboard_info
So16GRKDashboardInfo_p
$s3VNI27IMapAndDashboardInfoServiceP
```

Селекторы: `dashboardInfo`, `dashboardInfoSignal`, `dashboardInfoChangedCancellable`,
`dashboardInfoWithReceiver:`, `lastKnownDashboardInfo`, `onDashboardInfoEvent`,
`isDashboardInForeground`, `mapAndDashboardInfoService`.

То есть вместо `registerDashboardInformationCallback()` — подписка на
`RACSignal`/`unicore::stateful_channel` внутри одного процесса. Перехода через
границу приложения нет, значит и сериализовать JSON не во что.

> Уточнение: `GRKClusterUIInfoService` — это **кластеры друзей на карте**
> (`dgis::core::social::friends_on_map::ui_info::ClusterUiInfo`), а не
> `CPInstrumentClusterController`. Совпадение названий случайное.

---

## 5. Ключи нашего AIDL-JSON в iOS-бинарнике

Точный поиск по строкам 218-мегабайтного исполняемого файла:

| Ключ (Android AIDL) | Хитов в iOS |
|---|---|
| `activeNavigationMode` | 0 |
| `maneuverIcon` / `maneuverDescription` / `maneuverDistance` | 0 |
| `badLocation` | 0 |
| `trafficCameraType` / `Subtype` / `DistancePercent` | 0 |
| `jamInfoDuration` / `jamInfoLengthMeters` | 0 |
| `trafficLightColor` / `Arrow` / `Countdown` | 0 |
| `arrivalTime` / `remainingTime` / `totalDistance` / `speedLimit` / `progress` | 1–34, но это общие слова других подсистем |
| `dublgis.api` | 0 |
| `DashboardInformationService` | 0 |
| `getDashboardInformationJSON` | 0 |
| `x-callback-url` | 0 |

Ни пространства имён `ru.dublgis.api`, ни метода `getDashboardInformationJSON`,
ни поддержки `x-callback-url`, ни общего App Group для сторонних приложений —
на iOS нет ни одного из трёх «крючков», за которые мог бы зацепиться внешний клиент.

---

## 6. «Код пишут те же люди» — подтверждено

В бинарнике сохранились пути сборки общего C++-ядра:

```
../project/native-sdk/cpp/src/map_routing/src/ManeuverUtils.cpp
../project/native-sdk/cpp/src/map_routing/src/zenith/ZenithRouteManeuversFactory.cpp
../project/v4core/modules/transport/modules/navigation/src/plugins/next_maneuver/NextManeuver.cpp
../project/v4core/modules/transport/modules/navigation/src/ui/ManeuverInstruction.cpp
../project/v4core/Projects/DashboardModule/src/DashboardInfo.cpp
```

Protobuf-описания общие с Android:

```
protobuf/common/LaneManeuverType.proto
protobuf/common/TrafficLight.proto
protobuf/routing/SmartRoute.proto
protobuf/websocket/TrafficLightEvent.proto
```

С-типы светофора и скорости: `CTrafficLightColor`, `CTrafficLightArrow`,
`CTrafficLightData`, `CTrafficLightState`, `IRouteTrafficLightData`,
`ILottieTrafficLightGenerator`, `CSpeedLimitFeedbackState`,
`ExceedSpeedLimitDetector`, `CameraSpeedLimitView`.

**Но словарь манёвров разный.** На iOS — грубый перечислимый тип из 10 значений:

```
ManeuverType_Undef, Forward, Left, Right, SlightlyLeft, SlightlyRight,
SharplyLeft, SharplyRight, Turnover, RightWithLeftTurn
```

Нам же на Android приходится разбирать ~40 кодовых имён PDF-глифов
(`crossroad_left`, `ringroad_right_90`, `turn_over`, `stairs_up`, …), и на iOS
таких имён нет вообще (0 хитов) — иконки там генерируются, а не леят по имени.
`CarPlayAssets.bundle` содержит только `navigator_cursor_promo`.

---

## 7. Внешние поверхности iOS-версии

Раз AIDL нет, сторонний клиент на iOS мог бы использовать только:

1. **CarPlay** — единственный полноценный канал навигационных данных наружу
   (Dashboard + Instrument Cluster + Template-сцены).
2. **App Intents / Siri** — модуль `VNIntentsServices`: `AppIntentsService`,
   `RouteToHome`, `RouteToWork`, `RouteToPoint`, `FreeRoam`, `FindFriend`,
   `FriendsOnMap`, `RoadEvent`, модели `Search`. Это «построить маршрут»,
   а не «поток данных о манёврах».
3. **Live Activities / ActivityKit** — 207 упоминаний, включая
   `tapOnNaviDynamicIslandWhileNavigation`, `navigatorReturningStateURL`;
   расширение `ru.doublegis.grymmobile.widgets`.
4. **URL-схемы** — фактически только `dgis://2gis.ru/profile` и
   `dgis://appclip.2gis.ru/id/`.

---

## 8. Итог для проекта

- Аналога Dashboard AIDL на iOS **нет**: нет межпроцессного сервиса, нет JSON-дампа,
  нет документированного контракта для третьих приложений.
- Ближайший функциональный аналог — **CarPlay-поверхность** (`VNCarPlay`), где 2ГИС
  сам потребляет своё ядро через `ICarManeuverFactory`, `ActiveRouteInfoModel`,
  `CarPlayNavigationSessionService`. Это подтверждает правильность нашей архитектуры:
  внешний потребитель данных о манёврах — нормальная, предусмотренная роль.
- Гипотеза «пишут те же люди» верна на уровне C++-ядра (`native-sdk/cpp`, `v4core`,
  общие protobuf), но **не** на уровне словаря манёвров: iOS оперирует 10 значениями
  `ManeuverType_*`, Android — ~40 кодовыми именами глифов.
- Практический вывод: перенос Atenboro Nav на iOS означал бы не «подключиться к API»,
  а **стать CarPlay-приложением** либо ждать, пока 2ГИС откроет внешний API
  (App Group/хуки сейчас не предоставляют ничего подходящего).

---

## Приложение. Инструменты

В образе не было ни `class-dump`, ни Swift-метаданных в удобном виде, поэтому
исследование вели «в лоб»:

```bash
unzip -q private/ru.doublegis.grymmobile_7.29_und3fined.ipa -d /tmp/gisios

plutil -convert xml1 -o - Payload/ru.doublegis.grymmobile.app/Info.plist
otool -L Payload/ru.doublegis.grymmobile.app/ru.doublegis.grymmobile
codesign -d --entitlements :- Payload/ru.doublegis.grymmobile.app

# классы и селекторы ObjC
otool -v -s __TEXT __objc_classname ru.doublegis.grymmobile
otool -v -s __TEXT __objc_methname  ru.doublegis.grymmobile   # 46 475 селекторов

# поиск по строкам (обязательно LC_ALL=C, иначе sort падает на бинарном мусоре)
export LC_ALL=C
strings -a - ru.doublegis.grymmobile | sort -u    # 703 894 строки
```

Замечания по окружению: `timeout` в macOS отсутствует; `class-dump` нет в Homebrew;
`nm -m` не показывает C++-символы `DashboardModule` (stripped) — имена восстановлены
из строк и mangling (`N15DashboardModule13DashboardInfoE`).
