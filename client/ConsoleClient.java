import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ConsoleClient {

    private static final int DEFAULT_PORT = 25575;
    private static final Object PRINT_LOCK = new Object();

    public static void main(String[] args) {
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String host = prompt(stdin, "Server IP: ");
            while (host.isBlank()) {
                host = prompt(stdin, "Server IP (required): ");
            }
            host = host.strip();

            int port = DEFAULT_PORT;
            while (true) {
                String portInput = prompt(stdin, "Port [" + DEFAULT_PORT + "]: ").strip();
                if (portInput.isEmpty()) {
                    break;
                }
                try {
                    port = Integer.parseInt(portInput);
                    if (port >= 1 && port <= 65535) {
                        break;
                    }
                } catch (NumberFormatException ignored) {
                }
                System.out.println("Invalid port. Enter a number between 1 and 65535.");
            }

            String password = readPassword(stdin);

            System.out.println("Connecting to " + host + ":" + port + " ...");
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout(10_000);
            socket.setKeepAlive(true);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String banner = in.readLine();
            if (!"AUTH_REQUIRED".equals(banner)) {
                System.out.println("Unexpected server response. Is this a MaceLimiter console port?");
                socket.close();
                return;
            }

            send(out, password);
            String result = in.readLine();
            if (!"AUTH_OK".equals(result)) {
                System.out.println("Authentication failed.");
                socket.close();
                return;
            }
            socket.setSoTimeout(0);
            System.out.println("Authenticated. Type commands and press Enter. Type 'exit' or 'quit' to disconnect.");
            System.out.println("------------------------------------------------------------");

            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (PRINT_LOCK) {
                            System.out.println(line);
                        }
                    }
                } catch (IOException ignored) {
                }
                synchronized (PRINT_LOCK) {
                    System.out.println("Connection closed by server.");
                }
                System.exit(0);
            }, "console-reader");
            reader.setDaemon(true);
            reader.start();

            String input;
            while ((input = stdin.readLine()) != null) {
                String cmd = input.strip();
                if (cmd.isEmpty()) {
                    continue;
                }
                if (cmd.equalsIgnoreCase("exit") || cmd.equalsIgnoreCase("quit")) {
                    break;
                }
                send(out, cmd);
            }
            socket.close();
            System.out.println("Disconnected.");
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String prompt(BufferedReader stdin, String label) throws IOException {
        System.out.print(label);
        System.out.flush();
        String line = stdin.readLine();
        if (line == null) {
            System.exit(0);
        }
        return line;
    }

    private static String readPassword(BufferedReader stdin) throws IOException {
        Console console = System.console();
        if (console != null && console.isTerminal()) {
            char[] pw = console.readPassword("Password: ");
            if (pw == null) {
                System.exit(0);
            }
            String result = new String(pw);
            java.util.Arrays.fill(pw, '\0');
            return result;
        }
        System.out.println("(No secure console available; password input will be visible.)");
        return prompt(stdin, "Password: ");
    }

    private static void send(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.write('\n');
        out.flush();
    }
}
