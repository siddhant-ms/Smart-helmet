/*
  SHELMET — Bluetooth Contacts + Rider Name + Physical SOS SMS
  Board: LilyGO T-Call A7670E V1.0
  Scope: Bluetooth SPP, contact sync/persistence, rider name, phone GPS,
  button-triggered SOS, ride-gated MPU9250 crash detection, and crash SOS countdown.

  Android app protocol:
    CONTACTS:Mom|+919876543210,Dad|+919876543211\n
    OWNER:Siddhant\n
    LOC:12.971600,77.594600\n

  Button wiring for this test:
    GPIO18 -- push button -- GND (INPUT_PULLUP)

  Modem UART:
    LilyGO MODEM_TX GPIO26 -> modem RX
    LilyGO MODEM_RX GPIO25 -> modem TX
    PWRKEY GPIO4, RST GPIO27

  CAUTION: physical SMS messages are real. Test using your own number first.
*/

#include "BluetoothSerial.h"
#include <HardwareSerial.h>
#include <Preferences.h>
#include <Wire.h>
#include <math.h>

#define MODEM_TX       26
#define MODEM_RX       25
#define MODEM_PWRKEY    4
#define MODEM_RST      27
#define BUTTON_PIN     18
#define SDA_PIN        21
#define SCL_PIN        22

#define WHO_AM_I       0x75
#define PWR_MGMT_1     0x6B
#define CONFIG         0x1A
#define GYRO_CONFIG    0x1B
#define ACCEL_CONFIG   0x1C
#define ACCEL_CONFIG2  0x1D
#define ACCEL_XOUT_H   0x3B

const float IMPACT_THRESHOLD = 2.8f;
const float STRONG_IMPACT = 5.0f;
const float GYRO_THRESHOLD = 320.0f;
const float STRONG_GYRO = 500.0f;
const float TILT_THRESHOLD = 50.0f;
const unsigned long CONFIRM_TIME = 300UL;
const unsigned long COOLDOWN_TIME = 5000UL;
const unsigned long CRASH_COUNTDOWN_MS = 10000UL;

static const int MAX_CONTACTS = 5;
static const unsigned long BUTTON_DEBOUNCE_MS = 50;
static const unsigned long AT_TIMEOUT_MS = 2500;
static const unsigned long SMS_PROMPT_TIMEOUT_MS = 10000;
static const unsigned long SMS_RESULT_TIMEOUT_MS = 60000;
static const unsigned long CONTACT_SEND_GAP_MS = 1500;
static const unsigned long LOCATION_MAX_AGE_MS = 15000;

BluetoothSerial SerialBT;
HardwareSerial modem(1);
Preferences prefs;

struct Contact {
  String name;
  String number;
};

Contact contacts[MAX_CONTACTS];
int contactCount = 0;
String riderName = "Unknown Rider";
String btBuffer;
String latestPhoneLocation;
unsigned long lastLocationReceiveTime = 0;
bool locationStreamingActive = false;

bool rideActive = false;
bool imuReady = false;
byte MPU_ADDR = 0x68;
float ax = 0, ay = 0, az = 0, gx = 0, gy = 0, gz = 0;
float accelerationMagnitude = 0, gyroMagnitude = 0;
float roll = 0, pitch = 0, referenceRoll = 0, referencePitch = 0, tiltChange = 0;
float peakAcceleration = 0, peakGyro = 0, peakTilt = 0;
unsigned long crashCandidateStart = 0, lastCrashTime = 0;
bool crashCandidateActive = false;
bool crashCountdownActive = false;
unsigned long crashCountdownStarted = 0;
int lastCountdownSecond = -1;
int crashCancelPressCount = 0;

void triggerEmergencySMS(bool fromCrash = false);

bool modemReady = false;
bool smsInProgress = false;

bool lastRawButton = HIGH;
bool stableButton = HIGH;
unsigned long lastButtonChange = 0;

String readModemFor(unsigned long durationMs) {
  String response;
  unsigned long started = millis();
  while (millis() - started < durationMs) {
    while (modem.available()) {
      char c = static_cast<char>(modem.read());
      response += c;
    }
    delay(2);
  }
  return response;
}

