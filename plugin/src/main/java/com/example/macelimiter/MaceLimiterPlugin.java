package com.example.macelimiter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class MaceLimiterPlugin extends JavaPlugin implements Listener {

    private static final int MAX_MACES = 6;
    private static final String WARNING_MESSAGE =
            "An excess Mace broke! Only 6 Maces can exist on this server.";
    private static final String DEFAULT_PASSWORD = "change_this_password";
    private static final int MAX_CLIENTS = 5;
    private static final int MAX_LINE_LENGTH = 4096;

    private final Object fileLock = new Object();
    private File dataFile;
    private int lastPersistedCount = -1;

    private RemoteConsoleServer consoleServer;
    private ConsoleAppender consoleAppender;
    private org.apache.logging.log4j.core.Logger coreRootLogger;

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onEnable() {
        getConfig().addDefault("console.bind-address", "0.0.0.0");
        getConfig().addDefault("console.port", 25575);
        getConfig().addDefault("console.password", DEFAULT_PASSWORD);
        getConfig().options().copyDefaults(true);
        saveConfig();

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }
        dataFile = new File(getDataFolder(), "mace_data.yml");
        logPreviousCount();

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, () -> persist(countAll()));

        startRemoteConsole();
    }

    @Override
    public void onDisable() {
        stopRemoteConsole();
        // Final synchronous save.
        lastPersistedCount = -1;
        persistNow(countAllSafe());
    }

    // ---------------------------------------------------------------------
    // Mace limiter
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (!isMace(stack)) {
            return;
        }

        int allowed = MAX_MACES - countAll();
        if (allowed >= stack.getAmount()) {
            schedulePersist();
            return;
        }

        if (allowed <= 0) {
            event.setCancelled(true);
            item.remove();
        } else {
            ItemStack reduced = stack.clone();
            reduced.setAmount(allowed);
            item.setItemStack(reduced);
        }
        notifyBroken(player);
        schedulePersist();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // The click result is applied after the event; re-check on the next tick.
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                enforce(player);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                enforce(player);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // The quitting player is still "online" during this event; count next tick.
        schedulePersist();
    }

    /** Removes maces from this player's inventory until the global total is within the cap. */
    private void enforce(Player player) {
        int total = countAll();
        int excess = total - MAX_MACES;
        if (excess <= 0) {
            persist(total);
            return;
        }

        int removed = 0;

        // 1) Item on cursor (most likely the item just moved).
        ItemStack cursor = player.getItemOnCursor();
        if (isMace(cursor) && removed < excess) {
            int take = Math.min(excess - removed, cursor.getAmount());
            removed += take;
            if (take >= cursor.getAmount()) {
                player.setItemOnCursor(null);
            } else {
                ItemStack reduced = cursor.clone();
                reduced.setAmount(cursor.getAmount() - take);
                player.setItemOnCursor(reduced);
            }
        }

        // 2) Inventory slots (storage, hotbar, armor, offhand).
        if (removed < excess) {
            PlayerInventory inv = player.getInventory();
            ItemStack[] contents = inv.getContents();
            for (int i = contents.length - 1; i >= 0 && removed < excess; i--) {
                ItemStack s = contents[i];
                if (!isMace(s)) {
                    continue;
                }
                int take = Math.min(excess - removed, s.getAmount());
                removed += take;
                if (take >= s.getAmount()) {
                    contents[i] = null;
                } else {
                    s.setAmount(s.getAmount() - take);
                }
            }
            inv.setContents(contents);
        }

        if (removed > 0) {
            notifyBroken(player);
        }
        persist(countAll());
    }

    private void notifyBroken(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        player.sendMessage(Component.text(WARNING_MESSAGE, NamedTextColor.RED));
    }

    private static boolean isMace(ItemStack stack) {
        return stack != null && stack.getType() == Material.MACE && stack.getAmount() > 0;
    }

    private int countAll() {
        int total = 0;

        // Player inventory, cursor and ender chest.
        for (Player p : Bukkit.getOnlinePlayers()) {
            total += countInventory(p.getInventory());
            total += countItem(p.getItemOnCursor());
            total += countInventory(p.getEnderChest());
        }

        for (World world : Bukkit.getWorlds()) {
            // Dropped maces still exist until they despawn or are picked up.
            for (Item item : world.getEntitiesByClass(Item.class)) {
                total += countItem(item.getItemStack());
            }

            // Scan loaded block inventories: chests, barrels, hoppers,
            // shulkers, furnaces, brewing stands and InventoryHolder-based
            // containers supplied by other plugins (including copper chests).
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities(false)) {
                    if (state instanceof InventoryHolder holder) {
                        total += countInventory(holder.getInventory());
                    }
                }
            }
        }

        return total;
    }

    private int countAllSafe() {
        try {
            return countAll();
        } catch (Exception e) {
            return Math.max(lastPersistedCount, 0);
        }
    }

    private int countPlayer(Player player) {
        return countInventory(player.getInventory())
                + countItem(player.getItemOnCursor())
                + countInventory(player.getEnderChest());
    }

    private int countInventory(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }

        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            total += countItem(stack);
        }
        return total;
    }

    /**
     * Counts a mace directly and recursively searches nested storage items,
     * including shulker boxes and bundles.
     */
    private int countItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return 0;
        }

        int total = isMace(stack) ? stack.getAmount() : 0;

        // Shulker boxes and other block-state inventory items.
        if (stack.getItemMeta() instanceof BlockStateMeta meta) {
            BlockState blockState = meta.getBlockState();
            if (blockState instanceof InventoryHolder holder) {
                total += countInventory(holder.getInventory());
            }
        }

        // Bundle contents. Reflection keeps compatibility with Paper 1.21
        // API variants where BundleMeta's getItems signature may differ.
        try {
            Object meta = stack.getItemMeta();
            if (meta != null) {
                java.lang.reflect.Method method = meta.getClass().getMethod("getItems");
                Object value = method.invoke(meta);
                if (value instanceof Iterable<?> items) {
                    for (Object nested : items) {
                        if (nested instanceof ItemStack nestedStack) {
                            total += countItem(nestedStack);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // ItemMeta does not expose bundle contents on this item/API.
        }

        return total;
    }

    // ---------------------------------------------------------------------
    // Persistence (mace_data.yml)
    // ---------------------------------------------------------------------

    private void schedulePersist() {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, () -> persist(countAll()));
        }
    }

    private void logPreviousCount() {
        if (dataFile.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
            getLogger().info("Last recorded mace count: " + yaml.getInt("mace-count", 0)
                    + " (updated " + yaml.getString("last-updated", "unknown") + ")");
        }
    }

    private void persist(int count) {
        if (count == lastPersistedCount) {
            return;
        }
        lastPersistedCount = count;
        getLogger().info("Global mace count is now " + count + "/" + MAX_MACES);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mace-count", count);
        yaml.set("max-maces", MAX_MACES);
        yaml.set("last-updated", Instant.now().toString());
        String data = yaml.saveToString();

        if (isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> writeFile(data));
        } else {
            writeFile(data);
        }
    }

    private void persistNow(int count) {
        if (count == lastPersistedCount) {
            return;
        }
        lastPersistedCount = count;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mace-count", count);
        yaml.set("max-maces", MAX_MACES);
        yaml.set("last-updated", Instant.now().toString());
        writeFile(yaml.saveToString());
    }

    private void writeFile(String data) {
        synchronized (fileLock) {
            try {
                Files.writeString(dataFile.toPath(), data, StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().warning("Failed to write mace_data.yml: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Remote console wiring
    // ---------------------------------------------------------------------

    private void startRemoteConsole() {
        String bind = getConfig().getString("console.bind-address", "0.0.0.0");
        int port = getConfig().getInt("console.port", 25575);
        String password = getConfig().getString("console.password", DEFAULT_PASSWORD);

        if (DEFAULT_PASSWORD.equals(password)) {
            getLogger().warning("Remote console is using the default password! "
                    + "Change console.password in plugins/MaceLimiter/config.yml.");
        }

        try {
            consoleServer = new RemoteConsoleServer(bind, port, password);
            consoleServer.start();
            getLogger().info("Remote console listening on " + bind + ":" + port);
        } catch (IOException | NoSuchAlgorithmException e) {
            getLogger().severe("Could not start remote console on port " + port + ": " + e.getMessage());
            consoleServer = null;
            return;
        }

        consoleAppender = new ConsoleAppender(consoleServer);
        consoleAppender.start();
        if (LogManager.getRootLogger() instanceof org.apache.logging.log4j.core.Logger core) {
            core.addAppender(consoleAppender);
            coreRootLogger = core;
        } else {
            getLogger().warning("Root logger is not a Log4j core logger; live console streaming disabled.");
        }
    }

    private void stopRemoteConsole() {
        if (coreRootLogger != null && consoleAppender != null) {
            coreRootLogger.removeAppender(consoleAppender);
        }
        if (consoleAppender != null) {
            consoleAppender.stop();
            consoleAppender = null;
        }
        coreRootLogger = null;
        if (consoleServer != null) {
            consoleServer.shutdown();
            consoleServer = null;
        }
    }

    private void dispatchToMainThread(String rawCommand) {
        String command = rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty() || !isEnabled()) {
            return;
        }
        final String toRun = command;
        try {
            Bukkit.getScheduler().runTask(this,
                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun));
        } catch (IllegalStateException e) {
            getLogger().warning("Could not schedule remote command: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Log4j appender -> socket clients
    // ---------------------------------------------------------------------

    private static final class ConsoleAppender extends AbstractAppender {
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
        private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
        private final RemoteConsoleServer server;

        ConsoleAppender(RemoteConsoleServer server) {
            super("MaceLimiterRemoteConsole", null, null, true, Property.EMPTY_ARRAY);
            this.server = server;
        }

        @Override
        public void append(LogEvent event) {
            try {
                String time = TIME.format(LocalTime.ofInstant(
                        Instant.ofEpochMilli(event.getTimeMillis()), ZoneId.systemDefault()));
                StringBuilder sb = new StringBuilder();
                sb.append('[').append(time).append(' ').append(event.getLevel().name()).append("]: ")
                        .append(event.getMessage().getFormattedMessage());
                Throwable thrown = event.getThrown();
                if (thrown != null) {
                    StringWriter sw = new StringWriter();
                    thrown.printStackTrace(new PrintWriter(sw));
                    sb.append('\n').append(sw);
                }
                String text = ANSI.matcher(sb).replaceAll("");
                for (String line : text.split("\\r?\\n")) {
                    server.broadcast(line);
                }
            } catch (Exception ignored) {
                // Never let logging failures propagate into the server's logging pipeline.
            }
        }
    }

    // ---------------------------------------------------------------------
    // TCP server
    // ---------------------------------------------------------------------

    private final class RemoteConsoleServer implements Runnable {
        private final ServerSocket serverSocket;
        private final byte[] passwordHash;
        private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
        private volatile boolean running;

        RemoteConsoleServer(String bindAddress, int port, String password)
                throws IOException, NoSuchAlgorithmException {
            this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName(bindAddress));
            this.passwordHash = sha256(password);
        }

        void start() {
            running = true;
            Thread t = new Thread(this, "MaceLimiter-Console-Accept");
            t.setDaemon(true);
            t.start();
        }

        void shutdown() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            for (ClientSession s : sessions) {
                s.close();
            }
            sessions.clear();
        }

        void broadcast(String line) {
            for (ClientSession s : sessions) {
                s.enqueue(line);
            }
        }

        boolean passwordMatches(String supplied) {
            try {
                return MessageDigest.isEqual(passwordHash, sha256(supplied));
            } catch (NoSuchAlgorithmException e) {
                return false;
            }
        }

        private byte[] sha256(String s) throws NoSuchAlgorithmException {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    if (sessions.size() >= MAX_CLIENTS) {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                        continue;
                    }
                    ClientSession session = new ClientSession(this, socket);
                    Thread t = new Thread(session, "MaceLimiter-Console-Client");
                    t.setDaemon(true);
                    t.start();
                } catch (SocketException e) {
                    if (running) {
                        getLogger().warning("Console accept error: " + e.getMessage());
                    }
                } catch (IOException e) {
                    if (running) {
                        getLogger().warning("Console accept error: " + e.getMessage());
                    }
                }
            }
        }
    }

    private final class ClientSession implements Runnable {
        private final RemoteConsoleServer server;
        private final Socket socket;
        private final LinkedBlockingQueue<String> outbound = new LinkedBlockingQueue<>(5000);

        ClientSession(RemoteConsoleServer server, Socket socket) {
            this.server = server;
            this.socket = socket;
        }

        void enqueue(String line) {
            outbound.offer(line); // drops the line if the client is too slow
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void run() {
            String remote = String.valueOf(socket.getRemoteSocketAddress());
            try {
                socket.setSoTimeout(15_000);
                socket.setKeepAlive(true);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                writeLine(out, "AUTH_REQUIRED");
                String supplied = readBoundedLine(in);
                if (supplied == null || !server.passwordMatches(supplied)) {
                    writeLine(out, "AUTH_FAIL");
                    getLogger().warning("Remote console authentication failed from " + remote);
                    Thread.sleep(1000);
                    return;
                }

                writeLine(out, "AUTH_OK");
                socket.setSoTimeout(0);
                server.sessions.add(this);
                getLogger().info("Remote console client connected: " + remote);
                enqueue("[MaceLimiter] Connected to server console.");

                Thread writer = new Thread(() -> writeLoop(out), "MaceLimiter-Console-Writer");
                writer.setDaemon(true);
                writer.start();

                String line;
                while ((line = readBoundedLine(in)) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    getLogger().info("Remote console (" + remote + ") executed: " + line.strip());
                    dispatchToMainThread(line);
                }
            } catch (SocketTimeoutException e) {
                getLogger().warning("Remote console client timed out during login: " + remote);
            } catch (IOException e) {
                // Client disconnected or protocol violation.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                boolean wasActive = server.sessions.remove(this);
                close();
                if (wasActive) {
                    getLogger().info("Remote console client disconnected: " + remote);
                }
            }
        }

        private void writeLoop(BufferedWriter out) {
            try {
                while (!socket.isClosed()) {
                    String line = outbound.poll(1, TimeUnit.SECONDS);
                    if (line != null) {
                        writeLine(out, line);
                    }
                }
            } catch (IOException e) {
                close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void writeLine(BufferedWriter out, String line) throws IOException {
            out.write(line);
            out.write('\n');
            out.flush();
        }

        /** Reads a line, refusing anything longer than MAX_LINE_LENGTH. Returns null on EOF. */
        private String readBoundedLine(BufferedReader in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    int len = sb.length();
                    if (len > 0 && sb.charAt(len - 1) == '\r') {
                        sb.setLength(len - 1);
                    }
                    return sb.toString();
                }
                sb.append((char) c);
                if (sb.length() > MAX_LINE_LENGTH) {
                    throw new IOException("Line too long");
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
    }
}
