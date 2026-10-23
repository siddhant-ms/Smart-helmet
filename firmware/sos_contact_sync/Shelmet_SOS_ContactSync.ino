/*
  SHELMET — Bluetooth Contacts + Rider Name + Physical SOS SMS
  Board: LilyGO T-Call A7670E V1.0
  Scope: Bluetooth SPP, contact sync/persistence, rider name, button-triggered SMS.
  Excludes MPU, accident detection, ride state, speed alerts, and GPS.

  Android app protocol (matches Smart-helmet MainActivity.kt):
    CONTACTS:Mom|+919876543210,Dad|+919876543211\n
    OWNER:Siddhant\n

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

#define MODEM_TX       26
#define MODEM_RX       25
#define MODEM_PWRKEY    4
#define MODEM_RST      27
#define BUTTON_PIN     18

static const int MAX_CONTACTS = 5;
static const unsigned long BUTTON_DEBOUNCE_MS = 50;
static const unsigned long AT_TIMEOUT_MS = 2500;
static const unsigned long SMS_PROMPT_TIMEOUT_MS = 10000;
static const unsigned long SMS_RESULT_TIMEOUT_MS = 60000;
static const unsigned long CONTACT_SEND_GAP_MS = 1500;

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

void handleBluetoothMessage(String data) {
  data.trim();
  if (data.isEmpty()) return;

  Serial.println("[BT RX] " + data);

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
    Serial.println("[SMS] FAILED: Modem/network returned ERROR for " + number);
  } else {
    Serial.println("[SMS] TIMEOUT: No +CMGS confirmation for " + number);
  }
  return false;
}

void triggerEmergencySMS() {
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
  Serial.println("SHELMET — CONTACT SYNC + RIDER + SOS SMS TEST");
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
      triggerEmergencySMS();
    }
  }

  // Optional serial test command. Type TEST_SMS to submit real SMS messages.
  if (Serial.available()) {
    String command = Serial.readStringUntil('\n');
    command.trim();
    if (command == "TEST_SMS") {
      Serial.println("[TEST] Manual SMS batch requested from Serial Monitor.");
      triggerEmergencySMS();
    } else if (command == "SHOW_CONTACTS") {
      printContacts("Current Contacts");
    } else if (command == "MODEM_CHECK") {
      modemReady = waitForAT();
    } else {
      Serial.println("[SERIAL] Commands: TEST_SMS, SHOW_CONTACTS, MODEM_CHECK.");
    }
  }
}