String readModemUntilToken(const String &token1, const String &token2,
                           unsigned long timeoutMs) {
  String response;
  unsigned long started = millis();

  while (millis() - started < timeoutMs) {
    while (modem.available()) {
      char c = static_cast<char>(modem.read());
      response += c;

      if ((!token1.isEmpty() && response.indexOf(token1) >= 0) ||
          (!token2.isEmpty() && response.indexOf(token2) >= 0)) {
        return response;
      }

      // Prevent an unlimited buffer if the modem emits unexpected data.
      if (response.length() > 2500) {
        response.remove(0, 1000);
      }
    }
    delay(2);
  }
  return response;
}

String sendAT(const String &command, unsigned long timeoutMs = AT_TIMEOUT_MS) {
  while (modem.available()) modem.read();
  modem.print(command);
  modem.print("\r");
  return readModemFor(timeoutMs);
}

bool waitForAT() {
  Serial.println("[MODEM] Checking AT response...");
  for (int i = 0; i < 20; ++i) {
    String r = sendAT("AT", 700);
    if (r.indexOf("OK") >= 0) {
      Serial.println("[MODEM] AT communication OK.");
      return true;
    }
    delay(300);
  }
  Serial.println("[MODEM] ERROR: No AT response. Check power, wiring, baud rate.");
  return false;
}

bool waitForNetwork(unsigned long timeoutMs = 30000) {
  Serial.println("[NETWORK] Checking SIM registration...");
  unsigned long started = millis();

  while (millis() - started < timeoutMs) {
    String r = sendAT("AT+CREG?", 1200);
    // +CREG: <n>,1 means home network; <n>,5 means roaming.
    if (r.indexOf("+CREG:") >= 0 &&
        (r.indexOf(",1") >= 0 || r.indexOf(",5") >= 0)) {
      Serial.println("[NETWORK] Registered.");
      return true;
    }

    String csq = sendAT("AT+CSQ", 1000);
    if (csq.indexOf("+CSQ: 99,99") >= 0) {
      Serial.println("[NETWORK] Signal unavailable (CSQ 99,99).");
    }
    delay(1000);
  }

  Serial.println("[NETWORK] ERROR: Not registered before timeout.");
  return false;
}

String normalizeNumber(String number) {
  number.trim();
  number.replace(" ", "");
  number.replace("-", "");
  number.replace("(", "");
  number.replace(")", "");
  return number;
}

bool validPhoneNumber(const String &number) {
  if (number.length() < 8 || number.length() > 16) return false;
  int digits = 0;
  for (size_t i = 0; i < number.length(); ++i) {
    char c = number[i];
    if (c >= '0' && c <= '9') digits++;
    else if (c == '+' && i == 0) {}
    else return false;
  }
  return digits >= 7;
}

void printContacts(const char *heading) {
  Serial.println();
  Serial.println("========== " + String(heading) + " ==========");
  Serial.println("[RIDER] " + riderName);
  Serial.println("[CONTACTS] Count: " + String(contactCount) + "/" + String(MAX_CONTACTS));
  if (contactCount == 0) {
    Serial.println("[CONTACTS] No emergency contacts saved.");
  } else {
    for (int i = 0; i < contactCount; ++i) {
      Serial.printf("%d. %s - %s\n", i + 1,
                    contacts[i].name.c_str(), contacts[i].number.c_str());
    }
  }
  Serial.println("======================================");
}

void loadSavedData() {
  if (!prefs.begin("helmet", true)) {
    Serial.println("[FLASH] ERROR: Could not open Preferences for reading.");
    return;
  }

  riderName = prefs.getString("rider_name", "Unknown Rider");
  riderName.trim();
  if (riderName.isEmpty()) riderName = "Unknown Rider";

  int savedCount = prefs.getInt("count", 0);
  if (savedCount < 0 || savedCount > MAX_CONTACTS) {
    Serial.println("[FLASH] WARNING: Invalid saved contact count; ignoring saved list.");
    savedCount = 0;
  }

  Contact loaded[MAX_CONTACTS];
  int loadedCount = 0;
  bool allValid = true;

  for (int i = 0; i < savedCount; ++i) {
    String n = prefs.getString(("name_" + String(i)).c_str(), "");
    String p = prefs.getString(("num_" + String(i)).c_str(), "");
    n.trim();
    p = normalizeNumber(p);
    if (n.isEmpty() || !validPhoneNumber(p)) {
      allValid = false;
      break;
    }
    loaded[loadedCount++] = {n, p};
  }
  prefs.end();

  if (!allValid) {
    Serial.println("[FLASH] WARNING: Invalid stored contacts; contact list not loaded.");
    contactCount = 0;
    printContacts("Stored Contacts");
    return;
  }

  contactCount = loadedCount;
  for (int i = 0; i < contactCount; ++i) contacts[i] = loaded[i];
  printContacts("Loaded Stored Contacts");
}

