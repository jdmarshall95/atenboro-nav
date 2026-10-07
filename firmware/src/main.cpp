#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <ArduinoJson.h>
#include <string.h>
#include "splash_bmp.h"
#include "arrows.h"
#include "debug_log.h"
#include "version.h"

// HW-364A: stock demo labels are often wrong; we auto-detect SDA/SCL.
static const int SCREEN_W = 128;
static const int SCREEN_H = 64;
static uint8_t oledAddr = 0x3C;
static int oledSda = 14;
static int oledScl = 12;
static bool oledReady = false;

static const char *AP_SSID = "atenboro-nav";
static const char *AP_PASS = "atenboro1";
// Пока телефон на AP и есть nav — HUD не откатываем на splash/waiting.
static const uint32_t STALE_MS = 15000;
static const uint32_t PHONE_GONE_CLEAR_MS = 20000;

Adafruit_SSD1306 display(SCREEN_W, SCREEN_H, &Wire, -1);
ESP8266WebServer server(80);

enum class Turn : uint8_t {
  None = 0,
  Left,
  Right,
  SlightLeft,
  SlightRight,
  UTurn,
  Straight,
  Roundabout,
  Arrive
};

struct NavState {
  Turn turn = Turn::None;
  int dist_m = -1;
  bool camera = false;
  int cam_m = -1;
  int cam_kmh = -1; // лимит камеры, км/ч (−1 = нет)
  int cam_pct = -1; // 2GIS AIDL: 0..100 приближения к камере (−1 = нет)
  uint32_t last_update_ms = 0;
  bool has_data = false;
  bool has_icon = false;
  uint8_t icon[128]; // legacy 32x32 mono (не используем в новом HUD)
  char street[28];
};

struct DrawnNav {
  bool valid = false;
  Turn turn = Turn::None;
  int dist_m = -999;
  bool camera = false;
  int cam_m = -999;
  int cam_kmh = -999;
  int cam_pct = -999;
  bool cam_blink_on = false;
};

NavState nav;
DrawnNav drawn;
static bool camBlinkOn = true;
static uint32_t lastCamBlinkMs = 0;
static const uint32_t CAM_BLINK_MS = 450;
// Dual-color OLED: yellow y=0..15, blue y=16..63. Blue split at x=64.
static const int BLUE_TOP = 16;
static const int BLUE_SPLIT = 64;

Turn parseTurn(const char *s) {
  if (!s) return Turn::None;
  if (!strcmp(s, "left")) return Turn::Left;
  if (!strcmp(s, "right")) return Turn::Right;
  if (!strcmp(s, "slight_left")) return Turn::SlightLeft;
  if (!strcmp(s, "slight_right")) return Turn::SlightRight;
  if (!strcmp(s, "u_turn")) return Turn::UTurn;
  if (!strcmp(s, "straight")) return Turn::Straight;
  if (!strcmp(s, "roundabout")) return Turn::Roundabout;
  if (!strcmp(s, "arrive")) return Turn::Arrive;
  return Turn::None;
}

const char *turnLabel(Turn t) {
  switch (t) {
    case Turn::Left: return "LEFT";
    case Turn::Right: return "RIGHT";
    case Turn::SlightLeft: return "SLIGHT L";
    case Turn::SlightRight: return "SLIGHT R";
    case Turn::UTurn: return "U-TURN";
    case Turn::Straight: return "STRAIGHT";
    case Turn::Roundabout: return "ROUND";
    case Turn::Arrive: return "ARRIVE";
    default: return "---";
  }
}

const uint8_t *turnBitmap(Turn t) {
  switch (t) {
    case Turn::Left:
      return BMP_LEFT;
    case Turn::SlightLeft:
      return BMP_SLIGHT_L;
    case Turn::Right:
      return BMP_RIGHT;
    case Turn::SlightRight:
      return BMP_SLIGHT_R;
    case Turn::UTurn:
      return BMP_UTURN;
    case Turn::Roundabout:
      return BMP_ROUND;
    case Turn::Arrive:
      return BMP_ARRIVE;
    case Turn::Straight:
    default:
      return BMP_STRAIGHT;
  }
}

