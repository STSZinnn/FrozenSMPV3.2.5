package com.example.punchsnowball;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Stray;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Frost event subsystem. One active server-wide event at a time.
 * Runs entirely on the server's main thread; event-owned entities/blocks are
 * tagged or tracked and removed when the event ends or the plugin disables.
 */
final class FrostEvents implements Listener {
    private final PunchSnowballPlugin plugin;
    private final NamespacedKey relicMarker;
    private final Random random = new Random();
    private final Map<UUID, Setup> adminSetups = new HashMap<>();
    private Active active;
    private long generation;

    private static final LinkedHashMap<String, String> TYPES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> REWARDS = new LinkedHashMap<>();
    static {
        TYPES.put("meteor", "Frozen Meteor");
        TYPES.put("koth", "King of the Hill");
        TYPES.put("chosen", "The Chosen One");
        TYPES.put("titan", "Frost Titan");
        TYPES.put("treasure", "Treasure Hunt");
        TYPES.put("assassin", "Assassin Hunt");

        REWARDS.put("sword", "Frozen Sword");
        REWARDS.put("battleaxe", "Frozen Battleaxe");
        REWARDS.put("spear", "Frozen Spear");
        REWARDS.put("bow", "Frozen Bow");
        REWARDS.put("crossbow", "Frozen Crossbow");
        REWARDS.put("shield", "Frozen Shield");
        REWARDS.put("grapple", "Loyalty Trident");
        REWARDS.put("riptide", "Riptide Trident");
        REWARDS.put("pickaxe", "Frozen Pickaxe");
        REWARDS.put("snowball", "Frozen Snowball");
        REWARDS.put("tnt", "Frozen TNT");
        REWARDS.put("windcharge", "Frozen Wind Charge");
        REWARDS.put("firework", "Frozen Firework");
        REWARDS.put("lava", "Frozen Flint and Steel");
        REWARDS.put("totem", "Frozen Totem");
        REWARDS.put("rod", "Frozen Fishing Rod");
        REWARDS.put("horn", "Frozen Horn");
        REWARDS.put("helmet", "Frozen Helmet");
        REWARDS.put("chestplate", "Frozen Chestplate");
        REWARDS.put("leggings", "Frozen Leggings");
        REWARDS.put("boots", "Frozen Boots");
    }

    private static final int[] TYPE_SLOTS = {10, 12, 14, 28, 30, 32};
    private static final int[] REWARD_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private static class Setup {
        String type = "meteor";
        String reward = "sword";
        int minutes = 20;
    }

    private static class EventMenu implements InventoryHolder {
        final String view;
        final Inventory inventory;
        EventMenu(String view) {
            this.view = view;
            inventory = Bukkit.createInventory(this, 54,
                    Component.text(view.equals("rewards") ? "Frost Events - Rewards" : "Frost Events - Setup"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static class Active {
        final String type;
        final String reward;
        final World world;
        final UUID token = UUID.randomUUID();
        final int totalSeconds;
        int seconds;
        final BossBar bar;
        Location origin;
        Location objective;
        Block objectiveBlock;
        BlockData oldBlockData;
        // All impact edits are tracked and restored when the event ends.
        final Map<Block, BlockData> impactOriginal = new LinkedHashMap<>();
        final Map<Block, BlockData> impactPlaced = new LinkedHashMap<>();
        // Ticket the entire meteor flight corridor so the falling visuals and impact
        // still run even if no players are close enough to load its chunks.
        final Set<Long> meteorChunkTickets = new LinkedHashSet<>();
        final List<Entity> visuals = new ArrayList<>();
        final Map<UUID, Integer> points = new HashMap<>();
        final Map<UUID, UUID> targets = new HashMap<>();
        final Map<UUID, Double> bossDamage = new HashMap<>();
        UUID chosenHolder;
        UUID lastHolder;
        Warden titan;
        double titanRaidHealth;
        double titanMaxRaidHealth;
        int titanNextAbility = 6;
        int titanAttackIndex;
        long titanMovementStep;
        long titanNextMeleeStep;
        int titanStrafeDirection = 1;
        long titanLastSuccessfulStep;
        boolean titanDefeated;
        boolean meteorLanded;
        boolean enraged;
        long serial;

        Active(String type, String reward, World world, int duration, BossBar bar) {
            this.type = type;
            this.reward = reward;
            this.world = world;
            this.totalSeconds = duration;
            this.bar = bar;
        }
    }

    FrostEvents(PunchSnowballPlugin plugin) {
        this.plugin = plugin;
        this.relicMarker = NamespacedKey.fromString("punchsnowball:frost_event_relic");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        // A Warden with AI disabled does not walk in response to ordinary velocity.
        // Drive reliable, collision-checked positional steps 10x/second instead.
        // Its normal AI, sonic boom and uncapped melee remain disabled.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickTitanMovement, 2L, 2L);
    }

    private String label(String type) { return TYPES.getOrDefault(type, type); }
    private String rewardName(String reward) { return REWARDS.getOrDefault(reward, reward); }
    private int configuredInt(String key, int fallback) { return plugin.getConfig().getInt("events." + key, fallback); }
    private double configuredDouble(String key, double fallback) { return plugin.getConfig().getDouble("events." + key, fallback); }
    private String prefix() { return ChatColor.AQUA + "[Frost Event] " + ChatColor.RESET; }
    private void announce(String message) { Bukkit.broadcastMessage(prefix() + message); }

    private static String normalize(String s) { return s.toLowerCase(Locale.ROOT).replace("_", "").replace("-", ""); }
    private String findType(String input) {
        String name = normalize(input);
        if (name.equals("frosttitan") || name.equals("boss")) return "titan";
        if (name.equals("kingofthehill")) return "koth";
        if (name.equals("treasurehunt")) return "treasure";
        if (name.equals("frozenmeteor")) return "meteor";
        if (name.equals("chosenone")) return "chosen";
        if (name.equals("assassinhunt")) return "assassin";
        return TYPES.containsKey(name) ? name : null;
    }
    private String findReward(String input) {
        String name = normalize(input);
        for (String key : REWARDS.keySet()) {
            if (name.equals(key) || name.equals("frost" + key) || name.equals("frozen" + key)) return key;
        }
        if (name.equals("frozentrident")) return "grapple";
        if (name.equals("frosttridentdepths")) return "riptide";
        if (name.equals("frostpickaxe")) return "pickaxe";
        return null;
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission("frost.event")) {
                sender.sendMessage(prefix() + ChatColor.RED + "Only event administrators can open this menu."); return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(prefix() + "Use /frost event <type> <reward> [minutes] from console."); return true;
            }
            openSetup(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("status")) {
            status(sender);
            return true;
        }
        if (!sender.hasPermission("frost.event")) {
            sender.sendMessage(prefix() + ChatColor.RED + "No permission."); return true;
        }
        if (args[0].equalsIgnoreCase("stop") || args[0].equalsIgnoreCase("cancel")) {
            if (active == null) sender.sendMessage(prefix() + "There is no active event.");
            else { announce("Event stopped by an administrator."); closeEvent(); }
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(prefix() + "Usage: /frost event <meteor|koth|chosen|titan|treasure|assassin> <frost item> [minutes]");
            sender.sendMessage(prefix() + "Or open /frost event for the GUI.");
            return true;
        }
        String type = findType(args[0]);
        String reward = findReward(args[1]);
        if (type == null || reward == null) {
            sender.sendMessage(prefix() + ChatColor.RED + "Unknown event or reward. Use /frost event for the GUI."); return true;
        }
        int minutes = configuredInt("default-duration-minutes", 20);
        if (args.length >= 3) {
            try {
                String raw = args[2].toLowerCase(Locale.ROOT).replace("minutes", "").replace("min", "").replace("m", "");
                minutes = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                sender.sendMessage(prefix() + "Duration example: 20m"); return true;
            }
        }
        start(sender, type, reward, minutes);
        return true;
    }

    List<String> tabComplete(String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(TYPES.keySet());
            suggestions.add("status"); suggestions.add("stop");
            return suggestions.stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("status") && !args[0].equalsIgnoreCase("stop"))
            return REWARDS.keySet().stream().map(v -> "frost" + v)
                    .filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3) return List.of("10m", "15m", "20m", "30m", "45m");
        return List.of();
    }