bool saveContactListAtomically(Contact *newContacts, int newCount) {
  Preferences writePrefs;
  if (!writePrefs.begin("helmet", false)) {
    Serial.println("[FLASH] ERROR: Cannot open storage for writing.");
    return false;
  }

  bool ok = true;
  // Write the new entries first. Count is committed last as the list marker.
  for (int i = 0; i < MAX_CONTACTS; ++i) {
    String nameKey = "name_" + String(i);
    String numKey = "num_" + String(i);
    if (i < newCount) {
      if (writePrefs.putString(nameKey.c_str(), newContacts[i].name) == 0) ok = false;
      if (writePrefs.putString(numKey.c_str(), newContacts[i].number) == 0) ok = false;
    } else {
      writePrefs.remove(nameKey.c_str());
      writePrefs.remove(numKey.c_str());
    }
  }

  if (ok && writePrefs.putInt("count", newCount) == 0) ok = false;
  writePrefs.end();

  if (!ok) {
    Serial.println("[FLASH] ERROR: Write verification failed; do not assume sync persisted.");
  }
  return ok;
}

bool saveRiderName(const String &name) {
  Preferences p;
  if (!p.begin("helmet", false)) {
    Serial.println("[FLASH] ERROR: Cannot open storage for rider name.");
    return false;
  }
  size_t written = p.putString("rider_name", name);
  p.end();
  if (written == 0) {
    Serial.println("[FLASH] ERROR: Rider name write failed.");
    return false;
  }
  return true;
}

bool parseAndSaveContacts(const String &rawList) {
  Contact candidate[MAX_CONTACTS];
  int candidateCount = 0;
  int start = 0;
  String source = rawList;
  source.trim();

  // Empty CONTACTS: intentionally clears the contact list.
  if (source.isEmpty()) {
    if (!saveContactListAtomically(candidate, 0)) {
      Serial.println("[CONTACTS] ERROR: Empty list not saved; old RAM list retained.");
      return false;
    }
    contactCount = 0;
    printContacts("Contacts Cleared");
    return true;
  }

  while (start < source.length()) {
    if (candidateCount >= MAX_CONTACTS) {
      Serial.println("[CONTACTS] ERROR: More than 5 contacts. Sync rejected.");
      return false;
    }

    int comma = source.indexOf(',', start);
    if (comma < 0) comma = source.length();

    String entry = source.substring(start, comma);
    entry.trim();
    int pipe = entry.indexOf('|');

    if (pipe <= 0 || pipe >= (int)entry.length() - 1) {
      Serial.println("[CONTACTS] ERROR: Malformed entry: " + entry + ". Entire sync rejected.");
      return false;
    }

    String name = entry.substring(0, pipe);
    String number = normalizeNumber(entry.substring(pipe + 1));
    name.trim();

    if (name.isEmpty() || name.length() > 50 || !validPhoneNumber(number)) {
      Serial.println("[CONTACTS] ERROR: Invalid name or phone number in: " + entry);
      return false;
    }

    for (int i = 0; i < candidateCount; ++i) {
      if (candidate[i].number == number) {
        Serial.println("[CONTACTS] ERROR: Duplicate number " + number + ". Sync rejected.");
        return false;
      }
    }

    candidate[candidateCount++] = {name, number};
    start = comma + 1;
  }

  if (!saveContactListAtomically(candidate, candidateCount)) {
    Serial.println("[CONTACTS] ERROR: Flash save failed. Existing in-memory list retained.");
    return false;
  }

  contactCount = candidateCount;
  for (int i = 0; i < contactCount; ++i) contacts[i] = candidate[i];
  printContacts("Contacts Synced Successfully");
  Serial.println("[CONTACTS] SUCCESS: Validated and saved to flash.");
  return true;
}

