// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Așa cum este, schița se conectează la Wi-Fi și la broker și afișează pe
// Serial comenzile primite. Taskurile le scrieți voi: TODO 0 ... TODO 3.
// Exemplele de pornire sunt în laboratorul 0 din rt-labs-esp32c3; vezi
// exemple/README.md din folderul temei.
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include <errno.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "config.h"
#include "connection.h"

// ------------------------------------------------------------------
// Date comune taskurilor
// ------------------------------------------------------------------

// Eveniment de alarmă, pus în coadă prin valoare (xQueueSend copiază structul).
// Pragul se transmite împreună cu temperatura: mesajul de alarmă conține exact
// pragul pe care l-a depășit temperatura, chiar dacă între timp vine SET_THRESHOLD.
struct AlarmEvent {
    float temperature;
    float threshold;
};

// Comandă primită pe MQTT. Conține textul însuși, nu un pointer, pentru că
// "text" din command_received() este valid doar în timpul apelului.
struct Command {
    char text[COMMAND_MAX_LEN];
};

// Cozile prin care comunică taskurile (create în setup(), TODO 0 a).
static QueueHandle_t alarm_queue = NULL;    // periodic  -> sporadic
static QueueHandle_t command_queue = NULL;  // command_received() -> aperiodic

const UBaseType_t ALARM_QUEUE_LEN = 10;
const UBaseType_t COMMAND_QUEUE_LEN = 8;

// Prioritățile (între 2 și 19). Cea mai mare are taskul cu cerințele de timp
// cele mai stricte: perioada T. Sporadicul vine al doilea (interval minim 5 s),
// aperiodicul ultimul (nu are termen strict, doar răspunde la comenzi).
const UBaseType_t PRIORITY_PERIODIC = 5;
const UBaseType_t PRIORITY_SPORADIC = 4;
const UBaseType_t PRIORITY_APERIODIC = 3;

// Stiva taskurilor, în octeți (snprintf cu float și Serial.printf au nevoie de loc).
const uint32_t TASK_STACK_BYTES = 6144;

// Limitele acceptate pentru SET_INTERVAL (ms).
const long MIN_PERIOD_MS = 10;
const long MAX_PERIOD_MS = 3600000;  // o oră

// volatile: citite de taskul periodic, modificate de taskul aperiodic
// (taskuri diferite). Sunt citite o singură dată pe job, într-o variabilă locală.
static volatile uint32_t period_ms = PERIOD_MS_INITIAL;
static volatile float threshold = THRESHOLD_INITIAL;

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
// Este temperatura siliciului, nu a camerei: imediat după pornirea Wi-Fi-ului
// poate fi în jur de 50 °C, apoi scade în câteva minute spre 35-40 °C.
// Rezoluția este de aproximativ 1 °C. Ca să provocați alarme, puneți pragul
// cu SET_THRESHOLD cu un grad sub valoarea citită.
float read_temperature(void) {
    return temperatureRead();
}

// TASK PERIODIC (TODO 1), cu perioada T = period_ms.
// Fiecare job citește senzorul, publică telemetria și, dacă temperatura trece
// de prag, semnalează un eveniment în coada de alarme. Alarma o tratează taskul sporadic.
static void periodic_task(void* arg) {
    (void)arg;

    // r_0: prima eliberare este chiar acum. De aici, xTaskDelayUntil ține
    // grila r_k = r_0 + k*T: "last" este momentul ultimei eliberări.
    TickType_t last = xTaskGetTickCount();

    for (;;) {
        // ---- jobul eliberat la momentul "last" ----
        float temperature = read_temperature();

        char text[96];
        snprintf(text, sizeof(text), "%s;%lld;%.2f", DEVICE_ID, (long long)epoch_ms(), temperature);
        mqtt_publish("telemetry", text);
        Serial.printf("[periodic] telemetrie: %s\n", text);

        float current_threshold = threshold;
        if (temperature > current_threshold) {
            AlarmEvent event = {temperature, current_threshold};
            // timeout 0: nu blocăm niciodată taskul periodic după taskul sporadic
            if (xQueueSend(alarm_queue, &event, 0) == pdTRUE) {
                Serial.printf("[periodic] eveniment de alarmă: %.2f > %.2f\n", temperature, current_threshold);
            } else {
                Serial.println("[periodic] coada de alarme este plină, eveniment pierdut");
            }
        }

        // ---- așteptare ABSOLUTĂ până la următoarea eliberare de pe grilă ----
        // Nu vTaskDelay(T): acela ar număra T de la sfârșitul jobului și ar produce
        // derivă. period_ms este citit la fiecare job, deci SET_INTERVAL are efect imediat.
        BaseType_t waited = xTaskDelayUntil(&last, pdMS_TO_TICKS(period_ms));
        if (waited == pdFALSE) {
            // eliberarea următoare era deja trecută: jobul a depășit perioada;
            // următorul job pornește imediat, iar grila rămâne pe loc
            Serial.println("[periodic] termen ratat: jobul a depășit perioada");
        }
    }
}

