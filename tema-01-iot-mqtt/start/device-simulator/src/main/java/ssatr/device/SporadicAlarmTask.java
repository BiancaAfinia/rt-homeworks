package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */

public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;
    private final PeriodicTelemetryTask periodic;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<Double> alarmEvents,
                             PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        boolean released = false;   // a fost eliberat vreun job până acum?
        long lastRelease = 0;       // momentul ultimei eliberări (ms, timp monoton)

        while (true) {
            double temperature;
            try {
                // take() blochează thread-ul până sosește un eveniment;
                // cât timp coada e goală, thread-ul nu consumă procesor
                temperature = alarmEvents.take();
            } catch (InterruptedException e) {
                return; // thread-ul a fost întrerupt: oprim taskul
            }

            long now = System.nanoTime() / 1_000_000;

            // intervalul minim între sosiri: evenimentele prea dese sunt respinse
            if (released && now - lastRelease < MIN_INTERARRIVAL_MS) {
                System.out.println("alarmă RESPINSĂ: au trecut doar " + (now - lastRelease)
                        + " ms (interval minim " + MIN_INTERARRIVAL_MS + " ms)");
                continue;
            }
            released = true;
            lastRelease = now;

            // jobul sporadic: publicarea alarmei
            long timestamp = System.currentTimeMillis();
            String message = deviceId + ";" + timestamp + ";"
                    + String.format(Locale.US, "%.2f", temperature) + ";"
                    + String.format(Locale.US, "%.2f", periodic.getThreshold());
            mqtt.publish(Config.alarmTopic(deviceId), message);
            System.out.println("alarmă publicată: " + message);
        }
    }
}

