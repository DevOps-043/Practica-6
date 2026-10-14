/*
  IPN - Internet de las Cosas - Secuencia 7NV72
  Practica 4: Sistema de Telemetria Critica de Temperatura
  ESP32 + DHT11 KY-015 + OLED SSD1306 I2C + DIP SW1 + 8 LED
  SIN 74HC595

  UDP: LOW en GPIO13 -> datagramas cada 50 ms a la PC (puerto 4210).
  TCP: HIGH en GPIO13 -> servidor ESP32 puerto 8080.
       GET_TEMP -> ESP32 responde TEMP=...;HUM=...;ID=...
       ALERTA_ON -> estrobo remoto; ALERTA_OFF -> vuelve a barra.

  IMPORTANTE: DHT11 se lee cada 2000 ms. UDP transmite la ultima
  muestra valida cada 50 ms (no una medicion nueva cada 50 ms).
*/

#include <WiFi.h>
#include <WiFiUdp.h>
#include <Wire.h>
#include <DHT.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <math.h>

// =================== MODIFICAR ESTOS DATOS ===================
const char* WIFI_SSID = "NOMBRE_DE_TU_WIFI";
const char* WIFI_PASSWORD = "CONTRASENA_DE_TU_WIFI";

// IPv4 LOCAL de la computadora con la aplicacion Java.
// Ejemplo: en Windows, ejecutar ipconfig y buscar "Direccion IPv4".
IPAddress PC_IP(192, 168, 1, 100);
// =============================================================

const uint16_t UDP_PORT_PC = 4210;
const uint16_t UDP_PORT_ESP32 = 4211;
const uint16_t TCP_PORT = 8080;

const int DHT_PIN = 4;
const int SWITCH_PIN = 13;
const uint8_t LED_PINS[8] = {25, 26, 27, 14, 18, 33, 32, 19};
const int LED_COUNT = 8;

// OLED SSD1306 128x64, I2C: SDA 21, SCL 22
const int OLED_SDA = 21;
const int OLED_SCL = 22;
const uint8_t OLED_ADDRESS = 0x3C;
Adafruit_SSD1306 display(128, 64, &Wire, -1);
bool oledOk = false;

DHT dht(DHT_PIN, DHT11);
WiFiUDP udp;
WiFiServer tcpServer(TCP_PORT);

float temperatura = NAN;
float humedad = NAN;
unsigned long sampleId = 0;
unsigned long ultimaLectura = 0;
unsigned long ultimoEnvioUDP = 0;
unsigned long ultimoOLED = 0;
unsigned long ultimoParpadeo = 0;
unsigned long ultimaRevisionWifi = 0;

bool modoTCP = false;
bool modoAnteriorTCP = false;
bool alertaRemota = false;
bool estadoEstrobo = false;

const unsigned long DHT_INTERVALO_MS = 2000; // DHT11: no leer cada 50ms
const unsigned long UDP_INTERVALO_MS = 50;
const unsigned long OLED_INTERVALO_MS = 250;
const unsigned long ESTROBO_INTERVALO_MS = 200;

// Con el ejemplo de la practica: 20 C = 1 LED; 40 C = 8 LED.
int segmentosPorTemperatura(float t) {
  if (isnan(t)) return 0;
  if (t <= 20.0f) return 1;
  if (t >= 40.0f) return LED_COUNT;
  int n = 1 + (int)((t - 20.0f) * (LED_COUNT - 1) / 20.0f);
  return constrain(n, 1, LED_COUNT);
}

void actualizarLeds(unsigned long ahora) {
  if (modoTCP && alertaRemota) {
    if (ahora - ultimoParpadeo >= ESTROBO_INTERVALO_MS) {
      ultimoParpadeo = ahora;
      estadoEstrobo = !estadoEstrobo;
    }
    for (int i = 0; i < LED_COUNT; i++) {
      digitalWrite(LED_PINS[i], estadoEstrobo ? HIGH : LOW);
    }
    return;
  }

  int encendidos = segmentosPorTemperatura(temperatura);
  for (int i = 0; i < LED_COUNT; i++) {
    digitalWrite(LED_PINS[i], i < encendidos ? HIGH : LOW);
  }
}

String crearPaquete() {
  String t = isnan(temperatura) ? "nan" : String(temperatura, 1);
  String h = isnan(humedad) ? "nan" : String(humedad, 1);
  return "TEMP=" + t + ";HUM=" + h + ";ID=" + String(sampleId);
}

void leerDHT(unsigned long ahora) {
  if (ahora - ultimaLectura < DHT_INTERVALO_MS) return;
  ultimaLectura = ahora;

  float h = dht.readHumidity();
  float t = dht.readTemperature();
  if (!isnan(h) && !isnan(t)) {
    humedad = h;
    temperatura = t;
    sampleId = ahora;  // identificador para detectar cambios reales
    Serial.print("Muestra DHT11: ");
    Serial.println(crearPaquete());
  } else {
    Serial.println("Aviso: DHT11 no pudo leerse; conservo ultima muestra valida");
  }
}

