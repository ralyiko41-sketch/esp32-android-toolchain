package com.esp32ide.utils

data class Example(
    val key: String,
    val name: String,
    val category: String,
    val description: String,
    val code: String
)

object Examples {
    val list = listOf(
        Example("blink","LED Blink","Basic","Flash built-in LED on GPIO 2","""
// ESP32 Blink
#define LED_BUILTIN 2
void setup() {
  Serial.begin(115200);
  pinMode(LED_BUILTIN, OUTPUT);
  Serial.println("ESP32 Ready!");
}
void loop() {
  digitalWrite(LED_BUILTIN, HIGH); delay(500);
  digitalWrite(LED_BUILTIN, LOW);  delay(500);
  Serial.println("tick");
}""".trimIndent()),

        Example("wifi_scan","WiFi Scanner","Networking","Scan nearby WiFi networks","""
#include <WiFi.h>
void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  delay(100);
}
void loop() {
  Serial.println("Scanning...");
  int n = WiFi.scanNetworks();
  for (int i = 0; i < n; i++) {
    Serial.printf("[%d] %s  RSSI:%d  CH:%d\n",
      i+1, WiFi.SSID(i).c_str(), WiFi.RSSI(i), WiFi.channel(i));
  }
  WiFi.scanDelete();
  delay(5000);
}""".trimIndent()),

        Example("wifi_connect","WiFi + HTTP GET","Networking","Connect WiFi and fetch a URL","""
#include <WiFi.h>
#include <HTTPClient.h>
const char* ssid = "YOUR_SSID";
const char* password = "YOUR_PASSWORD";
void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nConnected! IP: " + WiFi.localIP().toString());
}
void loop() {
  HTTPClient http;
  http.begin("http://httpbin.org/get");
  int code = http.GET();
  if (code > 0) Serial.println(http.getString());
  http.end();
  delay(10000);
}""".trimIndent()),

        Example("analog_plot","Analog Read + Plotter","Sensors","ADC on GPIO34 — open Serial Plotter","""
// Send to Serial Plotter: Tools > Serial Plotter
#define ADC_PIN 34
void setup() {
  Serial.begin(115200);
  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);
  Serial.println("raw,voltage");
}
void loop() {
  int raw = analogRead(ADC_PIN);
  float v = raw * (3.9f / 4095.0f);
  Serial.printf("%d,%.4f\n", raw, v);
  delay(50);
}""".trimIndent()),

        Example("dht22","DHT22 Sensor","Sensors","Temperature + humidity","""
#include <DHT.h>
#define DHTPIN 4
#define DHTTYPE DHT22
DHT dht(DHTPIN, DHTTYPE);
void setup() {
  Serial.begin(115200);
  dht.begin();
}
void loop() {
  delay(2000);
  float h = dht.readHumidity();
  float t = dht.readTemperature();
  if (!isnan(h) && !isnan(t))
    Serial.printf("T:%.1f°C  H:%.1f%%\n", t, h);
  else
    Serial.println("DHT read failed!");
}""".trimIndent()),

        Example("bme280","BME280 Environment","Sensors","Temp + humidity + pressure via I2C","""
#include <Wire.h>
#include <Adafruit_BME280.h>
Adafruit_BME280 bme;
void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);
  if (!bme.begin(0x76)) { Serial.println("BME280 not found!"); while(1); }
}
void loop() {
  Serial.printf("T:%.2f°C  H:%.2f%%  P:%.2fhPa\n",
    bme.readTemperature(), bme.readHumidity(), bme.readPressure()/100.0f);
  delay(2000);
}""".trimIndent()),

        Example("servo","Servo Sweep","Actuators","Sweep servo 0-180°","""
#include <ESP32Servo.h>
Servo servo;
#define SERVO_PIN 13
void setup() {
  Serial.begin(115200);
  ESP32PWM::allocateTimer(0);
  servo.setPeriodHertz(50);
  servo.attach(SERVO_PIN, 500, 2400);
}
void loop() {
  for (int pos = 0; pos <= 180; pos++) { servo.write(pos); delay(10); }
  for (int pos = 180; pos >= 0; pos--) { servo.write(pos); delay(10); }
}""".trimIndent()),

        Example("deep_sleep","Deep Sleep","Power","Wake every 10s from timer","""
#include <esp_sleep.h>
RTC_DATA_ATTR int boots = 0;
#define SLEEP_SEC 10
void setup() {
  Serial.begin(115200); delay(100);
  Serial.printf("Boot #%d\n", ++boots);
  Serial.printf("Sleeping %ds...\n", SLEEP_SEC);
  Serial.flush();
  esp_sleep_enable_timer_wakeup(SLEEP_SEC * 1000000ULL);
  esp_deep_sleep_start();
}
void loop() {}""".trimIndent()),

        Example("freertos","FreeRTOS Dual Core","RTOS","Two tasks on two cores","""
TaskHandle_t t1, t2;
void task1(void* p) {
  Serial.printf("Task1 on core %d\n", xPortGetCoreID());
  for(;;) { Serial.println("[Core0] tick"); vTaskDelay(1000/portTICK_PERIOD_MS); }
}
void task2(void* p) {
  Serial.printf("Task2 on core %d\n", xPortGetCoreID());
  for(;;) { Serial.println("[Core1] tock"); vTaskDelay(500/portTICK_PERIOD_MS); }
}
void setup() {
  Serial.begin(115200);
  xTaskCreatePinnedToCore(task1,"T1",10000,NULL,1,&t1,0);
  xTaskCreatePinnedToCore(task2,"T2",10000,NULL,1,&t2,1);
}
void loop() { delay(1000); }""".trimIndent()),

        Example("mqtt","MQTT Client","IoT","Publish sensor data to broker","""
#include <WiFi.h>
#include <PubSubClient.h>
const char* ssid = "YOUR_SSID";
const char* pass = "YOUR_PASS";
const char* broker = "broker.hivemq.com";
WiFiClient espClient;
PubSubClient mqtt(espClient);
void reconnect() {
  while (!mqtt.connected()) {
    if (mqtt.connect("ESP32Client")) mqtt.subscribe("esp32/cmd");
    else delay(2000);
  }
}
void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, pass);
  while (WiFi.status() != WL_CONNECTED) delay(500);
  mqtt.setServer(broker, 1883);
}
void loop() {
  if (!mqtt.connected()) reconnect();
  mqtt.loop();
  static long last = 0;
  if (millis()-last > 5000) {
    last = millis();
    mqtt.publish("esp32/sensor", "{\"temp\":25.4}");
    Serial.println("Published!");
  }
}""".trimIndent()),

        Example("ota","OTA Update","Advanced","Flash firmware over WiFi","""
#include <WiFi.h>
#include <ArduinoOTA.h>
const char* ssid = "YOUR_SSID";
const char* pass = "YOUR_PASS";
void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, pass);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nIP: " + WiFi.localIP().toString());
  ArduinoOTA.setHostname("esp32-ota");
  ArduinoOTA.onStart([]() { Serial.println("OTA Start"); })
            .onEnd([]() { Serial.println("\nOTA Done"); })
            .onProgress([](unsigned int p, unsigned int t) { Serial.printf("OTA: %u%%\r", p/(t/100)); })
            .onError([](ota_error_t e) { Serial.printf("Error[%u]\n", e); });
  ArduinoOTA.begin();
  Serial.println("OTA Ready");
}
void loop() { ArduinoOTA.handle(); }""".trimIndent())
    )

    val categories get() = listOf("All") + list.map { it.category }.distinct()
}