/** HUD: <1 км — метры; 1–9.9 км — «3.8»+km; ≥10 км — целые км. */
void formatHudDistance(int meters, char *num, size_t nNum, char *unit, size_t nUnit) {
  if (meters < 0) {
    snprintf(num, nNum, "--");
    snprintf(unit, nUnit, "m");
    return;
  }
  if (meters >= 10000) {
    snprintf(num, nNum, "%d", (meters + 500) / 1000);
    snprintf(unit, nUnit, "km");
    return;
  }
  if (meters >= 1000) {
    const int tenths = (meters + 50) / 100; // 3800 → 38 → «3.8»
    snprintf(num, nNum, "%d.%d", tenths / 10, tenths % 10);
    snprintf(unit, nUnit, "km");
    return;
  }
  snprintf(num, nNum, "%d", meters);
  snprintf(unit, nUnit, "m");
}

void formatMetersOnly(int meters, char *buf, size_t n) {
  char unit[4];
  formatHudDistance(meters, buf, n, unit, sizeof(unit));
}

void formatDistance(int meters, char *buf, size_t n) {
  formatMetersOnly(meters, buf, n);
}

// Dual-color OLED: yellow band y=0..15, blue band y=16..63.
void drawVersionCentered(int16_t y) {
  char ver[16];
  snprintf(ver, sizeof(ver), "v%s", FW_VERSION);
  int16_t x1, y1;
  uint16_t w, h;
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.getTextBounds(ver, 0, 0, &x1, &y1, &w, &h);
  display.setCursor((SCREEN_W - (int)w) / 2, y);
  display.print(ver);
}

void playSplash(uint32_t totalMs = 3200) {
  if (!oledReady) return;

  const uint32_t planeMs = 500;
  const uint32_t logoMs = 1700;
  const uint32_t verMs = 500;
  const uint32_t holdMs =
      totalMs > (planeMs + logoMs + verMs) ? (totalMs - planeMs - logoMs - verMs) : 400;
  const uint32_t t0 = millis();

  // Plane rises into blue zone
  while (millis() - t0 < planeMs) {
    float p = (millis() - t0) / (float)planeMs;
    if (p > 1.0f) p = 1.0f;
    int y = 16 + (int)((1.0f - p) * 48);
    display.clearDisplay();
    display.drawBitmap(0, y, PLANE_BMP, PLANE_W, PLANE_H, SSD1306_WHITE);
    display.fillRect(0, 0, SCREEN_W, 16, SSD1306_BLACK);
    display.display();
    delay(16);
  }

  display.clearDisplay();
  display.drawBitmap(0, 16, PLANE_BMP, PLANE_W, PLANE_H, SSD1306_WHITE);
  display.display();

  // Logo wipe left→right in yellow band
  const uint32_t logoStart = millis();
  while (millis() - logoStart < logoMs) {
    float p = (millis() - logoStart) / (float)logoMs;
    if (p > 1.0f) p = 1.0f;
    p = 1.0f - (1.0f - p) * (1.0f - p);
    int reveal = (int)(p * SCREEN_W);

    display.clearDisplay();
    display.drawBitmap(0, 16, PLANE_BMP, PLANE_W, PLANE_H, SSD1306_WHITE);
    display.drawBitmap(0, 0, LOGO_BMP, LOGO_W, LOGO_H, SSD1306_WHITE);
    if (reveal < SCREEN_W) {
      display.fillRect(reveal, 0, SCREEN_W - reveal, 16, SSD1306_BLACK);
    }
    display.display();
    delay(16);
  }

  // Version types into blue band under the plane (wipe)
  const uint32_t verStart = millis();
  char ver[16];
  snprintf(ver, sizeof(ver), "v%s", FW_VERSION);
  const uint8_t verLen = strlen(ver);
  while (millis() - verStart < verMs) {
    float p = (millis() - verStart) / (float)verMs;
    if (p > 1.0f) p = 1.0f;
    uint8_t chars = (uint8_t)(p * verLen + 0.5f);
    if (chars > verLen) chars = verLen;

    display.clearDisplay();
    display.drawBitmap(0, 0, LOGO_BMP, LOGO_W, LOGO_H, SSD1306_WHITE);
    display.drawBitmap(0, 16, PLANE_BMP, PLANE_W, PLANE_H, SSD1306_WHITE);
    // clear bottom strip for version
    display.fillRect(0, 54, SCREEN_W, 10, SSD1306_BLACK);
    char partial[16];
    memcpy(partial, ver, chars);
    partial[chars] = '\0';
    int16_t x1, y1;
    uint16_t w, h;
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.getTextBounds(ver, 0, 0, &x1, &y1, &w, &h);
    display.setCursor((SCREEN_W - (int)w) / 2, 55);
    display.print(partial);
    display.display();
    delay(30);
  }

  display.clearDisplay();
  display.drawBitmap(0, 0, LOGO_BMP, LOGO_W, LOGO_H, SSD1306_WHITE);
  display.drawBitmap(0, 16, PLANE_BMP, PLANE_W, PLANE_H, SSD1306_WHITE);
  display.fillRect(0, 54, SCREEN_W, 10, SSD1306_BLACK);
  drawVersionCentered(55);
  display.display();
  delay(holdMs);
}