// TASK SPORADIC (TODO 2): tratează alarmele de temperatură.
// Intervalul minim între două alarme publicate este MIN_INTERARRIVAL_MS;
// evenimentele care sosesc mai des sunt respinse.
static void sporadic_task(void* arg) {
    (void)arg;

    bool released = false;       // a fost publicată vreo alarmă până acum?
    uint32_t last_release = 0;   // millis() la ultima alarmă publicată

    for (;;) {
        AlarmEvent event;
        // portMAX_DELAY: taskul stă blocat (fără consum de procesor) până vine un eveniment
        if (xQueueReceive(alarm_queue, &event, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        // millis() este monoton; scăderea pe uint32_t rămâne corectă și la depășirea valorii maxime
        uint32_t now = millis();
        if (released && (uint32_t)(now - last_release) < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] alarmă RESPINSĂ: au trecut doar %u ms (minim %u ms)\n",
                          (unsigned)(now - last_release), (unsigned)MIN_INTERARRIVAL_MS);
            continue;
        }
        released = true;
        last_release = now;

        // jobul sporadic: publicarea alarmei
        char text[112];
        snprintf(text, sizeof(text), "%s;%lld;%.2f;%.2f", DEVICE_ID, (long long)epoch_ms(),
                 event.temperature, event.threshold);
        mqtt_publish("alarm", text);
        Serial.printf("[sporadic] alarmă publicată: %s\n", text);
    }
}

// ---- ajutoare pentru taskul aperiodic ----

// Convertește tot șirul s într-un întreg; false dacă nu este număr întreg valid.
static bool parse_long(const char* s, long* out) {
    char* end = NULL;
    errno = 0;
    long value = strtol(s, &end, 10);
    if (errno != 0 || end == s || *end != '\0') return false;
    *out = value;
    return true;
}

// Convertește tot șirul s într-un număr zecimal (cu punct); false dacă e invalid sau nu e finit.
static bool parse_float(const char* s, float* out) {
    char* end = NULL;
    errno = 0;
    float value = strtof(s, &end);
    if (errno != 0 || end == s || *end != '\0' || !isfinite(value)) return false;
    *out = value;
    return true;
}

// Execută o comandă și scrie în "result" textul confirmării: "OK ..." sau "ERROR <motiv>".
// Mesajele care pleacă pe MQTT sunt fără diacritice, ca să nu depindă de codarea
// din Java. Nu publică nimic: asta face taskul aperiodic.
static void execute_command(const char* command, char* result, size_t size) {
    char buffer[COMMAND_MAX_LEN];
    strncpy(buffer, command, sizeof(buffer) - 1);
    buffer[sizeof(buffer) - 1] = '\0';

    // "SET_INTERVAL 1000" -> nume = "SET_INTERVAL", arg = "1000"
    const char* separators = " \t\r\n";
    char* save = NULL;
    char* name = strtok_r(buffer, separators, &save);
    char* arg = strtok_r(NULL, separators, &save);
    char* extra = strtok_r(NULL, separators, &save);

    if (name == NULL) {
        snprintf(result, size, "ERROR comanda goala");
    } else if (strcasecmp(name, "SET_INTERVAL") == 0) {
        long value;
        if (arg == NULL || extra != NULL) {
            snprintf(result, size, "ERROR SET_INTERVAL: se asteapta 1 parametru");
        } else if (!parse_long(arg, &value)) {
            snprintf(result, size, "ERROR SET_INTERVAL: parametru invalid '%.20s'", arg);
        } else if (value < MIN_PERIOD_MS || value > MAX_PERIOD_MS) {
            snprintf(result, size, "ERROR SET_INTERVAL: perioada trebuie sa fie intre %ld si %ld ms",
                     MIN_PERIOD_MS, MAX_PERIOD_MS);
        } else {
            period_ms = (uint32_t)value;
            snprintf(result, size, "OK SET_INTERVAL %ld", value);
        }
    } else if (strcasecmp(name, "SET_THRESHOLD") == 0) {
        float value;
        if (arg == NULL || extra != NULL) {
            snprintf(result, size, "ERROR SET_THRESHOLD: se asteapta 1 parametru");
        } else if (!parse_float(arg, &value)) {
            snprintf(result, size, "ERROR SET_THRESHOLD: parametru invalid '%.20s'", arg);
        } else {
            threshold = value;
            snprintf(result, size, "OK SET_THRESHOLD %.2f", value);
        }
    } else if (strcasecmp(name, "RESET") == 0) {
        if (arg != NULL) {
            snprintf(result, size, "ERROR RESET: nu are parametri");
        } else {
            // senzorul intern nu poate fi resetat: RESET readuce perioada și pragul la valorile inițiale
            period_ms = PERIOD_MS_INITIAL;
            threshold = THRESHOLD_INITIAL;
            snprintf(result, size, "OK RESET");
        }
    } else if (strcasecmp(name, "STATUS") == 0) {
        if (arg != NULL) {
            snprintf(result, size, "ERROR STATUS: nu are parametri");
        } else {
            snprintf(result, size, "OK STATUS interval=%u ms, prag=%.2f", (unsigned)period_ms, (float)threshold);
        }
    } else {
        snprintf(result, size, "ERROR comanda necunoscuta '%.20s'", name);
    }
}

