import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class ConsoleClient {
    private static final String RELAY_URL = "https://ntfy.sh";
    private static final Object PRINT_LOCK = new Object();
    private static final HttpClient HTTP = HttpClient.newBuilder().build();

    public static void main(String[] args) {
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String roomCode = prompt(stdin, "Room code: ").strip();
            while (roomCode.isEmpty()) roomCode = prompt(stdin, "Room code (required): ").strip();

            String topic = "macelimiter-" + sha256(roomCode).substring(0, 48);
            String topicUrl = RELAY_URL + "/" + topic;
            String clientId = java.util.UUID.randomUUID().toString();
            Relay relay = new Relay(topicUrl, clientId);

            Thread reader = new Thread(relay::listen, "relay-reader");
            reader.setDaemon(true);
            reader.start();

            System.out.println("Connecting using room code...");
            long deadline = System.currentTimeMillis() + 20_000;
            while (!relay.connected && System.currentTimeMillis() < deadline) {
                relay.publish("CONNECT|" + clientId);
                Thread.sleep(2_000);
            }
            if (!relay.connected) {
                System.out.println("Could not find a MaceLimiter plugin using that room code.");
                return;
            }

            System.out.println("Connected. Type commands and press Enter. Type 'exit' or 'quit' to disconnect.");
            System.out.println("------------------------------------------------------------");
            String input;
            while ((input = stdin.readLine()) != null) {
                String cmd = input.strip();
                if (cmd.isEmpty()) continue;
                if (cmd.equalsIgnoreCase("exit") || cmd.equalsIgnoreCase("quit")) break;
                relay.publish("CMD|" + clientId + "|" + cmd);
            }
            relay.publish("DISCONNECT|" + clientId);
            System.out.println("Disconnected.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String prompt(BufferedReader stdin, String label) throws IOException {
        System.out.print(label);
        System.out.flush();
        String line = stdin.readLine();
        if (line == null) System.exit(0);
        return line;
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static String jsonField(String json, String field) {
        String key = """ + field + "":"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { out.append(c); escaped = false; continue; }
            if (c == '\') { escaped = true; out.append(c); continue; }
            if (c == '"') return out.toString();
            out.append(c);
        }
        return null;
    }

    private static final class Relay {
        private final String topicUrl;
        private final String clientId;
        private volatile boolean connected;
        private volatile boolean running = true;

        Relay(String topicUrl, String clientId) {
            this.topicUrl = topicUrl;
            this.clientId = clientId;
        }

        void publish(String message) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(topicUrl))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8))
                        .build();
                HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                synchronized (PRINT_LOCK) { System.out.println("Relay error: " + e.getMessage()); }
            }
        }

        void listen() {
            String since = "now";
            while (running) {
                try {
                    String url = topicUrl + "/json?poll=1&since=" + java.net.URLEncoder.encode(since, StandardCharsets.UTF_8);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
                    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() / 100 != 2) { Thread.sleep(1_000); continue; }
                    for (String line : response.body().split("\R")) {
                        if (line.isBlank()) continue;
                        String id = jsonField(line, "id");
                        String message = jsonField(line, "message");
                        if (id != null) since = id;
                        if (message == null) continue;
                        if (message.equals("WELCOME|" + clientId)) { connected = true; continue; }
                        String prefix = "OUT|" + clientId + "|";
                        if (message.startsWith(prefix)) {
                            String output = message.substring(prefix.length()).replace("\n", "
");
                            synchronized (PRINT_LOCK) { System.out.println(output); }
                        }
                    }
                } catch (Exception e) {
                    if (running) try { Thread.sleep(1_500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }
}
