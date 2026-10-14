package ssatr.service;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul trebuie sincronizat.
 */
public class Dashboard {

    /** Starea unui device. Este accesată doar cât timp se ține lock-ul Dashboard-ului. */
    private static class DeviceState {
        double lastTemperature = Double.NaN;   // NaN: încă nu a sosit telemetrie
        long lastSeenMs;                       // momentul sosirii ultimului mesaj (ceasul serviciului)
        int alarmCount;
    }

    // TreeMap: device-urile apar în ordine alfabetică în tabel.
    // Toate metodele sunt synchronized pe același lock (this), fiindcă mapa este
    // modificată de 3 thread-uri MessageProcessor și citită de consolă.
    private final Map<String, DeviceState> devices = new TreeMap<>();

    /** Telemetrie nouă: reține temperatura și momentul sosirii. */
    public synchronized void updateTelemetry(String deviceId, double temperature) {
        DeviceState state = stateOf(deviceId);
        state.lastTemperature = temperature;
        state.lastSeenMs = System.currentTimeMillis();
    }

    /** Alarmă nouă: crește contorul de alarme. */
    public synchronized void updateAlarm(String deviceId) {
        DeviceState state = stateOf(deviceId);
        state.alarmCount++;
        state.lastSeenMs = System.currentTimeMillis();
    }

    /** Confirmare nouă: device-ul este activ. */
    public synchronized void updateStatus(String deviceId) {
        stateOf(deviceId).lastSeenMs = System.currentTimeMillis();
    }

    private DeviceState stateOf(String deviceId) {
        return devices.computeIfAbsent(deviceId, id -> new DeviceState());
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public void print() {
        // copiem datele sub lock, apoi afișăm fără lock (println poate fi lent)
        ArrayList<String> rows = new ArrayList<>();
        synchronized (this) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
                DeviceState s = entry.getValue();
                String temperature = Double.isNaN(s.lastTemperature)
                        ? "-" : String.format(Locale.US, "%.2f", s.lastTemperature);
                String age = "acum " + Math.max(0, (now - s.lastSeenMs) / 1000) + " s";
                rows.add(String.format(Locale.US, "%-12s %-8s %-16s %d",
                        entry.getKey(), temperature, age, s.alarmCount));
            }
        }

        System.out.println(String.format("%-12s %-8s %-16s %s", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME"));
        if (rows.isEmpty()) {
            System.out.println("(niciun device nu a trimis încă mesaje)");
        }
        rows.forEach(System.out::println);
    }
}