void drawWaiting() {
  display.clearDisplay();
  display.drawBitmap(0, 0, LOGO_BMP, LOGO_W, LOGO_H, SSD1306_WHITE);
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(22, 28);
  display.print(F("waiting phone"));
  display.setCursor(10, 48);
  display.print(F("AP: atenboro-nav"));
  display.display();
  drawn.valid = false;
}

void drawPhoneConnected(uint8_t stations) {
  display.clearDisplay();
  display.drawBitmap(0, 0, LOGO_BMP, LOGO_W, LOGO_H, SSD1306_WHITE);

  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(22, 24);
  display.print(F("PHONE LINK"));
  display.setCursor(16, 38);
  display.print(F("waiting nav"));

  char buf[20];
  snprintf(buf, sizeof(buf), "clients: %u", stations);
  int16_t x1, y1;
  uint16_t w, h;
  display.getTextBounds(buf, 0, 0, &x1, &y1, &w, &h);
  display.setCursor((SCREEN_W - (int)w) / 2, 52);
  display.print(buf);
  display.display();
  drawn.valid = false;
}

uint8_t stationCount() {
  return WiFi.softAPgetStationNum();
}

bool probeI2c(uint8_t addr) {
  Wire.beginTransmission(addr);
  return Wire.endTransmission() == 0;
}

bool scanBus(int sda, int scl, uint8_t *foundAddr) {
  Wire.begin(sda, scl);
  Wire.setClock(100000);
  delay(20);

  const uint8_t candidates[] = {0x3C, 0x3D};
  for (uint8_t addr : candidates) {
    if (probeI2c(addr)) {
      *foundAddr = addr;
      return true;
    }
  }
  // Full scan for unexpected address
  for (uint8_t addr = 1; addr < 127; addr++) {
    if (probeI2c(addr)) {
      *foundAddr = addr;
      return true;
    }
  }
  return false;
}

bool initOled() {
  // Common HW-364A wiring variants (SDA, SCL). Stock silkscreen/demo is often swapped.
  const int pairs[][2] = {
      {14, 12}, // D6=SDA, D5=SCL (many docs)
      {12, 14}, // D5=SDA, D6=SCL (working boards where labels lie)
      {4, 5},   // D2/D1 NodeMCU defaults
      {5, 4},
  };

  uint8_t addr = 0;
  bool found = false;
  for (const auto &p : pairs) {
    Serial.printf("I2C probe SDA=GPIO%d SCL=GPIO%d ... ", p[0], p[1]);
    if (scanBus(p[0], p[1], &addr)) {
      Serial.printf("FOUND 0x%02X\n", addr);
      oledSda = p[0];
      oledScl = p[1];
      oledAddr = addr;
      found = true;
      break;
    }
    Serial.println(F("none"));
  }

  if (!found) {
    Serial.println(F("No OLED on I2C — check board power / cable"));
    return false;
  }

  // Re-init bus on the winning pins before Adafruit begin
  Wire.begin(oledSda, oledScl);
  Wire.setClock(100000);
  delay(20);

  if (!display.begin(SSD1306_SWITCHCAPVCC, oledAddr)) {
    Serial.println(F("SSD1306 begin() failed (buffer/alloc)"));
    return false;
  }

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.ssd1306_command(SSD1306_DISPLAYON);
  display.dim(false);
  oledReady = true;
  Serial.printf("OLED OK SDA=%d SCL=%d addr=0x%02X\n", oledSda, oledScl, oledAddr);
  return true;
}

