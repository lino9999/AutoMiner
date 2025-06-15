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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class AutoMiner extends JavaPlugin implements Listener {

    private final Map<Location, MinerData> activeMachines = new HashMap<>();
    private final Map<UUID, Integer> playerMinerCount = new HashMap<>();
    private BukkitRunnable machineTask;
    private Connection database;
    private int maxMinersPerPlayer;
    private int miningSpeed;

    private static class MinerData {
        ArmorStand titleHolo;
        ArmorStand statsHolo;
        int blocksDestroyed;
        UUID owner;

        MinerData(ArmorStand title, ArmorStand stats, UUID owner) {
            this.titleHolo = title;
            this.statsHolo = stats;
            this.blocksDestroyed = 0;
            this.owner = owner;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        initDatabase();
        getServer().getPluginManager().registerEvents(this, this);
        loadMiners();
        startMachineTask();
    }

    @Override
    public void onDisable() {
        if (machineTask != null) machineTask.cancel();
        saveAllMiners();
        activeMachines.values().forEach(data -> {
            data.titleHolo.remove();
            data.statsHolo.remove();
        });
        activeMachines.clear();
        try {
            if (database != null && !database.isClosed()) database.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to close database: " + e.getMessage());
        }
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        maxMinersPerPlayer = config.getInt("max-miners-per-player", 5);
        miningSpeed = config.getInt("mining-speed-ticks", 20);
    }

    private void initDatabase() {
        try {
            File dbFile = new File(getDataFolder(), "miners.db");
            database = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            Statement stmt = database.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS miners (" +
                    "world TEXT NOT NULL," +
                    "x INTEGER NOT NULL," +
                    "y INTEGER NOT NULL," +
                    "z INTEGER NOT NULL," +
                    "owner TEXT NOT NULL," +
                    "blocks INTEGER DEFAULT 0," +
                    "PRIMARY KEY (world, x, y, z)" +
                    ")");
            stmt.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        ItemStack rod = new ItemStack(Material.END_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.setDisplayName("§6AutoMiner");
        meta.setLore(java.util.Arrays.asList("§7Place to start mining"));
        rod.setItemMeta(meta);

        player.getInventory().addItem(rod);
        player.sendMessage("§aYou received an AutoMiner!");
        return true;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.END_ROD || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (!"§6AutoMiner".equals(meta.getDisplayName())) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        int currentCount = playerMinerCount.getOrDefault(playerId, 0);

        if (currentCount >= maxMinersPerPlayer) {
            event.setCancelled(true);
            player.sendMessage("§cYou have reached the maximum number of miners (" + maxMinersPerPlayer + ")!");
            return;
        }

        Location loc = event.getBlock().getLocation();

        ArmorStand titleHolo = createHologram(loc.clone().add(0.5, 1.3, 0.5), "§6§lAutoMiner");
        ArmorStand statsHolo = createHologram(loc.clone().add(0.5, 1, 0.5), "§eBlocks: §f0");

        MinerData data = new MinerData(titleHolo, statsHolo, playerId);
        activeMachines.put(loc, data);
        playerMinerCount.put(playerId, currentCount + 1);

        saveMiner(loc, data);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
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

        int currentCount = playerMinerCount.getOrDefault(data.owner, 1);
        playerMinerCount.put(data.owner, Math.max(0, currentCount - 1));

        deleteMiner(loc);
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

    private void startMachineTask() {
        machineTask = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick % miningSpeed != 0) {
                    tick++;
                    return;
                }
                tick++;

                Iterator<Map.Entry<Location, MinerData>> it = activeMachines.entrySet().iterator();

                while (it.hasNext()) {
                    Map.Entry<Location, MinerData> entry = it.next();
                    Location loc = entry.getKey();
                    MinerData data = entry.getValue();

                    if (!loc.getChunk().isLoaded() || !data.titleHolo.isValid()) {
                        data.titleHolo.remove();
                        data.statsHolo.remove();
                        it.remove();
                        continue;
                    }

                    Block machine = loc.getBlock();
                    if (machine.getType() != Material.END_ROD) {
                        data.titleHolo.remove();
                        data.statsHolo.remove();
                        it.remove();
                        continue;
                    }

                    BlockFace facing = ((Directional) machine.getBlockData()).getFacing();
                    Block target = machine.getRelative(facing);

                    if (target.getType() != Material.AIR && target.getType() != Material.BEDROCK) {
                        target.breakNaturally();
                        loc.getWorld().playSound(loc, Sound.BLOCK_STONE_BREAK, 0.7f, 0.5f);
                        loc.getWorld().playSound(loc, Sound.BLOCK_GRINDSTONE_USE, 0.5f, 1.2f);

                        data.blocksDestroyed++;
                        data.statsHolo.setCustomName("§eBlocks: §f" + data.blocksDestroyed);

                        if (data.blocksDestroyed % 10 == 0) updateMinerBlocks(loc, data.blocksDestroyed);
                    }
                }
            }
        };

        machineTask.runTaskTimer(this, 0L, 1L);
    }

    private void saveMiner(Location loc, MinerData data) {
        try {
            PreparedStatement stmt = database.prepareStatement(
                    "INSERT OR REPLACE INTO miners (world, x, y, z, owner, blocks) VALUES (?, ?, ?, ?, ?, ?)"
            );
            stmt.setString(1, loc.getWorld().getName());
            stmt.setInt(2, loc.getBlockX());
            stmt.setInt(3, loc.getBlockY());
            stmt.setInt(4, loc.getBlockZ());
            stmt.setString(5, data.owner.toString());
            stmt.setInt(6, data.blocksDestroyed);
            stmt.executeUpdate();
            stmt.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to save miner: " + e.getMessage());
        }
    }

    private void updateMinerBlocks(Location loc, int blocks) {
        try {
            PreparedStatement stmt = database.prepareStatement(
                    "UPDATE miners SET blocks = ? WHERE world = ? AND x = ? AND y = ? AND z = ?"
            );
            stmt.setInt(1, blocks);
            stmt.setString(2, loc.getWorld().getName());
            stmt.setInt(3, loc.getBlockX());
            stmt.setInt(4, loc.getBlockY());
            stmt.setInt(5, loc.getBlockZ());
            stmt.executeUpdate();
            stmt.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to update miner blocks: " + e.getMessage());
        }
    }

    private void deleteMiner(Location loc) {
        try {
            PreparedStatement stmt = database.prepareStatement(
                    "DELETE FROM miners WHERE world = ? AND x = ? AND y = ? AND z = ?"
            );
            stmt.setString(1, loc.getWorld().getName());
            stmt.setInt(2, loc.getBlockX());
            stmt.setInt(3, loc.getBlockY());
            stmt.setInt(4, loc.getBlockZ());
            stmt.executeUpdate();
            stmt.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to delete miner: " + e.getMessage());
        }
    }

    private void saveAllMiners() {
        activeMachines.forEach(this::saveMiner);
    }

    private void loadMiners() {
        try {
            Statement stmt = database.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM miners");

            while (rs.next()) {
                Location loc = new Location(
                        Bukkit.getWorld(rs.getString("world")),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z")
                );

                if (loc.getWorld() == null || loc.getBlock().getType() != Material.END_ROD) continue;

                UUID owner = UUID.fromString(rs.getString("owner"));

                ArmorStand titleHolo = createHologram(loc.clone().add(0.5, 1.3, 0.5), "§6§lAutoMiner");
                ArmorStand statsHolo = createHologram(loc.clone().add(0.5, 1, 0.5), "§eBlocks: §f0");

                MinerData data = new MinerData(titleHolo, statsHolo, owner);
                data.blocksDestroyed = rs.getInt("blocks");
                data.statsHolo.setCustomName("§eBlocks: §f" + data.blocksDestroyed);

                activeMachines.put(loc, data);

                int currentCount = playerMinerCount.getOrDefault(owner, 0);
                playerMinerCount.put(owner, currentCount + 1);
            }

            rs.close();
            stmt.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to load miners: " + e.getMessage());
        }
    }
}