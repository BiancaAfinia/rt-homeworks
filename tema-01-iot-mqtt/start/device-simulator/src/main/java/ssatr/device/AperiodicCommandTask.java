package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }


    @Override
    public void run() {
        while (true) {
            String command;
            try {
                // take() blochează thread-ul până sosește o comandă
                command = commands.take();
            } catch (InterruptedException e) {
                return; // thread-ul a fost întrerupt: oprim taskul
            }

            String result;
            try {
                result = execute(command.trim());
            } catch (IllegalArgumentException e) {
                // comandă necunoscută sau parametru invalid: raportăm și continuăm
                result = "ERROR " + e.getMessage();
            }

            System.out.println("comandă: \"" + command + "\" -> " + result);
            publishStatus(result);
        }
    }

    /** Execută o comandă și întoarce textul confirmării; aruncă IllegalArgumentException dacă e invalidă. */
    private String execute(String command) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("comandă goală");
        }

        String[] parts = command.split("\\s+");
        String name = parts[0].toUpperCase(Locale.ROOT);

        switch (name) {
            case "SET_INTERVAL": {
                requireParameters(name, parts, 1);
                long ms;
                try {
                    ms = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("SET_INTERVAL: parametru invalid '" + parts[1] + "'");
                }
                if (ms <= 0) {
                    throw new IllegalArgumentException("SET_INTERVAL: perioada trebuie să fie > 0");
                }
                periodic.setPeriodMs(ms);
                return "OK SET_INTERVAL " + ms;
            }
            case "SET_THRESHOLD": {
                requireParameters(name, parts, 1);
                double value;
                try {
                    value = Double.parseDouble(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("SET_THRESHOLD: parametru invalid '" + parts[1] + "'");
                }
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("SET_THRESHOLD: valoare invalidă '" + parts[1] + "'");
                }
                periodic.setThreshold(value);
                return "OK SET_THRESHOLD " + String.format(Locale.US, "%.2f", value);
            }
            case "RESET":
                requireParameters(name, parts, 0);
                sensor.reset();
                return "OK RESET";
            case "STATUS":
                requireParameters(name, parts, 0);
                return "OK STATUS interval=" + periodic.getPeriodMs() + " ms, prag="
                        + String.format(Locale.US, "%.2f", periodic.getThreshold());
            default:
                throw new IllegalArgumentException("comandă necunoscută '" + parts[0] + "'");
        }
    }

    private static void requireParameters(String name, String[] parts, int expected) {
        int actual = parts.length - 1;
        if (actual != expected) {
            throw new IllegalArgumentException(name + ": se așteaptă " + expected + " parametri, sunt " + actual);
        }
    }

    /** Publică "deviceId;timestamp;text" pe topic-ul de status. */
    private void publishStatus(String text) {
        // ";" este separatorul câmpurilor: nu trebuie să apară în text
        String safeText = text.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
        String message = deviceId + ";" + System.currentTimeMillis() + ";" + safeText;
        mqtt.publish(Config.statusTopic(deviceId), message);
    }
}