void drawStale() {
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(40, 20);
  display.print(F("NO LINK"));
  display.setCursor(28, 40);
  display.print(F("no update"));
  display.display();
  drawn.valid = false;
}

/** Жёлтый сектор (y=0..15): тишина без камеры; с камерой — мигание + скорость. */
void drawYellowCameraBand() {
  display.fillRect(0, 0, SCREEN_W, BLUE_TOP, SSD1306_BLACK);
  if (!nav.camera) {
    return;
  }
  if (!camBlinkOn) {
    return;
  }
  display.setTextColor(SSD1306_WHITE);
  char buf[16];
  if (nav.cam_kmh > 0) {
    snprintf(buf, sizeof(buf), "%d", nav.cam_kmh);
    display.setTextSize(2);
    int16_t x1, y1;
    uint16_t w, h;
    display.getTextBounds(buf, 0, 0, &x1, &y1, &w, &h);
    display.setCursor((SCREEN_W - (int)w) / 2, 1);
    display.print(buf);
  } else if (nav.cam_m >= 0) {
    snprintf(buf, sizeof(buf), "%dm", nav.cam_m > 9999 ? 9999 : nav.cam_m);
    display.setTextSize(1);
    int16_t x1, y1;
    uint16_t w, h;
    display.getTextBounds(buf, 0, 0, &x1, &y1, &w, &h);
    display.setCursor((SCREEN_W - (int)w) / 2, 4);
    display.print(buf);
  } else if (nav.cam_pct >= 0) {
    // 2GIS AIDL отдаёт только процент приближения — рисуем «CAM 45%»
    snprintf(buf, sizeof(buf), "CAM %d%%", nav.cam_pct > 100 ? 100 : nav.cam_pct);
    display.setTextSize(1);
    int16_t x1, y1;
    uint16_t w, h;
    display.getTextBounds(buf, 0, 0, &x1, &y1, &w, &h);
    display.setCursor((SCREEN_W - (int)w) / 2, 4);
    display.print(buf);
  } else {
    display.setTextSize(1);
    display.setCursor(50, 4);
    display.print(F("CAM"));
  }
}

/** Синий сектор: слева стрелка, справа дистанция + m. */
void drawBlueManeuverBand() {
  display.fillRect(0, BLUE_TOP, SCREEN_W, SCREEN_H - BLUE_TOP, SSD1306_BLACK);
  display.drawFastVLine(BLUE_SPLIT, BLUE_TOP, SCREEN_H - BLUE_TOP, SSD1306_WHITE);

  // Отступ от разделителя и краёв, чтобы стрелку не кропало
  const int ax = (BLUE_SPLIT - ARROW_W) / 2;
  const int ay = BLUE_TOP + (SCREEN_H - BLUE_TOP - ARROW_H) / 2;
  display.drawBitmap(ax, ay, turnBitmap(nav.turn), ARROW_W, ARROW_H, SSD1306_WHITE);

  char num[8];
  char unit[4];
  formatHudDistance(nav.dist_m, num, sizeof(num), unit, sizeof(unit));
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(2);
  int16_t x1, y1;
  uint16_t w, h;
  display.getTextBounds(num, 0, 0, &x1, &y1, &w, &h);
  const int rightCx = BLUE_SPLIT + (SCREEN_W - BLUE_SPLIT) / 2;
  display.setCursor(rightCx - (int)w / 2, BLUE_TOP + 10);
  display.print(num);
  display.setTextSize(1);
  display.getTextBounds(unit, 0, 0, &x1, &y1, &w, &h);
  display.setCursor(rightCx - (int)w / 2, BLUE_TOP + 34);
  display.print(unit);
}

// 2GIS AIDL отдаёт процент приближения к камере, который может меняться
// на каждом обновлении. Перерисовываем полосу только при заметном изменении
// (шаг 5%), иначе OLED моргает от мелкого дрожания значения.
int pctBucket(int pct) { return pct < 0 ? -1 : pct / 5; }

void syncDrawnFromNav() {
  drawn.valid = true;
  drawn.turn = nav.turn;
  drawn.dist_m = nav.dist_m;
  drawn.camera = nav.camera;
  drawn.cam_m = nav.cam_m;
  drawn.cam_kmh = nav.cam_kmh;
  drawn.cam_pct = nav.cam_pct;
  drawn.cam_blink_on = camBlinkOn;
}