// Publică "deviceId;timestamp;text" pe topic-ul "status".
static void publish_status(const char* text) {
    // ";" este separatorul câmpurilor: nu are voie să apară în text
    char safe[128];
    size_t i = 0;
    for (; text[i] != '\0' && i < sizeof(safe) - 1; i++) {
        char c = text[i];
        safe[i] = (c == ';' || c == '\n' || c == '\r') ? ',' : c;
    }
    safe[i] = '\0';

    char message[192];
    snprintf(message, sizeof(message), "%s;%lld;%s", DEVICE_ID, (long long)epoch_ms(), safe);
    mqtt_publish("status", message);
}

// TASK APERIODIC (TODO 3): execută comenzile primite de la business service.
// Comenzile sosesc oricând; fiecare eliberează un job al taskului.
static void aperiodic_task(void* arg) {
    (void)arg;

    for (;;) {
        Command command;
        // blocat (fără consum de procesor) până sosește o comandă
        if (xQueueReceive(command_queue, &command, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        // comandă necunoscută sau parametru invalid: "ERROR ...", taskul continuă
        char result[128];
        execute_command(command.text, result, sizeof(result));

        Serial.printf("[aperiodic] comandă \"%s\" -> %s\n", command.text, result);
        publish_status(result);
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    // Doar copiem comanda în coadă (timeout 0, nu ne blocăm): o execută taskul aperiodic.
    if (command_queue == NULL) return;

    Command command;
    strncpy(command.text, text, sizeof(command.text) - 1);
    command.text[sizeof(command.text) - 1] = '\0';

    if (xQueueSend(command_queue, &command, 0) != pdTRUE) {
        Serial.println("[mqtt] coada de comenzi este plină, comandă pierdută");
    }
}

void setup() {
    Serial.begin(115200);
    // Fără așteptare la scriere: cu placa la USB, dar fără Serial Monitor deschis,
    // fiecare Serial.printf() ar aștepta până la 2 s să fie citit și ar întârzia
    // taskul care scrie. Așa, textul pe care nu-l citește nimeni se pierde.
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);  // așteptăm Serial Monitor, cel mult 3 s
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    // TODO 0 (a): cozile, create înainte de mqtt_start(): de acolo încolo pot
    // sosi comenzi, iar command_received() le pune în command_queue.
    alarm_queue = xQueueCreate(ALARM_QUEUE_LEN, sizeof(AlarmEvent));
    command_queue = xQueueCreate(COMMAND_QUEUE_LEN, sizeof(Command));
    if (alarm_queue == NULL || command_queue == NULL) {
        Serial.println("EROARE: nu s-au putut crea cozile (memorie insuficientă)");
        return;
    }

    mqtt_start();

    // TODO 0 (b): taskurile, pornite după mqtt_start(), ca primele mesaje publicate să nu se piardă.
    xTaskCreate(periodic_task, "periodic", TASK_STACK_BYTES, NULL, PRIORITY_PERIODIC, NULL);
    xTaskCreate(sporadic_task, "sporadic", TASK_STACK_BYTES, NULL, PRIORITY_SPORADIC, NULL);
    xTaskCreate(aperiodic_task, "aperiodic", TASK_STACK_BYTES, NULL, PRIORITY_APERIODIC, NULL);
    Serial.println("taskuri pornite: periodic, sporadic, aperiodic");
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}
