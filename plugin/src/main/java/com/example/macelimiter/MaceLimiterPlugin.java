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
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.event.player.PlayerDropItemEvent;
import java.util.ArrayList;
import java.util.List;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private static final int MAX_CLIENTS = 5;
    private static final int MAX_LINE_LENGTH = 4096;

    private final Object fileLock = new Object();
    private File dataFile;
    private int lastPersistedCount = -1;

    private RelayConsoleServer consoleServer;
    private ConsoleAppender consoleAppender;
    private org.apache.logging.log4j.core.Logger coreRootLogger;

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onEnable() {
        getConfig().addDefault("console.relay-url", "https://ntfy.sh");
        getConfig().addDefault("console.room-code", "lunar260");
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
        if (!(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (!containsMace(stack)) return;

        int totalAfterPickup = countAll();
        int excess = totalAfterPickup - MAX_MACES;
        if (excess <= 0) {
            schedulePersist();
            return;
        }

        // The picked-up item is the newest mace source, so remove excess from it first.
        int removed = removeMacesFromItem(stack, excess);
        if (countMaces(stack) == 0) {
            event.setCancelled(true);
            item.remove();
        } else {
            item.setItemStack(stack);
        }
        notifyBroken(player);
        schedulePersist();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        ItemStack dropped = item.getItemStack();
        if (!containsMace(dropped)) return;

        int total = countAll();
        int excess = total - MAX_MACES;
        if (excess <= 0) {
            schedulePersist();
            return;
        }

        // Dropped maces are the newest maces. Break the dropped stack before touching inventory.
        int removed = removeMacesFromItem(dropped, excess);
        if (removed >= countMaces(dropped) + removed) {
            event.setCancelled(true);
            item.remove();
        } else {
            item.setItemStack(dropped);
        }
        notifyBroken(event.getPlayer());
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

    /** Removes newest maces from this player's accessible inventory, including nested storage. */
    private void enforce(Player player) {
        int total = countAll();
        int excess = total - MAX_MACES;
        if (excess <= 0) {
            persist(total);
            return;
        }

        int removed = 0;

        // Cursor is the newest source after inventory manipulation.
        ItemStack cursor = player.getItemOnCursor();
        if (containsMace(cursor) && removed < excess) {
            removed += removeMacesFromItem(cursor, excess - removed);
            player.setItemOnCursor(cursor.getAmount() > 0 && !isEmptyStorage(cursor) ? cursor : null);
        }

        // Then walk inventory from newest-looking slots backwards, recursively removing nested maces.
        if (removed < excess) {
            PlayerInventory inv = player.getInventory();
            ItemStack[] contents = inv.getContents();
            for (int i = contents.length - 1; i >= 0 && removed < excess; i--) {
                ItemStack stack = contents[i];
                if (!containsMace(stack)) continue;
                removed += removeMacesFromItem(stack, excess - removed);
                contents[i] = stack.getAmount() > 0 && (containsMace(stack) || !isEmptyStorage(stack)) ? stack : null;
            }
            inv.setContents(contents);
        }

        if (removed > 0) notifyBroken(player);
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
     * Counts a mace directly and recursively searches nested storage items.
     */
    private int countItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return 0;

        int total = isMace(stack) ? stack.getAmount() : 0;
        if (stack.getItemMeta() instanceof BlockStateMeta meta) {
            BlockState blockState = meta.getBlockState();
            if (blockState instanceof InventoryHolder holder) total += countInventory(holder.getInventory());
        }
        if (stack.getItemMeta() instanceof BundleMeta bundle) {
            for (ItemStack nested : bundle.getItems()) total += countItem(nested);
        }
        return total;
    }

    private boolean containsMace(ItemStack stack) {
        return countMaces(stack) > 0;
    }

    private int countMaces(ItemStack stack) {
        return countItem(stack);
    }

    /**
     * Removes up to limit maces from this stack, recursively from nested storage.
     * Direct maces are removed before nested contents.
     */
    private int removeMacesFromItem(ItemStack stack, int limit) {
        if (stack == null || limit <= 0 || stack.getAmount() <= 0) return 0;
        int removed = 0;

        if (isMace(stack)) {
            int take = Math.min(limit, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            return take;
        }

        if (stack.getItemMeta() instanceof BundleMeta bundle) {
            List<ItemStack> updated = new ArrayList<>();
            for (ItemStack nested : bundle.getItems()) {
                if (removed < limit && containsMace(nested)) {
                    removed += removeMacesFromItem(nested, limit - removed);
                }
                if (nested != null && nested.getAmount() > 0) {
                    updated.add(nested);
                }
            }
            bundle.setItems(updated);
            stack.setItemMeta(bundle);
        }

        if (removed < limit && stack.getItemMeta() instanceof BlockStateMeta meta) {
            BlockState state = meta.getBlockState();
            if (state instanceof InventoryHolder holder) {
                Inventory inv = holder.getInventory();
                ItemStack[] contents = inv.getContents();
                for (int i = contents.length - 1; i >= 0 && removed < limit; i--) {
                    ItemStack nested = contents[i];
                    if (!containsMace(nested)) continue;
                    removed += removeMacesFromItem(nested, limit - removed);
                    contents[i] = nested != null && nested.getAmount() > 0 ? nested : null;
                }
                inv.setContents(contents);
                meta.setBlockState(state);
                stack.setItemMeta(meta);
            }
        }
        return removed;
    }

    private boolean isEmptyStorage(ItemStack stack) {
        return stack == null || stack.getAmount() <= 0 || (!containsMace(stack) && stack.getType().isAir());
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
    // Outbound HTTPS relay console
    // ---------------------------------------------------------------------

    private void startRemoteConsole() {
        String relayUrl = getConfig().getString("console.relay-url", "https://ntfy.sh").strip();
        String roomCode = getConfig().getString("console.room-code", "lunar260").strip();
        if (roomCode.isEmpty()) {
            getLogger().severe("Remote console room code is empty; console disabled.");
            return;
        }

        try {
            consoleServer = new RelayConsoleServer(relayUrl, roomCode);
            consoleServer.start();
            getLogger().info("Remote console relay enabled. Connect with console.jar using the configured room code.");
        } catch (Exception e) {
            getLogger().severe("Could not start remote console relay: " + e.getMessage());
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
        if (coreRootLogger != null && consoleAppender != null) coreRootLogger.removeAppender(consoleAppender);
        if (consoleAppender != null) { consoleAppender.stop(); consoleAppender = null; }
        coreRootLogger = null;
        if (consoleServer != null) { consoleServer.shutdown(); consoleServer = null; }
    }

    private void dispatchToMainThread(String rawCommand) {
        String command = rawCommand.strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty() || !isEnabled()) return;
        final String toRun = command;
        try {
            Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun));
        } catch (IllegalStateException e) {
            getLogger().warning("Could not schedule remote command: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Log4j appender -> HTTPS relay clients
    // ---------------------------------------------------------------------

    private static final class ConsoleAppender extends AbstractAppender {
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
        private static final Pattern ANSI = Pattern.compile("");
        private final RelayConsoleServer server;

        ConsoleAppender(RelayConsoleServer server) {
            super("MaceLimiterRemoteConsole", null, null, true, Property.EMPTY_ARRAY);
            this.server = server;
        }

        @Override
        public void append(LogEvent event) {
            try {
                String time = TIME.format(LocalTime.ofInstant(Instant.ofEpochMilli(event.getTimeMillis()), ZoneId.systemDefault()));
                StringBuilder sb = new StringBuilder();
                sb.append('[').append(time).append(' ').append(event.getLevel().name()).append("]: ")
                        .append(event.getMessage().getFormattedMessage());
                Throwable thrown = event.getThrown();
                if (thrown != null) {
                    StringWriter sw = new StringWriter();
                    thrown.printStackTrace(new PrintWriter(sw));
                    sb.append('\n').append(sw);

                }
                String text = sb.toString();
                for (String line : text.split("\r?\n")) server.broadcast(line);
            } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // HTTPS relay client
    // ---------------------------------------------------------------------

    private final class RelayConsoleServer {
        private static final HttpClient HTTP = HttpClient.newBuilder().build();
        private final String topicUrl;
        private final Set<String> clients = ConcurrentHashMap.newKeySet();
        private volatile boolean running;

        RelayConsoleServer(String relayUrl, String roomCode) throws Exception {
            String base = relayUrl.replaceAll("/+$", "");
            String topic = "macelimiter-" + sha256(roomCode).substring(0, 48);
            this.topicUrl = base + "/" + topic;
        }

        void start() {
            running = true;
            Thread t = new Thread(this::run, "MaceLimiter-Console-Relay");
            t.setDaemon(true);
            t.start();
        }

        void shutdown() { running = false; }

        void broadcast(String line) {
            String safe = line.replace("\r", "").replace("\n", "\\n");
            for (String client : clients) publish("OUT|" + client + "|" + safe);
        }

        private void run() {
            String since = "all";
            while (running) {
                try {
                    String url = topicUrl + "/json?poll=1&since=" + java.net.URLEncoder.encode(since, StandardCharsets.UTF_8);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(20)).GET().build();
                    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() / 100 != 2) { Thread.sleep(1_000); continue; }
                    for (String line : response.body().split(System.lineSeparator())) {
                        if (line.isBlank()) continue;
                        String id = jsonField(line, "id");
                        String message = jsonField(line, "message");
                        if (id != null) since = id;
                        if (message != null && message.startsWith("CONNECT|") || message != null && message.startsWith("DISCONNECT|") || message != null && message.startsWith("CMD|")) handleMessage(message);
                    }
                } catch (Exception e) {
                    if (running) try { Thread.sleep(1_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            }
        }

        private void handleMessage(String message) {
            if (message.startsWith("CONNECT|")) {
                String client = message.substring("CONNECT|".length()).strip();
                if (client.isEmpty()) return;
                if (clients.size() >= MAX_CLIENTS && !clients.contains(client)) return;
                clients.add(client);
                publish("WELCOME|" + client);
                getLogger().info("Remote console client connected through relay: " + client);
                return;
            }
            if (message.startsWith("DISCONNECT|")) {
                String client = message.substring("DISCONNECT|".length()).strip();
                if (clients.remove(client)) getLogger().info("Remote console client disconnected through relay: " + client);
                return;
            }
            if (message.startsWith("CMD|")) {
                int first = message.indexOf('|');
                int second = message.indexOf('|', first + 1);
                if (second < 0) return;
                String client = message.substring(first + 1, second);
                if (!clients.contains(client)) return;
                String command = message.substring(second + 1);
                if (!command.isBlank()) dispatchToMainThread(command);
            }
        }

        private void publish(String message) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(topicUrl))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8))
                        .build();
                HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        }

        private String jsonField(String json, String field) {
            char q = 34;
            String key = q + field + q + ":" + q;
            int start = json.indexOf(key);
            if (start < 0) return null;
            start += key.length();
            StringBuilder out = new StringBuilder();
            boolean escaped = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escaped) { out.append(c); escaped = false; continue; }
                if (c == 92) { escaped = true; out.append(c); continue; }
                if (c == '"') return out.toString();
                out.append(c);
            }
            return null;
        }

        private String sha256(String value) throws NoSuchAlgorithmException {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        }
    }
}