void drawNavFull() {
  display.clearDisplay();
  drawYellowCameraBand();
  drawBlueManeuverBand();
  display.display();
  syncDrawnFromNav();
}

void drawNavSmart(bool forceYellow) {
  if (!drawn.valid) {
    drawNavFull();
    return;
  }

  const bool distChanged = drawn.dist_m != nav.dist_m;
  const bool camChanged = drawn.camera != nav.camera || drawn.cam_m != nav.cam_m ||
      drawn.cam_kmh != nav.cam_kmh ||
      pctBucket(drawn.cam_pct) != pctBucket(nav.cam_pct);
  const bool turnChanged = drawn.turn != nav.turn;
  const bool blinkChanged = forceYellow && drawn.cam_blink_on != camBlinkOn;

  if (!distChanged && !camChanged && !turnChanged && !blinkChanged) {
    return;
  }

  if (camChanged || blinkChanged) {
    drawYellowCameraBand();
  }
  if (distChanged || turnChanged) {
    drawBlueManeuverBand();
  }
  display.display();
  syncDrawnFromNav();
}

void render(bool forceYellow = false) {
  if (!oledReady) return;
  uint32_t now = millis();
  const bool phoneOn = stationCount() > 0;

  if (nav.has_data && phoneOn) {
    drawNavSmart(forceYellow);
    return;
  }

  if (nav.has_data && !phoneOn) {
    if (now - nav.last_update_ms > PHONE_GONE_CLEAR_MS) {
      nav.has_data = false;
      nav.has_icon = false;
      drawn.valid = false;
      drawWaiting();
    } else {
      drawStale();
    }
    return;
  }

  if (phoneOn) {
    drawPhoneConnected(stationCount());
  } else {
    drawWaiting();
  }
  drawn.valid = false;
}