void dibujarOLED(unsigned long ahora) {
  if (!oledOk || ahora - ultimoOLED < OLED_INTERVALO_MS) return;
  ultimoOLED = ahora;
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.println("P4  TELEMETRIA IOT");
  display.println(modoTCP ? "Modo: TCP" : "Modo: UDP");

  display.print("T: ");
  if (isnan(temperatura)) display.print("--.-");
  else display.print(temperatura, 1);
  display.print(" C   H: ");
  if (isnan(humedad)) display.print("--");
  else display.print(humedad, 0);
  display.println("%");

  if (modoTCP && alertaRemota) {
    display.println("*** ALERTA REMOTA ***");
  } else {
    display.print("Barra: ");
    display.print(segmentosPorTemperatura(temperatura));
    display.println("/8");
  }

  display.print("IP: ");
  display.println(WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : "SIN WIFI");
  display.println(modoTCP ? "TCP puerto 8080" : "UDP cada 50 ms");
  display.display();
}

void cambiarModo(bool nuevoTCP) {
  if (nuevoTCP == modoAnteriorTCP) return;
  modoAnteriorTCP = nuevoTCP;
  alertaRemota = false;
  estadoEstrobo = false;
  if (nuevoTCP) {
    udp.stop();
    tcpServer.begin();
    Serial.println("MODO TCP: escuchando en puerto 8080");
  } else {
    tcpServer.end();
    udp.begin(UDP_PORT_ESP32);
    Serial.println("MODO UDP: enviando cada 50 ms");
  }
}

void enviarUDP(unsigned long ahora) {
  if (WiFi.status() != WL_CONNECTED || sampleId == 0) return;
  if (ahora - ultimoEnvioUDP < UDP_INTERVALO_MS) return;
  ultimoEnvioUDP = ahora;
  String paquete = crearPaquete();
  udp.beginPacket(PC_IP, UDP_PORT_PC);
  udp.print(paquete);
  udp.endPacket();
}

void gestionarClienteTCP() {
  if (WiFi.status() != WL_CONNECTED) return;
  WiFiClient cliente = tcpServer.available();
  if (!cliente) return;

  cliente.setTimeout(1200);
  String orden = cliente.readStringUntil('\n');
  orden.trim();

  if (orden == "GET_TEMP") {
    cliente.println(crearPaquete());
    cliente.flush();

    // Java debe responder ALERTA_ON o ALERTA_OFF en el mismo socket.
    String respuestaPC = cliente.readStringUntil('\n');
    respuestaPC.trim();
    if (respuestaPC == "ALERTA_ON") {
      alertaRemota = true;
      ultimoParpadeo = millis() - ESTROBO_INTERVALO_MS;
      Serial.println("ALERTA REMOTA ACTIVADA");
    } else if (respuestaPC == "ALERTA_OFF") {
      alertaRemota = false;
      estadoEstrobo = false;
      Serial.println("Alerta remota desactivada");
    }
  } else {
    cliente.println("ERROR: USA GET_TEMP");
  }
  cliente.stop();
}

void setup() {
  Serial.begin(115200);
  dht.begin();
  pinMode(SWITCH_PIN, INPUT_PULLUP);
  for (int i = 0; i < LED_COUNT; i++) {
    pinMode(LED_PINS[i], OUTPUT);
    digitalWrite(LED_PINS[i], LOW);
  }

  Wire.begin(OLED_SDA, OLED_SCL);
  oledOk = display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDRESS);
  if (!oledOk) Serial.println("No se detecto la OLED: revisar SDA/SCL y 0x3C");

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Conectando a Wi-Fi");
  unsigned long inicio = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - inicio < 12000UL) {
    delay(250);
    Serial.print('.');
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("ESP32 IP: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("Sin Wi-Fi: revisa SSID/contrasena. Reintentare.");
  }

  ultimaLectura = millis() - DHT_INTERVALO_MS;
  modoAnteriorTCP = (digitalRead(SWITCH_PIN) == LOW); // fuerza cambio inicial
  modoTCP = (digitalRead(SWITCH_PIN) == HIGH);
  cambiarModo(modoTCP);
}

void loop() {
  unsigned long ahora = millis();
  bool nuevoTCP = (digitalRead(SWITCH_PIN) == HIGH);
  modoTCP = nuevoTCP;
  cambiarModo(modoTCP);

  if (WiFi.status() != WL_CONNECTED && ahora - ultimaRevisionWifi >= 10000UL) {
    ultimaRevisionWifi = ahora;
    WiFi.reconnect();
  }

  leerDHT(ahora);

  if (modoTCP) gestionarClienteTCP();
  else enviarUDP(ahora);

  actualizarLeds(millis());
  dibujarOLED(millis());
}