bool validRiderName(String name) {
  name.trim();
  if (name.isEmpty() || name.length() > 60) return false;
  for (size_t i = 0; i < name.length(); ++i) {
    if (name[i] == '\r' || name[i] == '\n') return false;
  }
  return true;
}


void writeRegister(byte reg, byte value) {
  Wire.beginTransmission(MPU_ADDR); Wire.write(reg); Wire.write(value); Wire.endTransmission();
}
byte readRegister(byte reg) {
  Wire.beginTransmission(MPU_ADDR); Wire.write(reg); Wire.endTransmission(false);
  Wire.requestFrom(MPU_ADDR, (uint8_t)1);
  return Wire.available() ? Wire.read() : 0;
}
void readRegisters(byte startReg, byte *buf, int len) {
  for (int i = 0; i < len; ++i) buf[i] = 0;
  Wire.beginTransmission(MPU_ADDR); Wire.write(startReg);
  if (Wire.endTransmission(false) != 0) return;
  Wire.requestFrom(MPU_ADDR, (uint8_t)len);
  int i = 0; while (Wire.available() && i < len) buf[i++] = Wire.read();
}
bool detectMPUAddress() {
  byte addrs[2] = {0x68, 0x69};
  for (int i = 0; i < 2; ++i) {
    Wire.beginTransmission(addrs[i]);
    if (Wire.endTransmission() == 0) {
      MPU_ADDR = addrs[i];
      Serial.printf("[IMU] Found at 0x%02X\n", MPU_ADDR);
      return true;
    }
  }
  return false;
}
bool initializeMPU() {
  if (!detectMPUAddress()) return false;
  byte whoAmI = readRegister(WHO_AM_I);
  Serial.printf("[IMU] WHO_AM_I: 0x%02X\n", whoAmI);
  if (whoAmI == 0x00 || whoAmI == 0xFF) return false;
  writeRegister(PWR_MGMT_1, 0x00); delay(100);
  writeRegister(CONFIG, 0x03);
  writeRegister(GYRO_CONFIG, 0x18);
  writeRegister(ACCEL_CONFIG, 0x18);
  writeRegister(ACCEL_CONFIG2, 0x03);
  delay(100);
  return true;
}
void readIMU() {
  byte data[14] = {0};
  readRegisters(ACCEL_XOUT_H, data, 14);
  int16_t rawAX = ((int16_t)data[0] << 8) | data[1];
  int16_t rawAY = ((int16_t)data[2] << 8) | data[3];
  int16_t rawAZ = ((int16_t)data[4] << 8) | data[5];
  int16_t rawGX = ((int16_t)data[8] << 8) | data[9];
  int16_t rawGY = ((int16_t)data[10] << 8) | data[11];
  int16_t rawGZ = ((int16_t)data[12] << 8) | data[13];
  ax = rawAX / 2048.0f; ay = rawAY / 2048.0f; az = rawAZ / 2048.0f;
  gx = rawGX / 16.4f; gy = rawGY / 16.4f; gz = rawGZ / 16.4f;
  accelerationMagnitude = sqrtf(ax*ax + ay*ay + az*az);
  gyroMagnitude = sqrtf(gx*gx + gy*gy + gz*gz);
  roll = atan2f(ay, az) * 180.0f / PI;
  pitch = atan2f(-ax, sqrtf(ay*ay + az*az)) * 180.0f / PI;
}
void resetCrashState() {
  crashCandidateActive = false;
  peakAcceleration = peakGyro = peakTilt = 0.0f;
  crashCandidateStart = 0;
}
void calibrateIMUReference() {
  Serial.println("[IMU] Keep helmet still; calibrating reference for 1 second...");
  float rSum = 0, pSum = 0;
  for (int i = 0; i < 200; ++i) {
    readIMU(); rSum += roll; pSum += pitch; delay(5);
  }
  referenceRoll = rSum / 200.0f; referencePitch = pSum / 200.0f;
  Serial.printf("[IMU] Calibration complete. Roll %.2f | Pitch %.2f\n", referenceRoll, referencePitch);
}
void startRide() {
  if (rideActive) return;
  Serial.println("[RIDE] Start received. Initializing crash detection...");
  resetCrashState();
  imuReady = initializeMPU();
  if (!imuReady) {
    Serial.println("[IMU] ERROR: MPU not detected. Ride active, crash detection unavailable.");
    rideActive = true;
    return;
  }
  calibrateIMUReference();
  rideActive = true;
  Serial.println("[RIDE] Started. Crash detection ACTIVE.");
  Serial.println("[IMU] imuReady = YES");
}
void stopRide() {
  if (!rideActive) return;
  rideActive = false;
  resetCrashState();
  Serial.println("[RIDE] Stopped. Crash detection INACTIVE.");
}
void beginCrashCountdown() {
  if (crashCountdownActive) return;
  crashCountdownActive = true;
  crashCountdownStarted = millis();
  lastCountdownSecond = -1;
  crashCancelPressCount = 0;
  Serial.println();
  Serial.println("============================================================");
  Serial.println("                 !!! CRASH DETECTED !!!");
  Serial.println("============================================================");
  Serial.printf("[CRASH] Peak accel: %.2f g | Peak gyro: %.2f deg/s | Peak tilt: %.2f deg\n",
                peakAcceleration, peakGyro, peakTilt);
  Serial.println("[BUFFER] Emergency SMS in 10 seconds.");
  Serial.println("[BUFFER] Press the physical button TWICE before countdown ends to cancel.");
}
void updateCrashCountdown() {
  if (!crashCountdownActive) return;
  unsigned long elapsed = millis() - crashCountdownStarted;
  int secondsLeft = 10 - (int)(elapsed / 1000UL);
  if (secondsLeft > 0 && secondsLeft != lastCountdownSecond) {
    lastCountdownSecond = secondsLeft;
    Serial.printf("[BUFFER] %d...\n", secondsLeft);
  }
  if (elapsed >= CRASH_COUNTDOWN_MS) {
    crashCountdownActive = false;
    Serial.println("[BUFFER] Time expired. Sending emergency SMS to saved contacts.");
    triggerEmergencySMS(true);
    resetCrashState();
  }
}
void handleCrashButtonPress() {
  if (crashCountdownActive) {
    ++crashCancelPressCount;
    Serial.printf("[BUFFER] Button press %d/2 registered.\n", crashCancelPressCount);
    if (crashCancelPressCount >= 2) {
      crashCountdownActive = false;
      Serial.println("[BUFFER] CANCELLED by rider. No crash SMS sent.");
      resetCrashState();
    }
    return;
  }
  triggerEmergencySMS(false);
}
void updateCrashDetection() {
  if (!rideActive || !imuReady || crashCountdownActive) return;
  readIMU();
  float dRoll = fabsf(roll - referenceRoll);
  float dPitch = fabsf(pitch - referencePitch);
  tiltChange = max(dRoll, dPitch);
  bool impactDetected = accelerationMagnitude >= IMPACT_THRESHOLD;
  bool rotationDetect = gyroMagnitude >= GYRO_THRESHOLD;
  bool strongImpact = accelerationMagnitude >= STRONG_IMPACT;
  bool strongRotation = gyroMagnitude >= STRONG_GYRO;
  bool tiltDetected = tiltChange >= TILT_THRESHOLD;
  bool candidate = impactDetected || (rotationDetect && tiltDetected) || (strongImpact && strongRotation);
  unsigned long now = millis();
  if (candidate && !crashCandidateActive &&
      (lastCrashTime == 0 || now - lastCrashTime >= COOLDOWN_TIME)) {
    crashCandidateActive = true;
    crashCandidateStart = now;
    peakAcceleration = accelerationMagnitude;
    peakGyro = gyroMagnitude;
    peakTilt = tiltChange;
    return;
  }
  if (!crashCandidateActive) return;
  if (accelerationMagnitude > peakAcceleration) peakAcceleration = accelerationMagnitude;
  if (gyroMagnitude > peakGyro) peakGyro = gyroMagnitude;
  if (tiltChange > peakTilt) peakTilt = tiltChange;
  if (!candidate && now - crashCandidateStart < CONFIRM_TIME) {
    crashCandidateActive = false;
    peakAcceleration = peakGyro = peakTilt = 0.0f;
    return;
  }
  if (now - crashCandidateStart >= CONFIRM_TIME) {
    crashCandidateActive = false;
    bool confirmed = peakAcceleration >= IMPACT_THRESHOLD ||
      (peakGyro >= GYRO_THRESHOLD && peakTilt >= TILT_THRESHOLD) ||
      (peakAcceleration >= STRONG_IMPACT && peakGyro >= STRONG_GYRO);
    if (confirmed) {
      lastCrashTime = now;
      beginCrashCountdown();
    } else {
      peakAcceleration = peakGyro = peakTilt = 0.0f;
    }
  }
}