static int hexNibble(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

static bool parseIconHex(const char *hex, uint8_t *out, size_t outLen) {
  if (!hex) return false;
  size_t n = strlen(hex);
  if (n != outLen * 2) return false;
  for (size_t i = 0; i < outLen; i++) {
    int hi = hexNibble(hex[i * 2]);
    int lo = hexNibble(hex[i * 2 + 1]);
    if (hi < 0 || lo < 0) return false;
    out[i] = (uint8_t)((hi << 4) | lo);
  }
  return true;
}

void handleRoot() {
  char body[320];
  uint32_t age = nav.has_data ? (millis() - nav.last_update_ms) : 0;
  snprintf(body, sizeof(body),
           "{\"ok\":true,\"version\":\"%s\",\"ip\":\"%s\",\"has_data\":%s,\"age_ms\":%lu,\"ssid\":\"%s\",\"stations\":%u}",
           FW_VERSION,
           WiFi.softAPIP().toString().c_str(),
           nav.has_data ? "true" : "false",
           (unsigned long)age,
           AP_SSID,
           stationCount());
  server.send(200, "application/json", body);
}

void handleHealth() {
  char body[96];
  snprintf(body, sizeof(body), "{\"ok\":true,\"version\":\"%s\"}", FW_VERSION);
  server.send(200, "application/json", body);
}

void handleDebugGet() {
  JsonDocument doc;
  doc["ok"] = true;
  doc["version"] = FW_VERSION;
  doc["uptime_ms"] = millis();
  doc["stations"] = stationCount();
  doc["oled"] = oledReady;
  doc["sda"] = oledSda;
  doc["scl"] = oledScl;
  doc["addr"] = oledAddr;
  doc["has_nav"] = nav.has_data;
  if (nav.has_data) {
    doc["nav_age_ms"] = millis() - nav.last_update_ms;
    doc["dist_m"] = nav.dist_m;
    doc["camera"] = nav.camera;
  }
  JsonArray events = doc["events"].to<JsonArray>();
  dbgAppendToJson(events);

  String out;
  serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void handleDebugPost() {
  if (server.method() != HTTP_POST) {
    server.send(405, "application/json", "{\"ok\":false,\"error\":\"POST only\"}");
    return;
  }
  String body = server.arg("plain");
  if (body.isEmpty()) {
    server.send(400, "application/json", "{\"ok\":false,\"error\":\"empty\"}");
    return;
  }

  JsonDocument doc;
  if (deserializeJson(doc, body)) {
    server.send(400, "application/json", "{\"ok\":false,\"error\":\"bad json\"}");
    return;
  }

  auto ingest = [](JsonObjectConst o) {
    const char *msg = o["msg"] | "";
    const char *lvlS = o["lvl"] | "i";
    char lvl = lvlS[0] ? lvlS[0] : 'i';
    if (lvl != 'i' && lvl != 'w' && lvl != 'e') lvl = 'i';
    dbgPush('p', lvl, msg);
  };

  if (doc["events"].is<JsonArray>()) {
    for (JsonObjectConst o : doc["events"].as<JsonArrayConst>()) {
      ingest(o);
    }
  } else if (doc["msg"].is<const char *>()) {
    ingest(doc.as<JsonObjectConst>());
  } else {
    server.send(400, "application/json", "{\"ok\":false,\"error\":\"need msg or events\"}");
    return;
  }

  server.send(200, "application/json", "{\"ok\":true}");
}

void handleDebugDelete() {
  dbgClear();
  dbgEsp('i', "debug cleared");
  server.send(200, "application/json", "{\"ok\":true}");
}

/** Снимок framebuffer OLED: raw 1024 байта + метаданные в заголовках. */
void handleScreen() {
  if (!oledReady) {
    server.send(503, "application/json", "{\"ok\":false,\"error\":\"no oled\"}");
    return;
  }
  const uint8_t *buf = display.getBuffer();
  const size_t bufLen = (size_t)SCREEN_W * ((SCREEN_H + 7) / 8); // 1024

  char meta[120];
  snprintf(meta, sizeof(meta), "turn=%s;dist=%d;cam=%d;cam_kmh=%d;cam_m=%d;ver=%s",
           turnLabel(nav.turn), nav.dist_m, nav.camera ? 1 : 0, nav.cam_kmh, nav.cam_m,
           FW_VERSION);

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("X-OLED-W", String(SCREEN_W));
  server.sendHeader("X-OLED-H", String(SCREEN_H));
  server.sendHeader("X-OLED-FMT", "ssd1306_page");
  server.sendHeader("X-OLED-META", meta);
  server.setContentLength(bufLen);
  server.send(200, "application/octet-stream", "");
  WiFiClient client = server.client();
  client.write(buf, bufLen);
}

void handleNav() {
  if (server.method() != HTTP_POST) {
    server.send(405, "application/json", "{\"ok\":false,\"error\":\"POST only\"}");
    return;
  }

  String body = server.arg("plain");
  if (body.isEmpty()) {
    server.send(400, "application/json", "{\"ok\":false,\"error\":\"empty body\"}");
    return;
  }

  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, body);
  if (err) {
    server.send(400, "application/json", "{\"ok\":false,\"error\":\"bad json\"}");
    return;
  }

  if (doc["turn"].is<const char *>()) {
    nav.turn = parseTurn(doc["turn"]);
  }
  if (doc["dist_m"].is<int>()) {
    nav.dist_m = doc["dist_m"].as<int>();
  }
  if (doc["camera"].is<bool>()) {
    nav.camera = doc["camera"].as<bool>();
  } else {
    nav.camera = false;
  }
  if (doc["cam_m"].is<int>()) {
    nav.cam_m = doc["cam_m"].as<int>();
  } else {
    nav.cam_m = -1;
  }
  if (doc["cam_kmh"].is<int>()) {
    nav.cam_kmh = doc["cam_kmh"].as<int>();
  } else {
    nav.cam_kmh = -1;
  }
  if (doc["cam_pct"].is<int>()) {
    nav.cam_pct = doc["cam_pct"].as<int>();
  } else {
    nav.cam_pct = -1;
  }

  // Иконки 2ГИС больше не рисуем — только встроенные стрелки нового HUD
  nav.has_icon = false;
  if (doc["icon"].is<const char *>()) {
    const char *hex = doc["icon"];
    (void)parseIconHex(hex, nav.icon, sizeof(nav.icon));
  }

  if (doc["street"].is<const char *>()) {
    const char *s = doc["street"];
    size_t j = 0;
    for (size_t i = 0; s[i] && j + 1 < sizeof(nav.street); i++) {
      unsigned char c = (unsigned char)s[i];
      if (c >= 0x20 && c <= 0x7E) {
        nav.street[j++] = (char)c;
      }
    }
    nav.street[j] = '\0';
  }

  nav.last_update_ms = millis();
  nav.has_data = true;

  Serial.printf("NAV turn=%d dist=%d cam=%d cam_kmh=%d cam_m=%d\n",
                (int)nav.turn, nav.dist_m, nav.camera ? 1 : 0, nav.cam_kmh, nav.cam_m);
  char navMsg[DBG_MSG_LEN];
  snprintf(navMsg, sizeof(navMsg), "nav t=%d d=%d ck=%d",
           (int)nav.turn, nav.dist_m, nav.cam_kmh);
  dbgEsp('i', navMsg);

  render(true);
  server.send(200, "application/json", "{\"ok\":true}");
}

void setupAp() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_OFF);
  delay(100);
  WiFi.mode(WIFI_AP);

  IPAddress ip(192, 168, 4, 1);
  IPAddress gw(192, 168, 4, 1);
  IPAddress sn(255, 255, 255, 0);
  WiFi.softAPConfig(ip, gw, sn);

  // channel 6, visible, max 4 stations
  bool ok = WiFi.softAP(AP_SSID, AP_PASS, 6, false, 4);
  Serial.printf("SoftAP %s ssid=%s pass=%s ip=%s\n",
                ok ? "OK" : "FAIL",
                AP_SSID,
                AP_PASS,
                WiFi.softAPIP().toString().c_str());
  dbgEsp(ok ? 'i' : 'e', ok ? "softap up" : "softap fail");
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();
  Serial.println(F("Atenboro Nav firmware"));
  dbgEsp('i', "boot v" FW_VERSION);

  if (!initOled()) {
    Serial.println(F("SSD1306 init failed — OLED not responding"));
    dbgEsp('e', "oled fail");
  } else {
    char oledMsg[DBG_MSG_LEN];
    snprintf(oledMsg, sizeof(oledMsg), "oled ok sda=%d scl=%d", oledSda, oledScl);
    dbgEsp('i', oledMsg);
    playSplash(3200);
  }

  setupAp();

  server.on("/", HTTP_GET, handleRoot);
  server.on("/health", HTTP_GET, handleHealth);
  server.on("/debug", HTTP_GET, handleDebugGet);
  server.on("/debug", HTTP_POST, handleDebugPost);
  server.on("/debug", HTTP_DELETE, handleDebugDelete);
  server.on("/nav", HTTP_POST, handleNav);
  server.on("/screen", HTTP_GET, handleScreen);
  server.on("/nav", HTTP_OPTIONS, []() {
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.sendHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
    server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
    server.send(204);
  });
  server.on("/debug", HTTP_OPTIONS, []() {
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.sendHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
    server.send(204);
  });
  server.begin();

  if (oledReady) {
    drawWaiting();
  }
  dbgEsp('i', "http ready");
  Serial.println(F("HTTP ready on :80"));
}

