package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (true) {
            String message;
            try {
                // take() blochează thread-ul până sosește un mesaj;
                // cât timp coada e goală, thread-ul nu consumă procesor
                message = inbox.take();
            } catch (InterruptedException e) {
                return; // thread-ul a fost întrerupt: oprim procesarea
            }

            try {
                switch (kind) {
                    case "telemetry":
                        handleTelemetry(message);
                        break;
                    case "alarm":
                        handleAlarm(message);
                        break;
                    case "status":
                        handleStatus(message);
                        break;
                    default:
                        throw new IllegalArgumentException("tip de mesaj necunoscut");
                }
            } catch (IllegalArgumentException e) {
                // NumberFormatException este tot o IllegalArgumentException.
                // Mesajul greșit se raportează și se sare peste el; thread-ul
                // continuă cu următorul mesaj din coadă.
                System.out.println("mesaj " + kind + " invalid, ignorat: \"" + message + "\" (" + e.getMessage() + ")");
            }
        }
    }

    /** telemetry: deviceId;timestamp;temperatura */
    private void handleTelemetry(String message) {
        String[] fields = split(message, 3);
        String deviceId = deviceId(fields);
        parseTimestamp(fields[1]);
        double temperature = parseDouble(fields[2], "temperatura");

        // mesajul este valid: îl salvăm exact cum a sosit, apoi actualizăm dashboard-ul
        storage.append(kind, message);
        dashboard.updateTelemetry(deviceId, temperature);
    }

    /** alarm: deviceId;timestamp;temperatura;prag */
    private void handleAlarm(String message) {
        String[] fields = split(message, 4);
        String deviceId = deviceId(fields);
        parseTimestamp(fields[1]);
        double temperature = parseDouble(fields[2], "temperatura");
        double threshold = parseDouble(fields[3], "prag");

        storage.append(kind, message);
        dashboard.updateAlarm(deviceId);

        // alarmele se afișează imediat în consolă
        System.out.println("!!! ALARMĂ " + deviceId + ": temperatura " + fields[2] + " depășește pragul " + fields[3]);
    }

    /** status: deviceId;timestamp;text */
    private void handleStatus(String message) {
        String[] fields = split(message, 3);
        String deviceId = deviceId(fields);
        parseTimestamp(fields[1]);
        String text = fields[2];

        storage.append(kind, message);
        dashboard.updateStatus(deviceId);

        // confirmările se afișează imediat în consolă
        System.out.println(">>> " + deviceId + ": " + text);
    }

    /**
     * Împarte mesajul în exact expected câmpuri. Limita expected face ca
     * eventualele ";" din ultimul câmp (textul unei confirmări) să rămână în el.
     * Pentru telemetrie și alarmă, un ";" în plus produce un câmp numeric
     * invalid, deci mesajul este respins la parsare.
     */
    private static String[] split(String message, int expected) {
        String[] fields = message.split(";", expected);
        if (fields.length != expected) {
            throw new IllegalArgumentException("se așteaptă " + expected + " câmpuri, sunt " + fields.length);
        }
        return fields;
    }

    private static String deviceId(String[] fields) {
        String deviceId = fields[0].trim();
        if (deviceId.isEmpty()) {
            throw new IllegalArgumentException("deviceId lipsește");
        }
        return deviceId;
    }

    private static long parseTimestamp(String text) {
        return Long.parseLong(text.trim()); // NumberFormatException -> IllegalArgumentException
    }

    private static double parseDouble(String text, String fieldName) {
        double value = Double.parseDouble(text.trim());
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(fieldName + " nu este un număr finit: " + text);
        }
        return value;
    }
}
