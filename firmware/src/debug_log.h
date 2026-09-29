#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>

// Ring buffer of compact debug events shared with the Android app.
static const uint8_t DBG_CAP = 32;
static const uint8_t DBG_MSG_LEN = 56;

struct DebugEvent {
  uint32_t t_ms;
  char src;   // 'e' esp, 'p' phone
  char lvl;   // 'i' info, 'w' warn, 'e' error
  char msg[DBG_MSG_LEN];
};

static DebugEvent dbgBuf[DBG_CAP];
static uint8_t dbgHead = 0;
static uint8_t dbgCount = 0;

inline void dbgPush(char src, char lvl, const char *msg) {
  DebugEvent &e = dbgBuf[dbgHead];
  e.t_ms = millis();
  e.src = src;
  e.lvl = lvl;
  strncpy(e.msg, msg ? msg : "", DBG_MSG_LEN - 1);
  e.msg[DBG_MSG_LEN - 1] = '\0';
  dbgHead = (dbgHead + 1) % DBG_CAP;
  if (dbgCount < DBG_CAP) dbgCount++;
  Serial.printf("[%c/%c] %s\n", src, lvl, e.msg);
}

inline void dbgEsp(char lvl, const char *msg) { dbgPush('e', lvl, msg); }

inline void dbgClear() {
  dbgHead = 0;
  dbgCount = 0;
}

inline void dbgAppendToJson(JsonArray arr) {
  if (dbgCount == 0) return;
  uint8_t start = (dbgHead + DBG_CAP - dbgCount) % DBG_CAP;
  for (uint8_t i = 0; i < dbgCount; i++) {
    const DebugEvent &e = dbgBuf[(start + i) % DBG_CAP];
    JsonObject o = arr.add<JsonObject>();
    o["t"] = e.t_ms;
    o["src"] = (e.src == 'p') ? "phone" : "esp";
    char lvl[2] = {e.lvl, 0};
    o["lvl"] = lvl;
    o["msg"] = e.msg;
  }
}