    private Setup getSetup(Player player) {
        return adminSetups.computeIfAbsent(player.getUniqueId(), unused -> {
            Setup setup = new Setup();
            setup.minutes = configuredInt("default-duration-minutes", 20);
            return setup;
        });
    }
    private ItemStack icon(Material type, String title, String... lore) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + title);
        if (lore.length > 0) meta.setLore(Arrays.stream(lore).map(v -> ChatColor.GRAY + v).toList());
        stack.setItemMeta(meta);
        return stack;
    }
    private void fill(Inventory inv) {
        ItemStack background = icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, background);
    }
    private Material typeIcon(String type) {
        return switch (type) {
            case "meteor" -> Material.BLUE_ICE;
            case "koth" -> Material.BEACON;
            case "chosen" -> Material.HEART_OF_THE_SEA;
            case "titan" -> Material.WARDEN_SPAWN_EGG;
            case "treasure" -> Material.CHEST;
            case "assassin" -> Material.IRON_SWORD;
            default -> Material.SNOW_BLOCK;
        };
    }
    private void openSetup(Player player) {
        Setup setup = getSetup(player);
        EventMenu menu = new EventMenu("setup");
        fill(menu.inventory);
        int idx = 0;
        for (String type : TYPES.keySet()) {
            String chosen = setup.type.equals(type) ? ChatColor.GREEN + "Selected" : ChatColor.GRAY + "Click to select";
            menu.inventory.setItem(TYPE_SLOTS[idx++], icon(typeIcon(type), label(type), chosen));
        }
        menu.inventory.setItem(38, icon(Material.NETHER_STAR, "Reward: " + rewardName(setup.reward), "Click to select any Frost item"));
        menu.inventory.setItem(40, icon(Material.CLOCK, "Duration: " + setup.minutes + " minutes", "Left click: next duration", "Right click: previous duration"));
        menu.inventory.setItem(42, icon(Material.EMERALD_BLOCK, "START EVENT", "Event: " + label(setup.type), "Prize: " + rewardName(setup.reward)));
        menu.inventory.setItem(44, icon(Material.BARRIER, "STOP ACTIVE EVENT", "Immediately end the current event"));
        menu.inventory.setItem(49, icon(Material.BOOK, "Event status", active == null ? "No event active" : label(active.type) + " is active"));
        player.openInventory(menu.inventory);
    }
    private void openRewards(Player player) {
        EventMenu menu = new EventMenu("rewards");
        fill(menu.inventory);
        int idx = 0;
        for (String group : REWARDS.keySet()) {
            ItemStack item = plugin.frostEventReward(group);
            if (item == null) item = icon(Material.SNOW_BLOCK, rewardName(group));
            else {
                item = item.clone();
                ItemMeta meta = item.getItemMeta();
                List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
                lore.add(ChatColor.GOLD + "Click to choose this reward");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            menu.inventory.setItem(REWARD_SLOTS[idx++], item);
        }
        menu.inventory.setItem(49, icon(Material.ARROW, "Back to event setup"));
        player.openInventory(menu.inventory);
    }
    private static final int[] DURATIONS = {5, 10, 15, 20, 30, 45, 60};
    @EventHandler(ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EventMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("frost.event")) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        Setup setup = getSetup(player);
        if (menu.view.equals("rewards")) {
            for (int i = 0; i < REWARD_SLOTS.length; i++) {
                if (slot != REWARD_SLOTS[i]) continue;
                setup.reward = new ArrayList<>(REWARDS.keySet()).get(i);
                openSetup(player);
                return;
            }
            if (slot == 49) openSetup(player);
            return;
        }
        for (int i = 0; i < TYPE_SLOTS.length; i++) {
            if (slot == TYPE_SLOTS[i]) {
                setup.type = new ArrayList<>(TYPES.keySet()).get(i);
                openSetup(player);
                return;
            }
        }
        if (slot == 38) { openRewards(player); return; }
        if (slot == 40) {
            int index = 0;
            for (int i = 0; i < DURATIONS.length; i++) if (DURATIONS[i] == setup.minutes) index = i;
            index = (index + (event.isRightClick() ? DURATIONS.length - 1 : 1)) % DURATIONS.length;
            setup.minutes = DURATIONS[index];
            openSetup(player);
            return;
        }
        if (slot == 42) {
            player.closeInventory();
            start(player, setup.type, setup.reward, setup.minutes);
            return;
        }
        if (slot == 44) {
            player.closeInventory();
            if (active != null) { announce("Event stopped by an administrator."); closeEvent(); }
            else player.sendMessage(prefix() + "No active event.");
            return;
        }
        if (slot == 49) { player.closeInventory(); status(player); }
    }
    @EventHandler(ignoreCancelled = true)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EventMenu) { event.setCancelled(true); return; }
        if (active == null || !active.type.equals("chosen")) return;
        int size = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(raw -> raw < size)
                && event.getNewItems().values().stream().anyMatch(item -> isRelic(item, active)))
            event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void protectChosenRelicInStorage(InventoryClickEvent event) {
        if (active == null || !active.type.equals("chosen")) return;
        if (event.getView().getTopInventory().getHolder() instanceof EventMenu) return;
        // Never let the event relic be hidden inside a chest, shulker, or ender chest.
        if (event.getView().getTopInventory() instanceof org.bukkit.inventory.PlayerInventory) return;
        if (isRelic(event.getCursor(), active)
                || (event.getRawSlot() < event.getView().getTopInventory().getSize()
                    && isRelic(event.getCurrentItem(), active))) {
            event.setCancelled(true);
        }
    }

    private void status(CommandSender sender) {
        if (active == null) { sender.sendMessage(prefix() + "No active event."); return; }
        sender.sendMessage(prefix() + "Active: " + label(active.type) + " | Prize: " + rewardName(active.reward)
                + " | Time left: " + Math.max(0, active.totalSeconds - active.seconds) + "s");
        if (active.objective != null) sender.sendMessage(prefix() + "Location: " + loc(active.objective));
        if (active.type.equals("assassin")) sender.sendMessage(prefix() + "Only eliminations of your assigned target score points.");
        if (active.type.equals("assassin") || active.type.equals("koth")) {
            List<Map.Entry<UUID, Integer>> leaders = active.points.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).limit(3).toList();
            for (int i = 0; i < leaders.size(); i++) {
                var entry = leaders.get(i);
                Player player = Bukkit.getPlayer(entry.getKey());
                sender.sendMessage(prefix() + "#" + (i+1) + " "
                        + (player == null ? entry.getKey().toString().substring(0,8) : player.getName())
                        + ": " + entry.getValue() + (active.type.equals("koth") ? " seconds" : " kills"));
            }
        }
    }
    private String loc(Location l) { return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ(); }

    private boolean eligible(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR;
    }
    private List<Player> participants() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (eligible(player)) {
                players.add(player);
            }
        }
        return players;
    }
    private void start(CommandSender sender, String type, String reward, int minutes) {
        if (active != null) { sender.sendMessage(prefix() + ChatColor.RED + "Finish or stop the current event first."); return; }
        if (minutes < 1 || minutes > 120) {
            sender.sendMessage(prefix() + "Duration must be from 1 to 120 minutes."); return;
        }
        List<Player> players = participants();
        if (players.isEmpty()) { sender.sendMessage(prefix() + "At least one survival player must be online."); return; }
        if (type.equals("assassin") && players.size() < 2) {
            sender.sendMessage(prefix() + "Assassin requires at least two online survival players."); return;
        }
        Player host = sender instanceof Player p ? p : players.get(0);
        World world = host.getWorld();
        if ((type.equals("meteor") || type.equals("treasure") || type.equals("titan"))
                && world.getEnvironment() != World.Environment.NORMAL) {
            sender.sendMessage(prefix() + "Start the " + label(type) + " event from an Overworld location.");
            return;
        }
        Location origin = host.getLocation().clone();
        if (!world.getWorldBorder().isInside(origin)) {
            sender.sendMessage(prefix() + ChatColor.RED + "Stand inside the world border before starting this event."); return;
        }
        Location objective = null;
        if (type.equals("treasure")) {
            objective = randomSurfaceLocation(origin, Math.max(24, configuredInt("spawn-range-blocks", 450)));
            if (objective == null) {
                sender.sendMessage(prefix() + ChatColor.RED + "Could not find a safe location inside the world border."); return;
            }
        }
        if (type.equals("meteor")) {
            Location site = meteorSurfaceLocation(origin, Math.max(24, configuredInt("spawn-range-blocks", 450)));
            if (site == null) {
                sender.sendMessage(prefix() + ChatColor.RED + "No safe, flat natural terrain inside the world border for a meteor crater. Try another location.");
                return;
            }
            objective = site;
        }
        if (type.equals("titan")) {
            objective = randomSurfaceLocation(origin, Math.max(16, configuredInt("boss-spawn-range-blocks", 40)));
            if (objective == null) {
                sender.sendMessage(prefix() + ChatColor.RED + "Could not find a safe surface for Frost Titan."); return;
            }
        }
        BossBar bar = Bukkit.createBossBar(ChatColor.AQUA + "Frost Event: " + label(type), BarColor.BLUE, BarStyle.SEGMENTED_10);
        Active event = new Active(type, reward, world, minutes * 60, bar);
        event.serial = ++generation;
        event.origin = origin;
        event.objective = objective;
        active = event;
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
        announce(ChatColor.WHITE + label(type) + ChatColor.AQUA + " started! Prize: " + ChatColor.GOLD + rewardName(reward)
                + ChatColor.AQUA + ". Duration: " + minutes + "m.");
        switch (type) {
            case "meteor" -> startMeteor(event);
            case "treasure" -> {
                if (!placeObjectiveChest(event)) {
                    sender.sendMessage(prefix() + "The treasure chest location became occupied. Event cancelled.");
                    closeEvent(); return;
                }
                int nearX = Math.floorDiv(event.objective.getBlockX(), 200) * 200;
                int nearZ = Math.floorDiv(event.objective.getBlockZ(), 200) * 200;
                announce("A Frost Chest spawned in " + world.getName() + "! First clue: somewhere near X="
                        + nearX + ", Z=" + nearZ + " (approximately 200 blocks). New clues every 50 seconds.");
            }
            case "koth" -> {
                event.objective = origin;
                announce("Capture the hill at " + loc(origin) + ". Only the sole player inside earns progress!");
            }
            case "chosen" -> startChosen(event, players);
            case "assassin" -> {
                for (Player p : players) assignTarget(event, p);
                announce("Hunt YOUR assigned target! Check the action bar for their name and distance.");
            }
            case "titan" -> spawnTitan(event);
            default -> { }
        }
    }

    /** Checks candidate and final chest/meteor positions against the actual, current border. */
    private Location randomSurfaceLocation(Location origin, int radius) {
        World world = origin.getWorld();
        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2.0;
        Location center = border.getCenter();
        double margin = Math.min(8.0, Math.max(1.0, half / 5.0));
        double minX = center.getX() - half + margin, maxX = center.getX() + half - margin;
        double minZ = center.getZ() - half + margin, maxZ = center.getZ() + half - margin;
        if (minX >= maxX || minZ >= maxZ) return null;
        for (int i = 0; i < 28; i++) {
            int x = (int) Math.floor(Math.max(minX, Math.min(maxX, origin.getX() + random.nextInt(radius * 2 + 1) - radius)));
            int z = (int) Math.floor(Math.max(minZ, Math.min(maxZ, origin.getZ() + random.nextInt(radius * 2 + 1) - radius)));
            Block ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!ground.getType().isSolid()) continue;
            Location p = new Location(world, x + .5, ground.getY() + 1, z + .5);
            if (!border.isInside(p) || !border.isInside(p.clone().add(3, 0, 3))
                    || !border.isInside(p.clone().add(-3, 0, -3))) continue;
            if (!p.getBlock().isPassable() || !p.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            if (ground.getY() < world.getMinHeight() + 2 || ground.getY() > world.getMaxHeight() - 7) continue;
            return p;
        }
        return null;
    }

    private int meteorRadius() {
        return Math.max(4, Math.min(8, configuredInt("meteor-impact-radius-blocks", 7)));
    }

    /** No player builds, farms, trees, water, or tile entities are overwritten.
     *  The entire crater footprint, not just its center, must be within the border. */
    private boolean naturalMeteorSite(Location center) {
        World world = center.getWorld();
        WorldBorder border = world.getWorldBorder();
        int radius = meteorRadius();
        int x = center.getBlockX(), z = center.getBlockZ();
        int centerY = center.getBlockY() - 1;
        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius + 1) continue;
                Location at = new Location(world, x + dx + .5, centerY, z + dz + .5);
                if (!border.isInside(at)) return false;
                if (distance > radius + .1) continue;
                Block ground = world.getHighestBlockAt(x + dx, z + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (Math.abs(ground.getY() - centerY) > 2) return false;
                Material material = ground.getType();
                if (!(material == Material.GRASS_BLOCK || material == Material.DIRT
                        || material == Material.COARSE_DIRT || material == Material.PODZOL
                        || material == Material.STONE || material == Material.ANDESITE
                        || material == Material.DIORITE || material == Material.GRANITE
                        || material == Material.TUFF || material == Material.MOSS_BLOCK
                        || material == Material.MYCELIUM || material == Material.SNOW_BLOCK
                        || material == Material.SAND || material == Material.RED_SAND
                        || material == Material.GRAVEL || material == Material.MUD)) return false;
                // Don't overwrite tree leaves, walls, roofs or other player constructions above the ground.
                for (int above = 1; above <= 7; above++) {
                    Material m = ground.getRelative(0, above, 0).getType();
                    if (!m.isAir() && m != Material.SHORT_GRASS && m != Material.TALL_GRASS
                            && m != Material.FERN && m != Material.LARGE_FERN
                            && m != Material.SNOW) return false;
                }
            }
        }
        return centerY - 3 > world.getMinHeight() && centerY + 9 < world.getMaxHeight();
    }

    private Location meteorSurfaceLocation(Location origin, int range) {
        World world = origin.getWorld();
        WorldBorder border = world.getWorldBorder();
        int r = meteorRadius();
        double half = border.getSize() / 2.0;
        Location center = border.getCenter();
        double margin = r + 14.0; // room for the approach and ALL parts of the meteor
        if (half <= margin) return null;
        for (int i = 0; i < 120; i++) {
            double x = origin.getX() + random.nextInt(range * 2 + 1) - range;
            double z = origin.getZ() + random.nextInt(range * 2 + 1) - range;
            x = Math.max(center.getX() - half + margin, Math.min(center.getX() + half - margin, x));
            z = Math.max(center.getZ() - half + margin, Math.min(center.getZ() + half - margin, z));
            Block ground = world.getHighestBlockAt((int)Math.floor(x), (int)Math.floor(z), HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Location location = new Location(world, ground.getX() + .5, ground.getY() + 1, ground.getZ() + .5);
            if (naturalMeteorSite(location)) return location;
        }
        return null;
    }

    private BlockDisplay newMeteorDisplay(Active event, Location at, Material material, float size) {
        BlockDisplay display = event.world.spawn(at, BlockDisplay.class);
        display.setBlock(material.createBlockData());
        display.setTransformation(new Transformation(
                new Vector3f(-size / 2f, -size / 2f, -size / 2f),
                new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
        // Zero is deliberate. Repeated interpolated teleports can be perpetually
        // restarted by newer clients, leaving a large display apparently frozen.
        // Our explicit server-side keyframes are visually reliable in 1.21.11.
        display.setTeleportDuration(0);
        display.setViewRange(2.0f);
        event.visuals.add(display);
        return display;
    }

    private void meteorEdit(Active event, Block block, Material material) {
        if (block.getType() == material) return;
        event.impactOriginal.putIfAbsent(block, block.getBlockData().clone());
        block.setType(material, false);
        event.impactPlaced.put(block, block.getBlockData().clone());
    }

    /** A real, block-built shallow crater with a sunken reward chest, blue-ice
     *  shockwave rings and tall uneven crystal spikes. Restored when event ends. */
    private boolean meteorImpact(Active event, Location landing) {
        if (!naturalMeteorSite(landing)) return false;
        int radius = meteorRadius();
        World world = landing.getWorld();
        int x = landing.getBlockX(), z = landing.getBlockZ();
        int floorY = landing.getBlockY() - 3;
        int side = radius * 2 + 1;
        int[][] surface = new int[side][side];
        // Snapshot terrain height before ANY edits, so later columns aren't affected.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                surface[dx + radius][dz + radius] = world.getHighestBlockAt(x + dx, z + dz,
                        HeightMap.MOTION_BLOCKING_NO_LEAVES).getY();
            }
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius + .12) continue;
                int wx = x + dx, wz = z + dz;
                int groundY = surface[dx + radius][dz + radius];
                int pattern = Math.floorMod(wx * 31 + wz * 17, 11);
                if (d <= 2.6) {
                    // Carve the center shallowly and fill its floor with ice.
                    for (int y = floorY + 1; y <= groundY + 2; y++)
                        meteorEdit(event, world.getBlockAt(wx, y, wz), Material.AIR);
                    meteorEdit(event, world.getBlockAt(wx, floorY, wz),
                            pattern % 3 == 0 ? Material.BLUE_ICE : Material.PACKED_ICE);
                } else if (d <= 4.0) {
                    meteorEdit(event, world.getBlockAt(wx, groundY, wz),
                            pattern % 4 == 0 ? Material.BLUE_ICE : Material.PACKED_ICE);
                } else if (pattern < 7 || Math.abs(dx) <= 1 || Math.abs(dz) <= 1 || Math.abs(dx) == Math.abs(dz)) {
                    // Jagged ice cracks radiating away from the impact.
                    meteorEdit(event, world.getBlockAt(wx, groundY, wz),
                            pattern % 3 == 0 ? Material.BLUE_ICE : Material.PACKED_ICE);
                    if (d >= 4.8 && d < radius - .2 && pattern % 3 == 0)
                        meteorEdit(event, world.getBlockAt(wx, groundY + 1, wz), Material.BLUE_ICE);
                }
            }
        }
        // Towering shards encircle (but never cover) the central chest.
        for (int n = 0; n < 12; n++) {
            double angle = n * (Math.PI * 2 / 12);
            double distance = 4.2 + (n % 3) * .7;
            int dx = (int)Math.round(Math.cos(angle) * distance);
            int dz = (int)Math.round(Math.sin(angle) * distance);
            if (Math.abs(dx) > radius || Math.abs(dz) > radius) continue;
            int groundY = surface[dx + radius][dz + radius];
            int height = 2 + (n * 7 % 5);
            for (int h = 1; h <= height; h++) {
                meteorEdit(event, world.getBlockAt(x + dx, groundY + h, z + dz),
                        h == height ? Material.ICE : (n % 3 == 0 ? Material.BLUE_ICE : Material.PACKED_ICE));
            }
        }
        // The chest sits inside the crater, rather than appearing alone on the grass.
        event.objective = new Location(world, x + .5, floorY + 1, z + .5);
        return placeObjectiveChest(event);
    }

    // Minecraft unloads unobserved chunks, including chunks containing display
    // entities. A meteor can spawn hundreds of blocks from all players, so it must
    // hold chunk tickets for the whole approach and crater until event cleanup.
    private void keepMeteorChunksLoaded(Active event, Location landing) {
        int r = meteorRadius() + 5;
        int minX = landing.getBlockX() - 11 - r;
        int maxX = landing.getBlockX() + r;
        int minZ = landing.getBlockZ() - 9 - r;
        int maxZ = landing.getBlockZ() + r;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (event.world.addPluginChunkTicket(cx, cz, plugin)) {
                    long packed = (((long) cx) << 32) | (cz & 0xffffffffL);
                    event.meteorChunkTickets.add(packed);
                }
            }
        }
    }

    private Location meteorFlightPosition(Location landing, double altitude, double progress) {
        // Accelerate for the drop: one continuous path, independent of any client
        // interpolation or the display entities' own movement state.
        double eased = progress * progress;
        return landing.clone().add(-11.0 * (1.0 - eased),
                altitude * (1.0 - eased), -9.0 * (1.0 - eased));
    }

    private void removeMeteorVisuals(Active event, List<BlockDisplay> parts) {
        for (BlockDisplay part : parts) {
            if (part != null && part.isValid()) part.remove();
            event.visuals.remove(part);
        }
        parts.clear();
    }

    private void finishMeteorLanding(Active event, Location landing,
                                     List<BlockDisplay> parts) {
        // The countdown—not a display entity's lifetime—controls the impact.
        removeMeteorVisuals(event, parts);
        if (active != event) return;
        if (!event.world.getWorldBorder().isInside(landing)
                || !meteorImpact(event, landing)) {
            announce(ChatColor.RED + "The meteor could not land safely. Event cancelled.");
            closeEvent();
            return;
        }
        event.meteorLanded = true;
        Location blast = event.objective.clone().add(0, 2, 0);
        event.world.spawnParticle(Particle.EXPLOSION, blast, 16, 4.0, 1.8, 4.0);
        event.world.spawnParticle(Particle.SNOWFLAKE, blast, 280, 6.0, 2.0, 6.0, .14);
        event.world.spawnParticle(Particle.BLOCK, blast, 150, 4.0, 2.5, 4.0,
                .32, Material.BLUE_ICE.createBlockData());
        event.world.playSound(blast, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, .55f);
        event.world.playSound(blast, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.7f, .65f);
        event.world.strikeLightningEffect(blast);
        announce(ChatColor.GOLD + "BOOM! " + ChatColor.AQUA
                + "Frozen Meteor created an icy crater at " + loc(event.objective)
                + "! Find the chest INSIDE the crater and right-click it to win!");
    }

    private void startMeteor(Active event) {
        final Location landing = event.objective.clone();
        double skyY = Math.min(event.world.getMaxHeight() - 9, landing.getY() + 65);
        double altitude = Math.max(12, skyY - landing.getY());
        final int flightTicks = Math.max(60, Math.min(240,
                configuredInt("meteor-flight-seconds", 7) * 20));
        // Preload BEFORE creating entities so they cannot despawn during flight.
        keepMeteorChunksLoaded(event, landing);
        // Nine uniquely sized blocks make up the icy meteor core and fragments.
        final double[][] fragments = {
                {0, 0, 0, 5.0}, {2.4, 1.6, .8, 2.3}, {-2.2, .7, -.7, 2.2},
                {1.3, -2.2, 1.6, 1.8}, {-1.5, 2.5, 1.1, 1.8},
                {1.3, 1.2, -2.4, 1.8}, {-2.0, -1.8, -1.4, 1.7},
                {0, -3.0, 0, 1.5}, {0, 3.0, .4, 1.5}
        };
        final List<BlockDisplay> parts = new ArrayList<>();
        // Previously every fragment spawned at the same location until a later
        // teleport arrived. Build the meteor correctly at its initial position.
        Location initial = meteorFlightPosition(landing, altitude, 0.0);
        for (int i = 0; i < fragments.length; i++) {
            double[] f = fragments[i];
            Material material = i % 4 == 0 ? Material.BLUE_ICE
                    : i % 3 == 0 ? Material.ICE : Material.PACKED_ICE;
            parts.add(newMeteorDisplay(event, initial.clone().add(f[0], f[1], f[2]),
                    material, (float) f[3]));
        }
        announce(ChatColor.BOLD + "A GIANT FROZEN METEOR IS FALLING! " + ChatColor.RESET
                + "Impact near X=" + landing.getBlockX() + ", Z=" + landing.getBlockZ()
                + " in " + event.world.getName() + "! Prepare for impact!");
        // Teleport every two server ticks with 0 teleport interpolation.
        // The visual is therefore at a *different real position* every frame,
        // and cannot get frozen waiting for an interpolation to complete.
        new BukkitRunnable() {
            int elapsedTicks = 0;
            @Override public void run() {
                if (active != event) { cancel(); return; }
                elapsedTicks += 2;
                double progress = Math.min(1.0, elapsedTicks / (double) flightTicks);
                Location point = meteorFlightPosition(landing, altitude, progress);
                WorldBorder border = event.world.getWorldBorder();
                if (!border.isInside(point) || !border.isInside(landing)) {
                    announce("The world border moved; Frozen Meteor was cancelled safely.");
                    closeEvent(); cancel(); return;
                }
                for (int i = 0; i < parts.size(); i++) {
                    BlockDisplay part = parts.get(i);
                    double[] f = fragments[i];
                    Location spot = point.clone().add(f[0], f[1], f[2]);
                    if (part != null && part.isValid()) {
                        part.teleport(spot);
                    }
                    // A missing or removed display must never prevent the
                    // remaining meteor animation or the eventual impact.
                }
                event.world.spawnParticle(Particle.SNOWFLAKE, point, 35,
                        2.0, 2.0, 2.0, .1);
                event.world.spawnParticle(Particle.CLOUD, point, 12,
                        1.0, 1.0, 1.0, .06);
                if (elapsedTicks % 8 == 0)
                    event.world.spawnParticle(Particle.END_ROD, point, 18,
                            1.5, 1.2, 1.5, .05);
                if (elapsedTicks % 20 == 0)
                    event.world.playSound(point, Sound.ENTITY_BLAZE_SHOOT, 1.5f, .55f);
                if (elapsedTicks >= flightTicks) {
                    finishMeteorLanding(event, landing, parts);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private boolean placeObjectiveChest(Active event) {
        Location location = event.objective;
        if (location == null || !location.getWorld().getWorldBorder().isInside(location)) return false;
        Block block = location.getBlock();
        if (!block.getType().isAir()) return false; // Never overwrite a player's structure or liquid.
        event.objectiveBlock = block;
        event.oldBlockData = block.getBlockData().clone();
        block.setType(Material.CHEST, false);
        event.world.spawnParticle(Particle.SNOWFLAKE, location.clone().add(0, .5, 0), 50, 1, 1, 1, .04);
        event.world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, .7f);
        return true;
    }

    private void startChosen(Active event, List<Player> players) {
        Player chosen = players.get(random.nextInt(players.size()));
        event.chosenHolder = chosen.getUniqueId();
        giveRelic(chosen, event);
        announce(ChatColor.GOLD + chosen.getName() + ChatColor.AQUA + " is the Chosen One!");
        announce("Hold the event relic when time runs out. Defeat the holder to claim it!");
    }
    private ItemStack createRelic(Active event) {
        ItemStack relic = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = relic.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Frost Chosen Relic");
        meta.setLore(List.of(ChatColor.GRAY + "Event " + event.token, ChatColor.GOLD + "Hold this when time expires to win!"));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(relicMarker, PersistentDataType.STRING, event.token.toString());
        relic.setItemMeta(meta);
        return relic;
    }
    private boolean isRelic(ItemStack item, Active event) {
        if (item == null || !item.hasItemMeta()) return false;
        String token = item.getItemMeta().getPersistentDataContainer().get(relicMarker, PersistentDataType.STRING);
        return token != null && token.equals(event.token.toString());
    }
    private void giveRelic(Player target, Active event) {
        Map<Integer, ItemStack> extra = target.getInventory().addItem(createRelic(event));
        for (ItemStack leftover : extra.values()) target.getWorld().dropItemNaturally(target.getLocation(), leftover);
    }
    private UUID currentRelicHolder(Active event) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!eligible(player)) continue;
            for (ItemStack item : player.getInventory().getContents())
                if (isRelic(item, event)) return player.getUniqueId();
        }
        return null;
    }
    private void removeAllRelics(Active event) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                if (isRelic(item, event)) player.getInventory().setItem(slot, null);
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item entity : world.getEntitiesByClass(Item.class)) {
                if (isRelic(entity.getItemStack(), event)) entity.remove();
            }
        }
    }

    // -------------------- Frost Titan: scripted Warden raid boss --------------------
    // Warden AI must remain OFF. A vanilla Warden has a dangerous sonic boom,
    // an extremely high natural melee hit, and its own autonomous target logic.
    // The event below fully owns movement, melee and exactly five special attacks.
    private void spawnTitan(Active event) {
        Location spot = event.objective.clone();
        Warden boss = event.world.spawn(spot, Warden.class);
        boss.setCustomName(ChatColor.AQUA + "FROST TITAN " + ChatColor.DARK_AQUA + "[RAID BOSS]");
        boss.setCustomNameVisible(true);
        boss.setAI(false);
        boss.setPersistent(true);
        boss.setRemoveWhenFarAway(false);
        boss.setCanPickupItems(false);
        // Vanilla's maximum max_health attribute value is 1,024. The raid
        // health below is independent and can safely be much higher.
        double nativeMaxHealth = 1000.0;
        if (boss.getAttribute(Attribute.MAX_HEALTH) != null)
            boss.getAttribute(Attribute.MAX_HEALTH).setBaseValue(nativeMaxHealth);
        boss.setHealth(nativeMaxHealth);
        event.titanMaxRaidHealth = Math.max(500.0, configuredDouble("titan-health", 6000.0));
        event.titanRaidHealth = event.titanMaxRaidHealth;
        event.titan = boss;
        event.bar.setColor(BarColor.PURPLE);
        announce("FROST TITAN spawned at " + loc(spot) + "! "
                + (int) event.titanMaxRaidHealth + " raid HP, five attacks, and a second phase!"
                + " Highest damage dealt wins!");
        event.world.playSound(spot, Sound.ENTITY_WITHER_SPAWN, 1.5f, .55f);
        event.world.spawnParticle(Particle.SNOWFLAKE, spot.clone().add(0, 2, 0),
                150, 3.0, 2.5, 3.0, .07);
    }

    private boolean titanActive(Active event) {
        return active == event && !event.titanDefeated && event.titan != null
                && event.titan.isValid() && !event.titan.isDead();
    }

    private List<Player> titanPlayers(Active event, Location near, double radius) {
        List<Player> result = new ArrayList<>();
        for (Player player : participants()) {
            if (player.getWorld().equals(event.world)
                    && player.getLocation().distanceSquared(near) <= radius * radius)
                result.add(player);
        }
        return result;
    }

    // Each Titan attack can reach at least 20 blocks from the boss. Impact markers
    // for targeted attacks remain smaller so that skilled players can still dodge.
    private double titanAttackRadius() {
        return Math.max(20.0, Math.min(48.0,
                configuredDouble("titan-attack-radius-blocks", 24.0)));
    }

    private double titanChaseRadius() {
        return Math.max(35.0, Math.min(120.0,
                configuredDouble("titan-chase-range-blocks", 85.0)));
    }

    /**
     * Checks a Warden-width footprint and three blocks of headroom. All movement
     * is plugin-driven because setVelocity() does not reliably move an AI-disabled
     * Warden. Never teleport it through a wall, a large drop, or the world border.
     */
    private boolean titanStandable(Location at) {
        if (!at.getWorld().getWorldBorder().isInside(at)) return false;
        double width = .40;
        for (double dx : new double[]{-width, width}) {
            for (double dz : new double[]{-width, width}) {
                Location corner = at.clone().add(dx, 0, dz);
                Block ground = corner.clone().add(0, -1, 0).getBlock();
                if (!ground.getType().isSolid() || ground.isLiquid()) return false;
                for (int dy = 0; dy <= 2; dy++) {
                    Block space = corner.clone().add(0, dy, 0).getBlock();
                    if (!space.isPassable() || space.isLiquid()) return false;
                }
            }
        }
        return true;
    }

    private Location titanTryStep(Location from, double dx, double dz) {
        // Climb no more than one full block; descend at most two blocks.
        for (int yOffset : new int[]{0, 1, -1, -2}) {
            Location next = from.clone().add(dx, yOffset, dz);
            // Give the entity an integer-height foothold. Teleports to half blocks
            // would risk the boss intersecting solid blocks on the next step.
            next.setY(Math.floor(from.getY()) + yOffset);
            if (titanStandable(next)) return next;
        }
        return null;
    }

    /** Scripted raid chase and controlled melee; NO native Warden AI. */
    private void tickTitanMovement() {
        Active event = active;
        if (event == null || !event.type.equals("titan") || !titanActive(event)) return;
        Warden boss = event.titan;
        if (boss.hasAI()) boss.setAI(false);
        ++event.titanMovementStep;
        Location center = boss.getLocation();
        List<Player> targets = titanPlayers(event, center, titanChaseRadius());
        if (targets.isEmpty()) return;

        Player nearest = targets.stream().min(Comparator.comparingDouble(
                p -> p.getLocation().distanceSquared(center))).orElse(null);
        if (nearest == null) return;
        Vector toTarget = nearest.getLocation().toVector().subtract(center.toVector());
        double horizontal = Math.hypot(toTarget.getX(), toTarget.getZ());
        if (horizontal > 2.9) {
            double speed = Math.max(.20, Math.min(.85,
                    configuredDouble("titan-chase-speed-per-step", .48)))
                    * (event.enraged ? 1.22 : 1.0);
            double forwardX = toTarget.getX() / horizontal;
            double forwardZ = toTarget.getZ() / horizontal;
            // First try directly ahead, then diagonals and sideways to navigate
            // around trees, stone pillars and small obstacles without teleporting
            // through collision geometry. Alternate the preferred dodge direction
            // when the Titan becomes stuck.
            double[] offsets = {0, 35, -35, 70, -70, 100, -100, 140, -140};
            Location next = null;
            for (double offset : offsets) {
                double rad = Math.toRadians(offset * event.titanStrafeDirection);
                double nx = forwardX * Math.cos(rad) - forwardZ * Math.sin(rad);
                double nz = forwardX * Math.sin(rad) + forwardZ * Math.cos(rad);
                next = titanTryStep(center, nx * speed, nz * speed);
                if (next != null) break;
            }
            if (next != null) {
                double yaw = (float) Math.toDegrees(Math.atan2(-toTarget.getX(), toTarget.getZ()));
                next.setYaw((float) yaw);
                next.setPitch(0.0f);
                if (boss.teleport(next)) {
                    event.titanLastSuccessfulStep = event.titanMovementStep;
                    if (event.titanMovementStep % 5 == 0)
                        event.world.spawnParticle(Particle.SNOWFLAKE,
                                next.clone().add(0, .25, 0), 4, .6, .08, .6, .01);
                }
            }
            if (event.titanMovementStep - event.titanLastSuccessfulStep > 15) {
                event.titanStrafeDirection *= -1;
                event.titanLastSuccessfulStep = event.titanMovementStep;
            }
        }
        if (toTarget.lengthSquared() <= 3.4 * 3.4
                && event.titanMovementStep >= event.titanNextMeleeStep) {
            // 2 ticks per movement step: one melee attack every 1.8s normally, 1.2s enraged.
            event.titanNextMeleeStep = event.titanMovementStep + (event.enraged ? 12 : 18);
            double damage = Math.max(1.0, Math.min(10.0,
                    configuredDouble("titan-melee-damage-health-points", 6.0)));
            nearest.damage(damage, boss);  // Armor and Protection apply.
            event.world.playSound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.1f, .65f);
            Vector push = nearest.getLocation().toVector().subtract(center.toVector());
            if (push.lengthSquared() > .01) {
                push.setY(0).normalize().multiply(.55).setY(.22);
                nearest.setVelocity(push);
            }
        }
    }

    private void titanAttacks(Active event) {
        if (!titanActive(event)) return;
        Warden boss = event.titan;
        if (!event.enraged && event.titanRaidHealth <= event.titanMaxRaidHealth * .45) {
            event.enraged = true;
            announce(ChatColor.RED + "FROST TITAN IS ENRAGED! Phase II: faster attacks and more hazards!");
            event.world.playSound(boss.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.5f, 1.4f);
            event.world.spawnParticle(Particle.SNOWFLAKE,
                    boss.getLocation().add(0, 2, 0), 120, 3, 2, 3, .07);
        }
        if (event.seconds < event.titanNextAbility) return;
        event.titanNextAbility = event.seconds + (event.enraged ? 5 : 7);
        int ability = event.titanAttackIndex++ % 5;
        switch (ability) {
            case 0 -> castTitanFrostquake(event);
            case 1 -> castTitanIceLances(event);
            case 2 -> castTitanWhiteout(event);
            case 3 -> castTitanIcePillars(event);
            case 4 -> castTitanSummonGuards(event);
            default -> { }
        }
    }

    // Visual ground effects and an ambient chime telegraph attacks.
    // Never occupy the action bar: it belongs to event score / target tracking.
    private void titanWarning(Active event, String name) {
        if (!titanActive(event)) return;
        event.world.playSound(event.titan.getLocation(),
                Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.15f, .55f);
    }

    private void frostRing(Location center, double radius, Particle particle) {
        for (int step = 0; step < 36; step++) {
            double a = step * Math.PI / 18.0;
            center.getWorld().spawnParticle(particle, center.clone().add(
                    Math.cos(a) * radius, .15, Math.sin(a) * radius), 2, .10, .08, .10, .002);
        }
    }

    // Attack 1: expanding shockwave. A jump or moving beyond the ring avoids it.
    private void castTitanFrostquake(Active event) {
        titanWarning(event, "GLACIAL FROSTQUAKE — JUMP OR RETREAT!");
        Location center = event.titan.getLocation().clone();
        double radius = titanAttackRadius();
        frostRing(center, radius, Particle.SNOWFLAKE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!titanActive(event)) return;
            event.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, .75f);
            frostRing(center, radius, Particle.SNOWFLAKE);
            event.world.spawnParticle(Particle.BLOCK,
                    center.clone().add(0, .6, 0), 240, radius * .5, .4, radius * .5, .12,
                    Material.PACKED_ICE.createBlockData());
            for (Player target : titanPlayers(event, center, radius)) {
                if (Math.abs(target.getLocation().getY() - center.getY()) > 1.5) continue;
                target.damage(event.enraged ? 8.0 : 6.0, event.titan);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
                Vector away = target.getLocation().toVector().subtract(center.toVector());
                if (away.lengthSquared() > .01) target.setVelocity(away.setY(0).normalize().multiply(.6).setY(.34));
            }
        }, 26L);
    }

    // Attack 2: target markers, then crystal spears strike the old locations.
    private void castTitanIceLances(Active event) {
        titanWarning(event, "ICE LANCE VOLLEY — KEEP MOVING!");
        Location center = event.titan.getLocation();
        List<Player> targets = titanPlayers(event, center, titanAttackRadius() + 10.0);
        java.util.Collections.shuffle(targets, random);
        int count = Math.min(event.enraged ? 6 : 4, targets.size());
        List<Location> marks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location mark = targets.get(i).getLocation().clone();
            marks.add(mark);
            event.world.spawnParticle(Particle.SNOWFLAKE, mark.clone().add(0, .3, 0), 45, .65, .15, .65, .015);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!titanActive(event)) return;
            Set<UUID> damaged = new LinkedHashSet<>();
            for (Location mark : marks) {
                event.world.playSound(mark, Sound.BLOCK_GLASS_BREAK, 1.0f, .8f);
                event.world.spawnParticle(Particle.BLOCK, mark.clone().add(0, 1.6, 0),
                        70, .7, 1.1, .7, .08, Material.BLUE_ICE.createBlockData());
                for (Player target : titanPlayers(event, mark, 3.2)) {
                    if (!damaged.add(target.getUniqueId())) continue;
                    target.damage(event.enraged ? 7.0 : 5.0, event.titan);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 1));
                }
            }
        }, 28L);
    }

    // Attack 3: a blizzard that lasts five seconds. Leaving the area counters it.
    private void castTitanWhiteout(Active event) {
        titanWarning(event, "WHITEOUT BLIZZARD — LEAVE THE STORM!");
        Location center = event.titan.getLocation().clone();
        event.world.playSound(center, Sound.BLOCK_POWDER_SNOW_BREAK, 1.8f, .45f);
        for (int pulse = 0; pulse < 5; pulse++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!titanActive(event)) return;
                Location at = event.titan.getLocation().clone();
                double radius = titanAttackRadius() + (event.enraged ? 3.0 : 0.0);
                event.world.spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 2, 0),
                        320, radius * .68, 3, radius * .68, .06);
                for (Player target : titanPlayers(event, at, radius)) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
                    target.damage(2.0, event.titan);
                }
            }, 20L * pulse);
        }
    }

    // Attack 4: ice pillars erupt from telegraphed ground spots. No block grief.
    private void castTitanIcePillars(Active event) {
        titanWarning(event, "CRYSTAL ERUPTION — DODGE THE ICE CIRCLES!");
        List<Location> marks = new ArrayList<>();
        Location center = event.titan.getLocation();
        int count = event.enraged ? 18 : 14;
        double range = titanAttackRadius();
        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i / count) + (i % 3) * .31;
            // Inner / middle / outer rings all cover more than 20 blocks.
            double distance = range * (.38 + (i % 4) * .205);
            Location mark = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            Block floor = event.world.getHighestBlockAt(mark, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            mark.setY(floor.getY() + 1);
            if (!event.world.getWorldBorder().isInside(mark)) continue;
            marks.add(mark);
            frostRing(mark, 2.8, Particle.SNOWFLAKE);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!titanActive(event)) return;
            Set<UUID> damaged = new LinkedHashSet<>();
            for (Location mark : marks) {
                event.world.spawnParticle(Particle.BLOCK, mark.clone().add(0, 1.2, 0),
                        50, .65, 1.0, .65, .04, Material.PACKED_ICE.createBlockData());
                event.world.playSound(mark, Sound.BLOCK_GLASS_BREAK, .85f, .7f);
                for (Player target : titanPlayers(event, mark, 3.0)) {
                    if (!damaged.add(target.getUniqueId())) continue;
                    target.damage(event.enraged ? 7.0 : 5.0, event.titan);
                    Vector lift = target.getVelocity();
                    target.setVelocity(new Vector(lift.getX(), .72, lift.getZ()));
                }
            }
        }, 32L);
    }

    // Attack 5: summons frosted sword-wielding Strays to split raid attention.
    private void castTitanSummonGuards(Active event) {
        titanWarning(event, "FROST GUARD — REINFORCEMENTS INCOMING!");
        Location center = event.titan.getLocation();
        event.world.playSound(center, Sound.ENTITY_WITHER_SPAWN, .75f, 1.5f);
        int present = 0;
        for (Entity entity : event.visuals) if (entity instanceof Stray && entity.isValid()) present++;
        int count = Math.min(event.enraged ? 7 : 5, Math.max(0, 16 - present));
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / Math.max(1, count);
            double spawnRadius = 10.0 + (i % 4) * 4.2;
            Location spawn = center.clone().add(Math.cos(angle) * spawnRadius, 0, Math.sin(angle) * spawnRadius);
            Block ground = event.world.getHighestBlockAt(spawn, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            spawn.setY(ground.getY() + 1);
            if (!event.world.getWorldBorder().isInside(spawn)) continue;
            Stray guard = event.world.spawn(spawn, Stray.class);
            guard.setCustomName(ChatColor.AQUA + "Titan's Frost Guard");
            guard.setPersistent(true);
            guard.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
            guard.getEquipment().setHelmetDropChance(0f);
            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = sword.getItemMeta();
            meta.addEnchant(Enchantment.SHARPNESS, event.enraged ? 3 : 2, true);
            sword.setItemMeta(meta);
            guard.getEquipment().setItemInMainHand(sword);
            guard.getEquipment().setItemInMainHandDropChance(0f);
            guard.setFireTicks(0);
            event.visuals.add(guard);
            Player nearest = titanPlayers(event, spawn, titanAttackRadius() + 12.0).stream()
                    .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(spawn))).orElse(null);
            if (nearest != null) guard.setTarget(nearest);
            event.world.spawnParticle(Particle.SNOWFLAKE, spawn.clone().add(0, 1, 0),
                    30, .5, .7, .5, .03);
        }
    }

    private void assignTarget(Active event, Player hunter) {
        List<Player> options = participants().stream()
                .filter(player -> !player.getUniqueId().equals(hunter.getUniqueId())).toList();
        if (options.isEmpty()) { event.targets.remove(hunter.getUniqueId()); return; }
        UUID previous = event.targets.get(hunter.getUniqueId());
        List<Player> others = options.stream().filter(p -> !p.getUniqueId().equals(previous)).toList();
        if (!others.isEmpty()) options = others;
        Player target = options.get(random.nextInt(options.size()));
        event.targets.put(hunter.getUniqueId(), target.getUniqueId());
        hunter.sendMessage(prefix() + "Your next target is " + ChatColor.GOLD + target.getName() + ChatColor.RESET + "!");
    }
    private void tickAssassin(Active event) {
        for (Player hunter : participants()) {
            UUID id = hunter.getUniqueId();
            UUID targetId = event.targets.get(id);
            Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
            if (target == null || !eligible(target) || target.equals(hunter)) {
                assignTarget(event, hunter);
                UUID assigned = event.targets.get(id);
                target = assigned == null ? null : Bukkit.getPlayer(assigned);
            }
            int kills = event.points.getOrDefault(id, 0);
            String distance = target == null ? "?" :
                    (target.getWorld().equals(hunter.getWorld()) ?
                            ((int) Math.round(target.getLocation().distance(hunter.getLocation()))) + " blocks" : "Other dimension");
            String name = target == null ? "Waiting for a player" : target.getName();
            hunter.sendActionBar(Component.text("❄ Target: " + name + " | Distance: " + distance
                    + " | Target kills: " + kills + " | " + (event.totalSeconds - event.seconds) + "s"));
        }
    }

    private void tick() {
        Active event = active;
        if (event == null) return;
        event.seconds++;
        event.bar.setProgress(Math.max(0, Math.min(1, (event.totalSeconds - event.seconds) / (double) event.totalSeconds)));
        for (Player player : Bukkit.getOnlinePlayers()) if (!event.bar.getPlayers().contains(player)) event.bar.addPlayer(player);
        if (event.type.equals("titan")) {
            if (event.titan == null || !event.titan.isValid() || event.titan.isDead()) {
                chooseTitanWinner(event); return;
            }
            double max = Math.max(1.0, event.titanMaxRaidHealth);
            event.bar.setTitle(ChatColor.AQUA + "Frost Titan " + (int) Math.ceil(event.titanRaidHealth)
                    + "/" + (int) max + " RAID HP | " + Math.max(0, event.totalSeconds - event.seconds) + "s");
            event.bar.setProgress(Math.max(0, Math.min(1, event.titanRaidHealth / max)));
            titanAttacks(event);
        } else {
            event.bar.setTitle(ChatColor.AQUA + label(event.type) + " | " + Math.max(0, event.totalSeconds - event.seconds) + "s");
        }
        switch (event.type) {
            case "meteor" -> {
                if (event.meteorLanded && event.seconds % 5 == 0 && event.objective != null)
                    event.world.spawnParticle(Particle.SNOWFLAKE, event.objective.clone().add(0, 1, 0), 45, 1.4, 1.3, 1.4, .02);
            }
            case "treasure" -> {
                if (event.seconds % 50 == 0 && event.objective != null) {
                    Location l = event.objective;
                    int coarse = event.seconds < 150 ? 100 : 20;
                    int x = Math.floorDiv(l.getBlockX(), coarse) * coarse;
                    int z = Math.floorDiv(l.getBlockZ(), coarse) * coarse;
                    announce("Treasure clue: " + event.world.getName() + " | near X=" + x + ", Z=" + z
                            + " (within ~" + coarse + " blocks)");
                }
            }
            case "koth" -> tickKoth(event);
            case "chosen" -> {
                UUID holder = currentRelicHolder(event);
                event.chosenHolder = holder;
                if (holder != null && !holder.equals(event.lastHolder)) {
                    Player player = Bukkit.getPlayer(holder);
                    if (player != null) announce(player.getName() + " now holds the Chosen Relic!");
                }
                event.lastHolder = holder;
                if (holder != null) {
                    Player player = Bukkit.getPlayer(holder);
                    if (player != null) player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0,1,0), 20, .4, .7, .4, .01);
                }
            }
            case "assassin" -> tickAssassin(event);
        }
        if (event.seconds >= event.totalSeconds && active == event) endDueToTime(event);
    }
    private void tickKoth(Active event) {
        int radius = Math.max(3, configuredInt("koth-radius-blocks", 10));
        int target = Math.max(20, configuredInt("koth-capture-seconds", 90));
        List<Player> inside = participants().stream().filter(p -> p.getWorld().equals(event.world))
                .filter(p -> p.getLocation().distanceSquared(event.objective) <= radius * radius).toList();
        if (event.seconds % 2 == 0) event.world.spawnParticle(Particle.SNOWFLAKE,
                event.objective.clone().add(0, .5, 0), 40, radius / 2.0, .3, radius / 2.0, 0.02);
        if (inside.size() != 1) return; // A contested or empty hill earns no points.
        Player leader = inside.get(0);
        int score = event.points.merge(leader.getUniqueId(), 1, Integer::sum);
        leader.sendActionBar(Component.text("❄ Capturing the hill: " + score + "/" + target + " seconds"));
        if (score >= target) win(event, leader.getUniqueId(), "Captured the Frost Hill");
    }

    private UUID leader(Map<UUID, ? extends Number> scores) {
        UUID result = null;
        double top = 0.0;
        for (var entry : scores.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !eligible(player)) continue;
            double amount = entry.getValue().doubleValue();
            if (amount > top) { result = entry.getKey(); top = amount; }
        }
        return result;
    }
    private void endDueToTime(Active event) {
        switch (event.type) {
            case "koth" -> win(event, leader(event.points), "Most hill-control seconds");
            case "assassin" -> win(event, leader(event.points), "Most assigned-target eliminations");
            case "chosen" -> win(event, currentRelicHolder(event), "Held the chosen relic at the final second");
            case "titan" -> chooseTitanWinner(event);
            case "meteor", "treasure" -> win(event, null, "No one claimed the objective before the timer expired");
        }
    }
    private void chooseTitanWinner(Active event) {
        if (active != event) return;
        win(event, leader(event.bossDamage), "Dealt the most damage to the Frost Titan");
    }
    private void win(Active event, UUID winnerId, String reason) {
        if (active != event) return;
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        if (winner == null || !winner.isOnline()) {
            announce("Event ended without an eligible winner. " + reason + ".");
            closeEvent(); return;
        }
        ItemStack prize = plugin.frostEventReward(event.reward);
        if (prize != null) {
            Map<Integer, ItemStack> leftovers = winner.getInventory().addItem(prize);
            for (ItemStack item : leftovers.values()) winner.getWorld().dropItemNaturally(winner.getLocation(), item);
        }
        announce(ChatColor.GOLD + winner.getName() + ChatColor.AQUA + " WON " + label(event.type)
                + "! Prize: " + ChatColor.GREEN + rewardName(event.reward) + ChatColor.AQUA + ". " + reason + ".");
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 1.0f);
        closeEvent();
    }
    void shutdown() { closeEvent(); }
    private void closeEvent() {
        Active event = active;
        if (event == null) return;
        active = null;
        generation++;
        event.bar.removeAll();
        for (Entity entity : event.visuals) if (entity.isValid()) entity.remove();
        if (event.titan != null && event.titan.isValid()) event.titan.remove();
        if (event.objectiveBlock != null && event.oldBlockData != null
                && event.objectiveBlock.getType() == Material.CHEST)
            event.objectiveBlock.setBlockData(event.oldBlockData, false);
        // Revert ONLY blocks that still match our exact changes. Other events/builds remain untouched.
        List<Map.Entry<Block, BlockData>> edits = new ArrayList<>(event.impactOriginal.entrySet());
        java.util.Collections.reverse(edits);
        for (Map.Entry<Block, BlockData> edit : edits) {
            BlockData expected = event.impactPlaced.get(edit.getKey());
            if (expected != null && edit.getKey().getBlockData().matches(expected))
                edit.getKey().setBlockData(edit.getValue(), false);
        }
        if (event.type.equals("chosen")) removeAllRelics(event);
        for (long chunkPos : event.meteorChunkTickets) {
            int cx = (int) (chunkPos >> 32);
            int cz = (int) chunkPos;
            event.world.removePluginChunkTicket(cx, cz, plugin);
        }
        event.meteorChunkTickets.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onObjectiveClick(PlayerInteractEvent event) {
        Active current = active;
        if (current == null || (!current.type.equals("meteor") && !current.type.equals("treasure"))) return;
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || !event.getClickedBlock().equals(current.objectiveBlock)) return;
        event.setCancelled(true);
        if (eligible(event.getPlayer())) win(current, event.getPlayer().getUniqueId(), "First player to claim the Frost Chest");
    }
    @EventHandler(ignoreCancelled = true)
    public void onObjectiveBreak(BlockBreakEvent event) {
        if (active != null && (event.getBlock().equals(active.objectiveBlock)
                || active.impactPlaced.containsKey(event.getBlock()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(prefix() + "This ice structure belongs to the active Frost event! Claim the chest instead.");
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void protectObjectiveFromExplosion(EntityExplodeEvent event) {
        if (active != null && active.objectiveBlock != null)
            event.blockList().removeIf(block -> block.equals(active.objectiveBlock)
                    || active.impactPlaced.containsKey(block));
    }
    @EventHandler(ignoreCancelled = true)
    public void protectObjectiveFromBlockExplosion(BlockExplodeEvent event) {
        if (active != null && active.objectiveBlock != null)
            event.blockList().removeIf(block -> block.equals(active.objectiveBlock)
                    || active.impactPlaced.containsKey(block));
    }
    // Prevent environmental attrition / accidental vanilla damage to the raid boss.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void protectTitanFromEnvironment(org.bukkit.event.entity.EntityDamageEvent event) {
        Active current = active;
        if (current == null || !current.type.equals("titan") || current.titan == null
                || !current.titan.equals(event.getEntity())) return;
        if (!(event instanceof EntityDamageByEntityEvent)) event.setCancelled(true);
    }

    // Virtual raid health: counts real final hit damage, then absorbs it into the
    // larger raid pool. Native Warden health is purely a visual progress proxy.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTitanDamage(EntityDamageByEntityEvent event) {
        Active current = active;
        if (current == null || !current.type.equals("titan") || current.titan == null
                || !current.titan.equals(event.getEntity()) || current.titanDefeated) return;
        Player player = null;
        if (event.getDamager() instanceof Player direct) player = direct;
        else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player owner)
            player = owner;
        else if (event.getDamageSource().getCausingEntity() instanceof Player source) player = source;
        // Take the final amount BEFORE cancelling the vanilla hit.
        double actualDamage = Math.max(0.0, event.getFinalDamage());
        // Only participants can damage the boss; non-player sources cannot cheese the raid.
        event.setCancelled(true);
        if (player == null || !eligible(player)) return;
        double maxHit = Math.max(1.0, configuredDouble("titan-max-damage-per-hit", 24.0));
        double scale = Math.max(0.05, Math.min(1.0,
                configuredDouble("titan-damage-received-multiplier", 0.30)));
        double amount = Math.min(maxHit, actualDamage) * scale;
        if (amount <= 0) return;
        current.bossDamage.merge(player.getUniqueId(), amount, Double::sum);
        current.titanRaidHealth = Math.max(0.0, current.titanRaidHealth - amount);
        double fraction = current.titanRaidHealth / Math.max(1.0, current.titanMaxRaidHealth);
        if (current.titanRaidHealth > 0.0) {
            current.titan.setHealth(Math.max(1.0, 1000.0 * fraction));
            current.titan.playHurtAnimation(0.0f);
        } else {
            current.titanDefeated = true;
            current.world.playSound(current.titan.getLocation(), Sound.ENTITY_WITHER_DEATH, 1.5f, .7f);
            current.world.spawnParticle(Particle.SNOWFLAKE,
                    current.titan.getLocation().clone().add(0, 2, 0), 240, 4, 2, 4, .12);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (active == current) chooseTitanWinner(current);
            });
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTitanDeath(EntityDeathEvent event) {
        Active current = active;
        if (current == null || !current.type.equals("titan") || !event.getEntity().equals(current.titan)) return;
        event.getDrops().clear();
        Bukkit.getScheduler().runTask(plugin, () -> chooseTitanWinner(current));
    }
    @EventHandler(ignoreCancelled = true)
    public void onAssassinOrChosenDeath(PlayerDeathEvent event) {
        Active current = active;
        if (current == null) return;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null && event.getDamageSource().getCausingEntity() instanceof Player source)
            killer = source;
        if (current.type.equals("assassin")) {
            if (killer != null && !killer.equals(victim) && eligible(killer)
                    && victim.getUniqueId().equals(current.targets.get(killer.getUniqueId()))) {
                int kills = current.points.merge(killer.getUniqueId(), 1, Integer::sum);
                announce(killer.getName() + " eliminated their assigned target " + victim.getName() + "! Score: " + kills);
                assignTarget(current, killer);
            }
            current.targets.remove(victim.getUniqueId());
            return;
        }
        if (!current.type.equals("chosen")) return;
        boolean relicPresent = event.getDrops().removeIf(item -> isRelic(item, current));
        if (!relicPresent) {
            for (ItemStack item : victim.getInventory().getContents()) if (isRelic(item, current)) { relicPresent = true; break; }
        }
        if (!relicPresent) return;
        if (event.getKeepInventory()) {
            for (int slot = 0; slot < victim.getInventory().getSize(); slot++) {
                if (isRelic(victim.getInventory().getItem(slot), current))
                    victim.getInventory().setItem(slot, null);
            }
        }
        if (killer != null && killer.isOnline()) {
            final Player winningKiller = killer;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (active != current) return;
                removeAllRelics(current);
                giveRelic(winningKiller, current);
                current.chosenHolder = winningKiller.getUniqueId();
                announce(winningKiller.getName() + " defeated " + victim.getName() + " and claimed the Chosen Relic!");
            });
        } else {
            ItemStack drop = createRelic(current);
            event.getDrops().add(drop);
            current.chosenHolder = null;
            announce("The chosen relic dropped where " + victim.getName() + " was defeated!");
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Active current = active;
        if (current == null) return;
        current.bar.addPlayer(event.getPlayer());
        if (current.type.equals("assassin"))
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (active == current && eligible(event.getPlayer())) assignTarget(current, event.getPlayer());
            }, 20L);
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Active current = active;
        if (current == null) return;
        if (current.type.equals("assassin")) active.targets.remove(event.getPlayer().getUniqueId());
        if (!current.type.equals("chosen")) return;
        Player holder = event.getPlayer();
        boolean dropped = false;
        for (int slot = 0; slot < holder.getInventory().getSize(); slot++) {
            ItemStack item = holder.getInventory().getItem(slot);
            if (!isRelic(item, current)) continue;
            holder.getInventory().setItem(slot, null);
            holder.getWorld().dropItemNaturally(holder.getLocation(), item);
            dropped = true;
        }
        if (dropped) announce("The Chosen One disconnected; their relic dropped at their last position!");
    }
}
