package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        // r_0: prima eliberare este chiar acum. Grila se ține în ms, pe timpul
        // monoton System.nanoTime(), care nu sare la corecțiile ceasului.
        long next = System.nanoTime() / 1_000_000;

        while (true) {
            // ---- jobul eliberat la momentul next ----
            double temperature = sensor.read();
            long timestamp = System.currentTimeMillis();
            // Locale.US: punct zecimal, ca Double.parseDouble să poată citi valoarea
            String temperatureText = String.format(Locale.US, "%.2f", temperature);

            String message = deviceId + ";" + timestamp + ";" + temperatureText;
            mqtt.publish(Config.telemetryTopic(deviceId), message);
            System.out.println("telemetrie: " + message);

            // taskul periodic doar semnalează evenimentul; alarma o tratează
            // taskul sporadic. add() nu blochează (coada nu are limită), deci
            // taskul periodic nu așteaptă niciodată după taskul sporadic.
            double currentThreshold = threshold;
            if (temperature > currentThreshold) {
                alarmEvents.add(temperature);
                System.out.println("eveniment de alarmă: " + temperatureText + " > " + currentThreshold);
            }

            // ---- așteptare absolută până la următoarea eliberare de pe grilă ----
            // următoarea eliberare se calculează din grilă, nu din momentul de acum,
            // deci întârzierile unui job nu se adună la următoarele (fără derivă).
            // periodMs este citit la fiecare job: SET_INTERVAL are efect imediat.
            next = next + periodMs;
            long remaining = next - System.nanoTime() / 1_000_000;

            if (remaining < 0) {
                // termenul-limită este D = T: jobul a terminat după eliberarea
                // următorului job. Următorul job pornește imediat.
                System.out.println("termen ratat: jobul a depășit perioada cu " + (-remaining) + " ms");
            } else {
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    return; // thread-ul a fost întrerupt: oprim taskul
                }
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}