void loop() {
  server.handleClient();

  static uint32_t lastCheck = 0;
  static uint8_t lastStations = 255;
  static bool lastHadNav = false;
  uint32_t now = millis();

  // Мигание жёлтого сектора при камере
  bool blinkTick = false;
  if (nav.has_data && nav.camera && (now - lastCamBlinkMs >= CAM_BLINK_MS)) {
    lastCamBlinkMs = now;
    camBlinkOn = !camBlinkOn;
    blinkTick = true;
  } else if (!nav.camera) {
    camBlinkOn = true;
  }

  if (blinkTick) {
    render(true);
  }

  if (now - lastCheck > 400) {
    lastCheck = now;
    uint8_t stations = stationCount();
    const bool stationsChanged = stations != lastStations;
    const bool navFlagChanged = lastHadNav != nav.has_data;
    lastStations = stations;
    lastHadNav = nav.has_data;

    if (stationsChanged) {
      Serial.printf("SoftAP stations=%u\n", stations);
      char stMsg[DBG_MSG_LEN];
      snprintf(stMsg, sizeof(stMsg), "stations=%u", stations);
      dbgEsp('i', stMsg);
    }

    if (stationsChanged || navFlagChanged || (nav.has_data && stations == 0)) {
      render(false);
    }
  }
}
