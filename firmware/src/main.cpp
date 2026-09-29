#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <ArduinoJson.h>
#include "splash_bmp.h"
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
static const uint32_t STALE_MS = 5000;

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
  uint32_t last_update_ms = 0;
  bool has_data = false;
};

NavState nav;

// 32x32 arrow bitmaps (1 bit per pixel, MSB left)
static const uint8_t BMP_LEFT[] PROGMEM = {
  0x00,0x00,0x00,0x00, 0x00,0x18,0x00,0x00, 0x00,0x38,0x00,0x00, 0x00,0x78,0x00,0x00,
  0x00,0xf8,0x00,0x00, 0x01,0xf8,0x00,0x00, 0x03,0xf8,0x00,0x00, 0x07,0xff,0xff,0x00,
  0x0f,0xff,0xff,0x00, 0x1f,0xff,0xff,0x00, 0x3f,0xff,0xff,0x00, 0x7f,0xff,0xff,0x00,
  0x3f,0xff,0xff,0x00, 0x1f,0xff,0xff,0x00, 0x0f,0xff,0xff,0x00, 0x07,0xff,0xff,0x00,
  0x03,0xf8,0x00,0x00, 0x01,0xf8,0x00,0x00, 0x00,0xf8,0x00,0x00, 0x00,0x78,0x00,0x00,
  0x00,0x38,0x00,0x00, 0x00,0x18,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00,
  0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00,
  0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00
};

static const uint8_t BMP_RIGHT[] PROGMEM = {
  0x00,0x00,0x00,0x00, 0x00,0x00,0x18,0x00, 0x00,0x00,0x1c,0x00, 0x00,0x00,0x1e,0x00,
  0x00,0x00,0x1f,0x00, 0x00,0x00,0x1f,0x80, 0x00,0x00,0x1f,0xc0, 0x00,0xff,0xff,0xe0,
  0x00,0xff,0xff,0xf0, 0x00,0xff,0xff,0xf8, 0x00,0xff,0xff,0xfc, 0x00,0xff,0xff,0xfe,
  0x00,0xff,0xff,0xfc, 0x00,0xff,0xff,0xf8, 0x00,0xff,0xff,0xf0, 0x00,0xff,0xff,0xe0,
  0x00,0x00,0x1f,0xc0, 0x00,0x00,0x1f,0x80, 0x00,0x00,0x1f,0x00, 0x00,0x00,0x1e,0x00,
  0x00,0x00,0x1c,0x00, 0x00,0x00,0x18,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00,
  0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00,
  0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00
};

static const uint8_t BMP_STRAIGHT[] PROGMEM = {
  0x00,0x01,0x80,0x00, 0x00,0x03,0xc0,0x00, 0x00,0x07,0xe0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x1f,0xf8,0x00, 0x00,0x3f,0xfc,0x00, 0x00,0x7f,0xfe,0x00, 0x00,0xff,0xff,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x00,0x00,0x00
};

static const uint8_t BMP_UTURN[] PROGMEM = {
  0x00,0x3f,0xfc,0x00, 0x00,0xff,0xff,0x00, 0x01,0xff,0xff,0x80, 0x03,0xf0,0x0f,0xc0,
  0x07,0xc0,0x03,0xe0, 0x07,0x80,0x01,0xe0, 0x0f,0x00,0x00,0xf0, 0x0e,0x00,0x00,0x70,
  0x0e,0x00,0x00,0x70, 0x0e,0x00,0x00,0x70, 0x0e,0x00,0x00,0x70, 0x0e,0x00,0x18,0x70,
  0x0e,0x00,0x3c,0x70, 0x0e,0x00,0x7e,0x70, 0x0e,0x00,0xff,0x70, 0x0e,0x01,0xff,0xf0,
  0x0e,0x00,0xff,0x70, 0x0e,0x00,0x7e,0x70, 0x0e,0x00,0x3c,0x70, 0x0e,0x00,0x18,0x70,
  0x0e,0x00,0x00,0x70, 0x0e,0x00,0x00,0x70, 0x0e,0x00,0x00,0x70, 0x0e,0x00,0x00,0x70,
  0x0e,0x00,0x00,0x70, 0x0e,0x00,0x00,0x70, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00,
  0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00
};

static const uint8_t BMP_ROUND[] PROGMEM = {
  0x00,0x0f,0xf0,0x00, 0x00,0x3f,0xfc,0x00, 0x00,0x7f,0xfe,0x00, 0x00,0xf8,0x1f,0x00,
  0x01,0xe0,0x07,0x80, 0x03,0xc0,0x03,0xc0, 0x03,0x80,0x01,0xc0, 0x07,0x00,0x00,0xe0,
  0x07,0x00,0x00,0xe0, 0x0e,0x00,0x00,0x70, 0x0e,0x01,0x80,0x70, 0x0e,0x03,0xc0,0x70,
  0x0e,0x07,0xe0,0x70, 0x0e,0x0f,0xf0,0x70, 0x0e,0x07,0xe0,0x70, 0x0e,0x03,0xc0,0x70,
  0x0e,0x01,0x80,0x70, 0x0e,0x00,0x00,0x70, 0x07,0x00,0x00,0xe0, 0x07,0x00,0x00,0xe0,
  0x03,0x80,0x01,0xc0, 0x03,0xc0,0x03,0xc0, 0x01,0xe0,0x07,0x80, 0x00,0xf8,0x1f,0x00,
  0x00,0x7f,0xfe,0x00, 0x00,0x3f,0xfc,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x00,0x00,0x00,
  0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00
};