void handleBluetoothMessage(String data) {
  data.trim();
  if (data.isEmpty()) return;

  // Keep GPS coordinates out of Serial Monitor; log only GPS stream start/stop transitions.
  if (!data.startsWith("LOC:")) {
    Serial.println("[BT RX] " + data);
  }

  if (data == "RIDE_START") {
    startRide();
    return;
  }
  if (data == "RIDE_STOP") {
    stopRide();
    return;
  }

  if (data.startsWith("CONTACTS:")) {
    parseAndSaveContacts(data.substring(String("CONTACTS:").length()));
    return;
  }

  if (data.startsWith("OWNER:") || data.startsWith("RIDER:")) {
    int separator = data.indexOf(':');
    String name = data.substring(separator + 1);
    name.trim();

    if (!validRiderName(name)) {
      Serial.println("[RIDER] ERROR: Empty or invalid rider name. Existing name retained.");
      return;
    }

    if (!saveRiderName(name)) {
      Serial.println("[RIDER] ERROR: Name not confirmed in flash.");
      return;
    }

    riderName = name;
    Serial.println("[RIDER] SUCCESS: " + riderName);
    return;
  }

  if (data.startsWith("LOC:")) {
    String coordinates = data.substring(4);
    coordinates.trim();

    int comma = coordinates.indexOf(',');
    if (comma <= 0 || comma >= (int)coordinates.length() - 1) {
      Serial.println("[GPS] Ignored malformed LOC message.");
      return;
    }

    String latText = coordinates.substring(0, comma);
    String lonText = coordinates.substring(comma + 1);
    latText.trim();
    lonText.trim();

    char *latEnd = nullptr;
    char *lonEnd = nullptr;
    double lat = strtod(latText.c_str(), &latEnd);
    double lon = strtod(lonText.c_str(), &lonEnd);

    if (latEnd == latText.c_str() || *latEnd != '\0' ||
        lonEnd == lonText.c_str() || *lonEnd != '\0' ||
        !isfinite(lat) || !isfinite(lon) ||
        lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
      Serial.println("[GPS] Ignored invalid coordinates.");
      return;
    }

    bool streamWasInactive = !locationStreamingActive;
    latestPhoneLocation = String(lat, 6) + "," + String(lon, 6);
    lastLocationReceiveTime = millis();
    locationStreamingActive = true;

    if (streamWasInactive) {
      Serial.println("[GPS] Location streaming started.");
    }
    // The app streams LOC only during a ride; this also supports apps that
    // do not yet send explicit RIDE_START/RIDE_STOP commands.
    if (!rideActive) startRide();
    return;
  }

  if (data == "PING") {
    Serial.println("[BT] PING received; Bluetooth link is working.");
    return;
  }

  Serial.println("[BT] ERROR: Unknown command ignored.");
}

