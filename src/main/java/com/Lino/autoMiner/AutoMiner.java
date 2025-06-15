package com.Lino.autoMiner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AutoMiner extends JavaPlugin implements Listener, TabCompleter {

    private final Map<Location, MinerData> activeMachines = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerMinerCount = new ConcurrentHashMap<>();
    private final Set<Location> pendingUpdates = Collections.synchronizedSet(new HashSet<>());
    private BukkitRunnable machineTask;
    private BukkitRunnable saveTask;
    private Connection database;
    private PreparedStatement insertStmt;
    private PreparedStatement updateStmt;
    private PreparedStatement deleteStmt;
    private int maxMinersPerPlayer;
    private int miningSpeed;

    private static class MinerData {
        final ArmorStand titleHolo;
        final ArmorStand statsHolo;
        int blocksDestroyed;
        final UUID owner;
        boolean needsUpdate;

        MinerData(ArmorStand title, ArmorStand stats, UUID owner) {
            this.titleHolo = title;
            this.statsHolo = stats;
            this.blocksDestroyed = 0;
            this.owner = owner;
            this.needsUpdate = false;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        initDatabase();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("autominer").setTabCompleter(this);
        loadMiners();
        startTasks();
        getLogger().info("AutoMiner enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (machineTask != null) machineTask.cancel();
        if (saveTask != null) saveTask.cancel();
        saveAllMiners();
        activeMachines.values().forEach(data -> {
            if (data.titleHolo.isValid()) data.titleHolo.remove();
            if (data.statsHolo.isValid()) data.statsHolo.remove();
        });
        activeMachines.clear();
        try {
            if (insertStmt != null) insertStmt.close();
            if (updateStmt != null) updateStmt.close();
            if (deleteStmt != null) deleteStmt.close();
            if (database != null && !database.isClosed()) database.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to close database: " + e.getMessage());
        }
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        maxMinersPerPlayer = config.getInt("max-miners-per-player", 5);
        miningSpeed = Math.max(1, config.getInt("mining-speed-ticks", 20));
    }

    private void initDatabase() {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }

            File dbFile = new File(getDataFolder(), "miners.db");
            database = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            database.setAutoCommit(false);

            try (Statement stmt = database.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS miners (" +
                        "world TEXT NOT NULL," +
                        "x INTEGER NOT NULL," +
                        "y INTEGER NOT NULL," +
                        "z INTEGER NOT NULL," +
                        "owner TEXT NOT NULL," +
                        "blocks INTEGER DEFAULT 0," +
                        "PRIMARY KEY (world, x, y, z)" +
                        ")");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_owner ON miners(owner)");
            }
            database.commit();

            insertStmt = database.prepareStatement(
                    "INSERT OR REPLACE INTO miners (world, x, y, z, owner, blocks) VALUES (?, ?, ?, ?, ?, ?)"
            );
            updateStmt = database.prepareStatement(
                    "UPDATE miners SET blocks = ? WHERE world = ? AND x = ? AND y = ? AND z = ?"
            );
            deleteStmt = database.prepareStatement(
                    "DELETE FROM miners WHERE world = ? AND x = ? AND y = ? AND z = ?"
            );
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /autominer <give|reload>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give":
                if (!sender.hasPermission("autominer.give")) {
                    sender.sendMessage("§cYou don't have permission to use this command!");
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /autominer give <player> <amount>");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found!");
                    return true;
                }

                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                    if (amount < 1 || amount > 64) {
                        sender.sendMessage("§cAmount must be between 1 and 64!");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid amount!");
                    return true;
                }

                ItemStack rod = new ItemStack(Material.END_ROD, amount);
                ItemMeta meta = rod.getItemMeta();
                meta.setDisplayName("§6AutoMiner");
                meta.setLore(Arrays.asList("§7Place to start mining"));
                rod.setItemMeta(meta);

                target.getInventory().addItem(rod);
                sender.sendMessage("§aGave " + amount + " AutoMiner(s) to " + target.getName() + "!");
                if (!sender.equals(target)) {
                    target.sendMessage("§aYou received " + amount + " AutoMiner(s)!");
                }
                break;

            case "reload":
                if (!sender.hasPermission("autominer.reload")) {
                    sender.sendMessage("§cYou don't have permission to use this command!");
                    return true;
                }

                reloadConfig();
                loadConfig();
                sender.sendMessage("§aAutoMiner configuration reloaded!");
                break;

            default:
                sender.sendMessage("§cUnknown subcommand. Use: /autominer <give|reload>");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("give", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return Arrays.asList("1", "8", "16", "32", "64");
        }

        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;

        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.END_ROD || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (!"§6AutoMiner".equals(meta.getDisplayName())) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        int currentCount = playerMinerCount.getOrDefault(playerId, 0);

        if (currentCount >= maxMinersPerPlayer && !player.hasPermission("autominer.bypass")) {
            event.setCancelled(true);
            player.sendMessage("§cYou have reached the maximum number of miners (" + maxMinersPerPlayer + ")!");
            return;
        }

        Location loc = event.getBlock().getLocation();

        ArmorStand titleHolo = createHologram(loc.clone().add(0.5, 1.3, 0.5), "§6§lAutoMiner");
        ArmorStand statsHolo = createHologram(loc.clone().add(0.5, 1, 0.5), "§eBlocks: §f0");

        MinerData data = new MinerData(titleHolo, statsHolo, playerId);
        activeMachines.put(loc, data);
        playerMinerCount.merge(playerId, 1, Integer::sum);

        saveMiner(loc, data);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Location loc = event.getBlock().getLocation();
        MinerData data = activeMachines.get(loc);

        if (data == null) return;

        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(data.owner) && !player.hasPermission("autominer.admin")) {
            event.setCancelled(true);
            player.sendMessage("§cYou can only break your own miners!");
            return;
        }

        data.titleHolo.remove();
        data.statsHolo.remove();
        activeMachines.remove(loc);

        playerMinerCount.computeIfPresent(data.owner, (k, v) -> v > 1 ? v - 1 : null);

        deleteMiner(loc);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        activeMachines.entrySet().stream()
                .filter(e -> e.getKey().getChunk().equals(event.getChunk()))
                .forEach(e -> {
                    if (e.getValue().needsUpdate) {
                        pendingUpdates.add(e.getKey());
                    }
                });
    }

    private ArmorStand createHologram(Location loc, String text) {
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSmall(true);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.setMarker(true);
        return stand;
    }

    private void startTasks() {
        machineTask = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick++ % miningSpeed != 0) return;

                Iterator<Map.Entry<Location, MinerData>> it = activeMachines.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Location, MinerData> entry = it.next();
                    Location loc = entry.getKey();
                    MinerData data = entry.getValue();

                    if (!loc.getChunk().isLoaded()) continue;

                    if (!data.titleHolo.isValid() || loc.getBlock().getType() != Material.END_ROD) {
                        data.titleHolo.remove();
                        data.statsHolo.remove();
                        it.remove();
                        playerMinerCount.computeIfPresent(data.owner, (k, v) -> v > 1 ? v - 1 : null);
                        deleteMiner(loc);
                        continue;
                    }

                    Block machine = loc.getBlock();
                    BlockFace facing = ((Directional) machine.getBlockData()).getFacing();
                    Block target = machine.getRelative(facing);

                    if (target.getType() != Material.AIR && target.getType() != Material.BEDROCK) {
                        target.breakNaturally();
                        loc.getWorld().playSound(loc, Sound.BLOCK_STONE_BREAK, 0.7f, 0.5f);
                        loc.getWorld().playSound(loc, Sound.BLOCK_GRINDSTONE_USE, 0.5f, 1.2f);

                        data.blocksDestroyed++;
                        data.statsHolo.setCustomName("§eBlocks: §f" + data.blocksDestroyed);
                        data.needsUpdate = true;
                    }
                }
            }
        };
        machineTask.runTaskTimer(this, 0L, 1L);

        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveUpdatedMiners();
            }
        };
        saveTask.runTaskTimerAsynchronously(this, 200L, 200L);
    }

    private void saveMiner(Location loc, MinerData data) {
        if (insertStmt == null || database == null) return;

        try {
            insertStmt.setString(1, loc.getWorld().getName());
            insertStmt.setInt(2, loc.getBlockX());
            insertStmt.setInt(3, loc.getBlockY());
            insertStmt.setInt(4, loc.getBlockZ());
            insertStmt.setString(5, data.owner.toString());
            insertStmt.setInt(6, data.blocksDestroyed);
            insertStmt.executeUpdate();
            database.commit();
        } catch (SQLException e) {
            getLogger().severe("Failed to save miner: " + e.getMessage());
        }
    }

    private void deleteMiner(Location loc) {
        if (deleteStmt == null || database == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                deleteStmt.setString(1, loc.getWorld().getName());
                deleteStmt.setInt(2, loc.getBlockX());
                deleteStmt.setInt(3, loc.getBlockY());
                deleteStmt.setInt(4, loc.getBlockZ());
                deleteStmt.executeUpdate();
                database.commit();
            } catch (SQLException e) {
                getLogger().severe("Failed to delete miner: " + e.getMessage());
            }
        });
    }

    private void saveUpdatedMiners() {
        if (activeMachines.isEmpty()) return;

        try {
            for (Location loc : new ArrayList<>(pendingUpdates)) {
                MinerData data = activeMachines.get(loc);
                if (data != null && data.needsUpdate) {
                    updateStmt.setInt(1, data.blocksDestroyed);
                    updateStmt.setString(2, loc.getWorld().getName());
                    updateStmt.setInt(3, loc.getBlockX());
                    updateStmt.setInt(4, loc.getBlockY());
                    updateStmt.setInt(5, loc.getBlockZ());
                    updateStmt.addBatch();
                    data.needsUpdate = false;
                }
            }
            pendingUpdates.clear();

            for (MinerData data : activeMachines.values()) {
                if (data.needsUpdate) {
                    data.needsUpdate = false;
                }
            }

            if (updateStmt != null) {
                updateStmt.executeBatch();
                database.commit();
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to save updated miners: " + e.getMessage());
        }
    }

    private void saveAllMiners() {
        activeMachines.forEach(this::saveMiner);
    }

    private void loadMiners() {
        new BukkitRunnable() {
            @Override
            public void run() {
                int loaded = 0;
                try (Statement stmt = database.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM miners")) {

                    while (rs.next()) {
                        String worldName = rs.getString("world");
                        if (Bukkit.getWorld(worldName) == null) continue;

                        Location loc = new Location(
                                Bukkit.getWorld(worldName),
                                rs.getInt("x"),
                                rs.getInt("y"),
                                rs.getInt("z")
                        );

                        UUID owner = UUID.fromString(rs.getString("owner"));
                        int blocks = rs.getInt("blocks");
                        loaded++;

                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (!loc.getChunk().isLoaded()) {
                                    loc.getChunk().load();
                                }

                                if (loc.getBlock().getType() != Material.END_ROD) {
                                    deleteMiner(loc);
                                    return;
                                }

                                ArmorStand titleHolo = createHologram(loc.clone().add(0.5, 1.3, 0.5), "§6§lAutoMiner");
                                ArmorStand statsHolo = createHologram(loc.clone().add(0.5, 1, 0.5), "§eBlocks: §f" + blocks);

                                MinerData data = new MinerData(titleHolo, statsHolo, owner);
                                data.blocksDestroyed = blocks;

                                activeMachines.put(loc, data);
                                playerMinerCount.merge(owner, 1, Integer::sum);
                            }
                        }.runTask(AutoMiner.this);
                    }

                    if (loaded > 0) {
                        getLogger().info("Loaded " + loaded + " AutoMiners from database!");
                    }
                } catch (SQLException e) {
                    getLogger().severe("Failed to load miners: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskLater(this, 20L);
    }
}