static const uint8_t BMP_ARRIVE[] PROGMEM = {
  0x00,0x01,0x80,0x00, 0x00,0x03,0xc0,0x00, 0x00,0x07,0xe0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x1f,0xf8,0x00, 0x00,0x3f,0xfc,0x00, 0x00,0x7f,0xfe,0x00, 0x00,0xff,0xff,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00,
  0x00,0x0f,0xf0,0x00, 0x00,0x0f,0xf0,0x00, 0x00,0x3f,0xfc,0x00, 0x00,0x3f,0xfc,0x00,
  0x00,0x3f,0xfc,0x00, 0x00,0x3f,0xfc,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00,
  0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00
};

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
    case Turn::SlightLeft:
      return BMP_LEFT;
    case Turn::Right:
    case Turn::SlightRight:
      return BMP_RIGHT;
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

void formatDistance(int meters, char *buf, size_t n) {
  if (meters < 0) {
    snprintf(buf, n, "--");
    return;
  }
  if (meters >= 1000) {
    float km = meters / 1000.0f;
    if (km >= 10.0f) {
      snprintf(buf, n, "%d km", (int)km);
    } else {
      snprintf(buf, n, "%.1f km", km);
    }
  } else {
    snprintf(buf, n, "%d m", meters);
  }
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
}

void drawNav() {
  display.clearDisplay();

  // Top band (yellow physical strip on 0.96" dual-color OLEDs): y 0..15
  char distBuf[16];
  formatDistance(nav.dist_m, distBuf, sizeof(distBuf));
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 4);
  display.print(distBuf);

  if (nav.camera) {
    char camBuf[20];
    if (nav.cam_m >= 0) {
      char cm[12];
      formatDistance(nav.cam_m, cm, sizeof(cm));
      snprintf(camBuf, sizeof(camBuf), "CAM %s", cm);
    } else {
      snprintf(camBuf, sizeof(camBuf), "CAM");
    }
    int16_t x1, y1;
    uint16_t w, h;
    display.getTextBounds(camBuf, 0, 0, &x1, &y1, &w, &h);
    display.setCursor(SCREEN_W - (int)w, 4);
    display.print(camBuf);
  }

  display.drawFastHLine(0, 16, SCREEN_W, SSD1306_WHITE);

  // Arrow + label in blue zone
  display.drawBitmap(48, 20, turnBitmap(nav.turn), 32, 32, SSD1306_WHITE);

  const char *label = turnLabel(nav.turn);
  int16_t x1, y1;
  uint16_t w, h;
  display.getTextBounds(label, 0, 0, &x1, &y1, &w, &h);
  display.setCursor((SCREEN_W - (int)w) / 2, 54);
  display.print(label);

  display.display();
}

void render() {
  if (!oledReady) return;
  uint32_t now = millis();
  const bool phoneOn = stationCount() > 0;

  if (nav.has_data && (now - nav.last_update_ms <= STALE_MS)) {
    drawNav();
    return;
  }

  // Had nav but updates stopped — while phone still on AP, hint reconnect/app
  if (nav.has_data && !phoneOn) {
    drawStale();
    return;
  }
  if (nav.has_data && phoneOn && (now - nav.last_update_ms > STALE_MS)) {
    drawPhoneConnected(stationCount());
    return;
  }

  if (phoneOn) {
    drawPhoneConnected(stationCount());
  } else {
    drawWaiting();
  }
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

  nav.last_update_ms = millis();
  nav.has_data = true;

  Serial.printf("NAV turn=%d dist=%d cam=%d cam_m=%d\n",
                (int)nav.turn, nav.dist_m, nav.camera ? 1 : 0, nav.cam_m);
  char navMsg[DBG_MSG_LEN];
  snprintf(navMsg, sizeof(navMsg), "nav t=%d d=%d cam=%d",
           (int)nav.turn, nav.dist_m, nav.camera ? 1 : 0);
  dbgEsp('i', navMsg);

  render();
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
  static bool lastHadFreshNav = false;
  uint32_t now = millis();

  if (now - lastCheck > 400) {
    lastCheck = now;
    uint8_t stations = stationCount();
    const bool freshNav = nav.has_data && (now - nav.last_update_ms <= STALE_MS);
    const bool stationsChanged = stations != lastStations;
    const bool navBecameStale = lastHadFreshNav && !freshNav;
    lastStations = stations;
    lastHadFreshNav = freshNav;

    if (stationsChanged) {
      Serial.printf("SoftAP stations=%u\n", stations);
      char stMsg[DBG_MSG_LEN];
      snprintf(stMsg, sizeof(stMsg), "stations=%u", stations);
      dbgEsp('i', stMsg);
    }

    // OLED HUD обновляется из POST /nav.
    // Здесь только смена статуса phone/waiting/stale — без лишней перерисовки.
    if (stationsChanged || navBecameStale || !freshNav) {
      render();
    }
  }
}