bool waitForModemPrompt() {
  String response = readModemUntilToken(">", "ERROR", SMS_PROMPT_TIMEOUT_MS);
  return response.indexOf('>') >= 0 && response.indexOf("ERROR") < 0;
}

bool sendSingleSMS(const String &rawNumber, const String &message) {
  String number = normalizeNumber(rawNumber);

  if (!validPhoneNumber(number)) {
    Serial.println("[SMS] FAILED: Invalid number: " + number);
    return false;
  }

  if (!modemReady) {
    Serial.println("[SMS] FAILED for " + number + ": modem is not ready.");
    return false;
  }

  Serial.println();
  Serial.println("[SMS] Submitting to " + number);

  String cmgf = sendAT("AT+CMGF=1", 1500);
  if (cmgf.indexOf("OK") < 0 || cmgf.indexOf("ERROR") >= 0) {
    Serial.println("[SMS] FAILED: Could not set text mode.");
    return false;
  }

  while (modem.available()) modem.read();
  modem.print("AT+CMGS=\"");
  modem.print(number);
  modem.print("\"\r");

  if (!waitForModemPrompt()) {
    Serial.println("[SMS] FAILED: Modem did not return SMS prompt (>).");
    // Try to exit any unexpected input state safely.
    modem.write(27);
    readModemFor(500);
    return false;
  }

  modem.print(message);
  modem.write(26); // Ctrl+Z submits the SMS.

  String response = readModemUntilToken("+CMGS:", "ERROR", SMS_RESULT_TIMEOUT_MS);

  if (response.indexOf("+CMGS:") >= 0 && response.indexOf("ERROR") < 0) {
    Serial.println("[SMS] SUBMITTED successfully to " + number +
                   " (network accepted submission; delivery not guaranteed).");
    return true;
  }

  if (response.indexOf("ERROR") >= 0) {
    response.trim();
    Serial.println("[SMS] FAILED for " + number);
    Serial.println("[SMS] Modem error: " + response);
  } else {
    Serial.println("[SMS] TIMEOUT: No +CMGS confirmation for " + number);
  }
  return false;
}

void triggerEmergencySMS(bool fromCrash) {
  // Manual SOS requires an active ride. A confirmed crash may still send after
  // its countdown if GPS streaming stopped during the 10-second buffer.
  if (!rideActive && !fromCrash) {
    Serial.println("[SOS] Ride hasn't started yet. No message sent.");
    return;
  }

  if (smsInProgress) {
    Serial.println("[SOS] Ignored: an SMS batch is already running.");
    return;
  }

  smsInProgress = true;
  Serial.println();
  Serial.println("==========================================");
  Serial.println("[SOS] PHYSICAL BUTTON PRESSED");
  Serial.println("[SOS] Rider: " + riderName);

  String message = "EMERGENCY ALERT: " + riderName +
                   " is in an emergency and may need immediate assistance.";

  bool locationIsFresh =
      !latestPhoneLocation.isEmpty() &&
      lastLocationReceiveTime > 0 &&
      millis() - lastLocationReceiveTime <= LOCATION_MAX_AGE_MS;

  if (locationIsFresh) {
    message += " Location: https://maps.google.com/?q=" + latestPhoneLocation;
    Serial.println("[GPS] Adding current location link to SMS.");
  } else {
    message += " Location unavailable (no recent phone GPS fix).";
    Serial.println("[GPS] No recent location; SMS will state unavailable.");
  }

  if (contactCount == 0) {
    Serial.println("[SOS] ERROR: No contacts saved. No SMS sent.");
    Serial.println("[SOS] Sync contacts from the Shelmet app first.");
    Serial.println("==========================================");
    smsInProgress = false;
    return;
  }

  Serial.println("[SOS] Sending to " + String(contactCount) + " saved contact(s).");

  if (!modemReady) {
    Serial.println("[MODEM] Retesting AT communication...");
    modemReady = waitForAT();
  }
  if (!modemReady) {
    Serial.println("[SOS] ABORTED: modem not responding.");
    smsInProgress = false;
    return;
  }

  // Compatibility test: use the earlier firmware's approach and attempt SMS
  // directly instead of blocking on CREG registration status first.
  Serial.println("[SOS] Attempting SMS directly (network pre-check skipped).");

  int submitted = 0;
  int failed = 0;
  for (int i = 0; i < contactCount; ++i) {
    Serial.println("[SOS] Contact " + String(i + 1) + "/" +
                   String(contactCount) + ": " + contacts[i].name);
    if (sendSingleSMS(contacts[i].number, message)) submitted++;
    else failed++;
    if (i + 1 < contactCount) delay(CONTACT_SEND_GAP_MS);
  }

  Serial.println("[SOS] Result: " + String(submitted) + " submitted, " +
                 String(failed) + " failed.");
  Serial.println("==========================================");
  smsInProgress = false;
}

void setup() {
  Serial.begin(115200);
  delay(1200);

  Serial.println();
  Serial.println("==============================================");
  Serial.println("SHELMET — CONTACTS + GPS + CRASH DETECTION + SOS");
  Serial.println("==============================================");

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  lastRawButton = digitalRead(BUTTON_PIN);
  stableButton = lastRawButton;

  loadSavedData();

  if (!SerialBT.begin("SmartHelmet")) {
    Serial.println("[BT] ERROR: Bluetooth SPP could not start.");
  } else {
    Serial.println("[BT] SPP started. Pair/connect to: SmartHelmet");
    Serial.println("[BT] Expected messages: CONTACTS:... and OWNER:...");
  }

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000);
  Serial.println("[IMU] I2C ready on SDA=21, SCL=22. Sensor initialization waits for ride start.");

  pinMode(MODEM_PWRKEY, OUTPUT);
  digitalWrite(MODEM_PWRKEY, LOW);
  pinMode(MODEM_RST, OUTPUT);
  digitalWrite(MODEM_RST, HIGH);

  modem.begin(115200, SERIAL_8N1, MODEM_RX, MODEM_TX);
  Serial.println("[MODEM] UART started at 115200; TX=GPIO26 RX=GPIO25.");

  // Preserve the existing A7670E PWRKEY pulse approach from your firmware.
  digitalWrite(MODEM_PWRKEY, LOW);
  delay(100);
  digitalWrite(MODEM_PWRKEY, HIGH);
  delay(1200);
  digitalWrite(MODEM_PWRKEY, LOW);

  Serial.println("[MODEM] Waiting for modem boot...");
  delay(8000);
  modemReady = waitForAT();
  if (modemReady) {
    // Configure verbose modem errors and SMS text mode.
    sendAT("AT+CMEE=2", 1500);
    String smsMode = sendAT("AT+CMGF=1", 1500);
    if (smsMode.indexOf("OK") < 0) {
      Serial.println("[MODEM] WARNING: Could not configure SMS text mode.");
    }
    Serial.println("[MODEM] AT communication ready. Network registration is checked at SOS.");
  } else {
    Serial.println("[MODEM] WARNING: Not ready. SOS cannot send until AT responds.");
  }

  Serial.println();
  Serial.println("[BUTTON] GPIO18 -> button -> GND.");
  Serial.println("[BUTTON] Press to send a REAL SMS to all saved contacts.");
  Serial.println("[SERIAL TEST] Type TEST_SMS and press Enter to test without the physical button.");
  Serial.println("[SYSTEM] Ready.");
}

void loop() {
  // Read incoming Bluetooth SPP data, one newline-delimited command at a time.
  while (SerialBT.available()) {
    char c = static_cast<char>(SerialBT.read());
    if (c == '\n') {
      btBuffer.trim();
      if (!btBuffer.isEmpty()) handleBluetoothMessage(btBuffer);
      btBuffer = "";
    } else if (c != '\r') {
      if (btBuffer.length() < 512) {
        btBuffer += c;
      } else {
        Serial.println("[BT] ERROR: Message too long; dropping partial command.");
        btBuffer = "";
      }
    }
  }

  // Announce GPS stream transitions only; suppress per-coordinate logging.
  if (locationStreamingActive &&
      lastLocationReceiveTime > 0 &&
      millis() - lastLocationReceiveTime > LOCATION_MAX_AGE_MS) {
    locationStreamingActive = false;
    latestPhoneLocation = "";
    lastLocationReceiveTime = 0;
    Serial.println("[GPS] Location streaming stopped.");
    stopRide();
  }

  // Debounced physical button: trigger only on HIGH -> LOW stable transition.
  bool raw = digitalRead(BUTTON_PIN);
  if (raw != lastRawButton) {
    lastRawButton = raw;
    lastButtonChange = millis();
  }

  if (millis() - lastButtonChange >= BUTTON_DEBOUNCE_MS &&
      raw != stableButton) {
    stableButton = raw;
    if (stableButton == LOW) {
      handleCrashButtonPress();
    }
  }

  // Optional serial test command. Type TEST_SMS to submit real SMS messages.
  if (Serial.available()) {
    String command = Serial.readStringUntil('\n');
    command.trim();
    if (command == "TEST_SMS") {
      Serial.println("[TEST] Manual SMS batch requested from Serial Monitor.");
      triggerEmergencySMS(false);
    } else if (command == "SHOW_CONTACTS") {
      printContacts("Current Contacts");
    } else if (command == "MODEM_CHECK") {
      modemReady = waitForAT();
    } else if (command == "RIDE_START") {
      startRide();
    } else if (command == "RIDE_STOP") {
      stopRide();
    } else {
      Serial.println("[SERIAL] Commands: TEST_SMS, SHOW_CONTACTS, MODEM_CHECK, RIDE_START, RIDE_STOP.");
    }
  }

  updateCrashCountdown();
  updateCrashDetection();
}