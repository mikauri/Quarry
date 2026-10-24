package com.mikauri.quarry;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quarry — a from-scratch manhunt: runners race to kill the Ender Dragon while
 * hunters track them down with a dimension-aware compass. This is the plain
 * foundation; flavor features come later.
 */
public final class Quarry extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private enum State { IDLE, HEADSTART, RUNNING, PAUSED, ENDED }

    // Which game mode currently owns the plugin. NONE = nothing set up or played.
    // Assigning a role (or randomizing) claims the mode; /quarry stop releases it.
    // Only one mode can be set up or running at a time. HEADSTART doubles as the
    // pre-game countdown phase for both modes; the phase lives in `state`.
    private enum Mode { NONE, MANHUNT, RUSH }
    private Mode activeMode = Mode.NONE;

    private State state = State.IDLE;

    private final Set<UUID> hunters = new HashSet<>();
    private final Set<UUID> runners = new HashSet<>();
    // Dragon Rush participants. Everyone races to land the final blow on the dragon.
    private final Set<UUID> racers = new HashSet<>();
    private final Set<UUID> eliminated = new HashSet<>();
    private final Set<UUID> readyPlayers = new HashSet<>();
    // Per-participant lives remaining (shared by whichever mode is active).
    private final Map<UUID, Integer> livesLeft = new HashMap<>();

    // How many times each hunter has been killed by a runner this game — drives
    // the help-hunter Speed buff (Strength is from the start; Speed at 5 deaths).
    private final Map<UUID, Integer> hunterDeaths = new HashMap<>();

    // Per-runner last known location in each world, so the hunter compass can
    // point at a runner's "trail" (where they were last seen in the hunter's
    // dimension) instead of uselessly spinning when they're dimensions apart.
    private final Map<UUID, Map<String, Location>> lastSeen = new HashMap<>();

    private final Map<UUID, Long> compassCooldownUntil = new HashMap<>();

    // "Help hunter" catch-up buffs. When an op runs /quarry helphunter on, a hunter
    // gains Strength I after STRENGTH_AT deaths to a runner and Speed I after SPEED_AT.
    private boolean helpHunterEnabled;
    private static final int STRENGTH_AT = 5;  // runner-kills before a hunter gains Strength I
    private static final int SPEED_AT = 10;    // runner-kills before a hunter gains Speed I
    private static final int INFINITE = Integer.MAX_VALUE; // "until the game ends"

    // Config
    private int headStartSeconds;
    // The head start must be explicitly set by an op (via /quarry headstart <n>)
    // before any game can start. Persisted in config so it stays set across restarts.
    private boolean headStartSet;
    private int runnerLives;
    private long compassCooldownMs;

    // ---- Dragon Rush config (persisted in config.yml under rush.*) ----
    private int rushCountdownSeconds;   // pre-race freeze before racers are released
    private boolean rushPvp;            // may racers damage each other
    private int rushLives;              // lives per racer; 0 = infinite
    private boolean rushKeepInventory;  // keep inventory/XP on death
    private boolean rushScatter;        // random-spread racers at the start
    private int rushScatterRadius;      // blocks from spawn to scatter within
    private int rushTimeLimitMinutes;   // 0 = no limit; on expiry the race is a draw

    private NamespacedKey compassKey;
    private BukkitTask headStartTask;
    private BukkitTask rushTimeLimitTask;

    // Wall-clock start of the current game (epoch millis), for the elapsed timer.
    // 0 = no game timed. Persisted so the clock survives a restart. On resume,
    // gameStartMillis is shifted forward by the paused duration so paused time
    // doesn't inflate the elapsed clock.
    private long gameStartMillis;
    // When paused: the millis the pause began (freezes/excludes paused time). 0 = not paused.
    private long pauseStartMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCfg();
        compassKey = new NamespacedKey(this, "tracking_compass");
        if (getCommand("quarry") != null) {
            getCommand("quarry").setExecutor(this);
            getCommand("quarry").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        loadState();
        // Re-apply roles to anyone already online (e.g. a /reload mid-game).
        if (state == State.RUNNING || state == State.PAUSED) {
            for (Player p : Bukkit.getOnlinePlayers()) applyActiveRole(p);
        }
        getLogger().info("Quarry enabled.");
    }

    @Override
    public void onDisable() {
        saveState();
    }

    private void loadCfg() {
        reloadConfig();
        FileConfiguration c = getConfig();
        headStartSeconds = Math.max(0, c.getInt("head-start-seconds", 0));
        headStartSet = c.getBoolean("head-start-set", false);
        runnerLives = Math.max(1, c.getInt("runner-lives", 1));
        compassCooldownMs = Math.max(0, c.getLong("compass-cooldown-seconds", 0)) * 1000L;
        helpHunterEnabled = c.getBoolean("help-hunter", false);

        rushCountdownSeconds = Math.max(0, c.getInt("rush.countdown-seconds", 10));
        rushPvp = c.getBoolean("rush.pvp", false);
        rushLives = Math.max(0, c.getInt("rush.lives", 0));
        rushKeepInventory = c.getBoolean("rush.keep-inventory", false);
        rushScatter = c.getBoolean("rush.scatter", true);
        rushScatterRadius = Math.max(0, c.getInt("rush.scatter-radius", 200));
        rushTimeLimitMinutes = Math.max(0, c.getInt("rush.time-limit-minutes", 0));
    }

    // ------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Bare /quarry opens the mode picker for admins; others get the text status.
        if (args.length == 0) {
            if (sender instanceof Player && sender.hasPermission("quarry.admin")) {
                openModePicker((Player) sender);
                return true;
            }
            return status(sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "manhunt":
            case "mh":
                return manhuntCommand(sender, args);
            case "rush":
            case "dragonrush":
                return rushCommand(sender, args);
            // Mode-agnostic top-level commands act on whichever mode is active.
            case "stop":
                return stopActive(sender);
            case "status":
                return status(sender);
            case "reload":
                if (noPerm(sender)) return true;
                loadCfg();
                sender.sendMessage(ChatColor.GREEN + "[Quarry] Config reloaded.");
                return true;
            case "help":
                return showHelp(sender);
            case "gui":
            case "menu":
                if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Only players can open the menu."); return true; }
                if (noPerm(sender)) return true;
                openModePicker((Player) sender);
                return true;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand '" + sub + "'.");
                return showHelp(sender);
        }
    }

    // /quarry manhunt [sub ...] — the classic manhunt. Bare form opens its GUI.
    // `a` is the manhunt-local arg list (args with the leading "manhunt" dropped),
    // so a[0] is the manhunt subcommand and a[1] its argument — matching what the
    // original handlers expect.
    private boolean manhuntCommand(CommandSender sender, String[] args) {
        String[] a = Arrays.copyOfRange(args, 1, args.length);
        if (a.length == 0) {
            if (!(sender instanceof Player)) return status(sender);
            if (noPerm(sender)) return true;
            openMainMenu((Player) sender);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui":
            case "menu":
                if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Only players can open the menu."); return true; }
                if (noPerm(sender)) return true;
                openMainMenu((Player) sender);
                return true;
            case "hunter":
            case "runner":
                return assignRole(sender, sub, a);
            case "random":
                return assignRandom(sender, a);
            case "start":
                return startGame(sender);
            case "pause":
                return pauseGame(sender);
            case "resume":
                return resumeGame(sender);
            case "stop":
                return stopActive(sender);
            case "iamready":
                return setReady(sender);
            case "rules":
                return showRules(sender);
            case "helphunter":
                return helpHunter(sender, a);
            case "headstart":
                return setHeadStart(sender, a);
            case "status":
                return status(sender);
            default:
                sender.sendMessage(ChatColor.RED + "Unknown manhunt subcommand '" + sub + "'.");
                return showHelp(sender);
        }
    }

    // /quarry rush [sub ...] — Dragon Rush. Bare form opens its GUI.
    private boolean rushCommand(CommandSender sender, String[] args) {
        String[] a = Arrays.copyOfRange(args, 1, args.length);
        if (a.length == 0) {
            if (!(sender instanceof Player)) return status(sender);
            if (noPerm(sender)) return true;
            openRushMenu((Player) sender);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui":
            case "menu":
                if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Only players can open the menu."); return true; }
                if (noPerm(sender)) return true;
                openRushMenu((Player) sender);
                return true;
            case "racer":
                return assignRacer(sender, a);
            case "random":
                return assignRushRandom(sender, a);
            case "start":
                return startRush(sender);
            case "pause":
                return pauseGame(sender);
            case "resume":
                return resumeGame(sender);
            case "stop":
                return stopActive(sender);
            case "iamready":
                return setReady(sender);
            case "status":
                return status(sender);
            default:
                sender.sendMessage(ChatColor.RED + "Unknown rush subcommand '" + sub + "'.");
                return showHelp(sender);
        }
    }

    private boolean showHelp(CommandSender sender) {
        boolean admin = sender.hasPermission("quarry.admin");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== Quarry ===");
        if (admin) sender.sendMessage(ChatColor.GRAY + "Two modes: " + ChatColor.RED + "Manhunt" + ChatColor.GRAY + " and " + ChatColor.LIGHT_PURPLE + "Dragon Rush" + ChatColor.GRAY + ". Bare /quarry opens the picker.");
        if (admin) {
            sender.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Manhunt:");
            sender.sendMessage(ChatColor.YELLOW + "/quarry manhunt" + ChatColor.GRAY + " — open the manhunt setup GUI");
            sender.sendMessage(ChatColor.YELLOW + "/quarry manhunt hunter|runner <player>" + ChatColor.GRAY + " — assign a role");
            sender.sendMessage(ChatColor.YELLOW + "/quarry manhunt random [hunters]" + ChatColor.GRAY + " — shuffle into roles");
            sender.sendMessage(ChatColor.YELLOW + "/quarry manhunt headstart <seconds>" + ChatColor.GRAY + " — set the head start (required)");
            sender.sendMessage(ChatColor.YELLOW + "/quarry manhunt helphunter <on|off>" + ChatColor.GRAY + " — catch-up buffs");
            sender.sendMessage(ChatColor.YELLOW + "/quarry manhunt start|pause|resume" + ChatColor.GRAY + " — game control");
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Dragon Rush:");
            sender.sendMessage(ChatColor.YELLOW + "/quarry rush" + ChatColor.GRAY + " — open the Dragon Rush setup GUI");
            sender.sendMessage(ChatColor.YELLOW + "/quarry rush racer <player>" + ChatColor.GRAY + " — add/remove a racer");
            sender.sendMessage(ChatColor.YELLOW + "/quarry rush random" + ChatColor.GRAY + " — make everyone a racer");
            sender.sendMessage(ChatColor.YELLOW + "/quarry rush start|pause|resume" + ChatColor.GRAY + " — game control");
            sender.sendMessage(ChatColor.GRAY + "Shared: " + ChatColor.YELLOW + "/quarry stop | reload");
        }
        sender.sendMessage(ChatColor.YELLOW + "/quarry manhunt iamready" + ChatColor.GRAY + " (or /quarry rush iamready) — mark yourself ready");
        sender.sendMessage(ChatColor.YELLOW + "/quarry status" + ChatColor.GRAY + " — show roles, timer, and settings");
        sender.sendMessage(ChatColor.YELLOW + "/quarry manhunt rules" + ChatColor.GRAY + " — open the manhunt rulebook");
        if (admin) sender.sendMessage(ChatColor.YELLOW + "/quarry" + ChatColor.GRAY + " — open the mode picker (GUI)");
        return true;
    }

    // ============================================================= GUI
    // A prototype chest-menu control panel for admins. /quarry (no args) opens it.
    // The main menu holds the config toggles and game controls; a sub-menu lists
    // online players for role assignment. All clicks are handled in onInventoryClick.

    private static final String PICKER_TITLE = ChatColor.DARK_AQUA + "Quarry — Choose Mode";
    private static final String GUI_TITLE = ChatColor.DARK_AQUA + "Manhunt Setup";
    private static final String ROLES_TITLE = ChatColor.DARK_AQUA + "Manhunt — Assign Roles";
    private static final String RUSH_TITLE = ChatColor.DARK_AQUA + "Dragon Rush Setup";
    private static final String RUSH_ROLES_TITLE = ChatColor.DARK_AQUA + "Dragon Rush — Assign Racers";
    private static final String MENU_PICKER = "picker";
    private static final String MENU_MAIN = "main";
    private static final String MENU_ROLES = "roles";
    private static final String MENU_RUSH = "rush";
    private static final String MENU_RUSH_ROLES = "rushroles";
    // Main-menu slots (27-slot / 3-row chest), grouped by function:
    //   Row 0 — info:    Status (centered)
    //   Row 1 — setup:   [settings block] | [roles block]
    //   Row 2 — actions: [game controls]  | [utility]
    private static final int SLOT_STATUS = 4;
    // Settings block (left of row 1)
    private static final int SLOT_HEADSTART = 9, SLOT_LIVES = 10, SLOT_COOLDOWN = 11, SLOT_HELP = 12;
    // Roles block (right of row 1)
    private static final int SLOT_ROLES = 15, SLOT_RANDOM = 16;
    // Game controls (left of row 2)
    private static final int SLOT_START = 18, SLOT_PAUSE = 19, SLOT_STOP = 20;
    // Utility (right of row 2)
    private static final int SLOT_RULES = 24, SLOT_REFRESH = 25, SLOT_CLOSE = 26;
    // Divider columns between the left and right groups.
    private static final int[] DIVIDERS = {13, 14, 22, 23};

    // Mode-picker slots (27-slot chest).
    private static final int PICK_MANHUNT = 11, PICK_RUSH = 15, PICK_STATUS = 4, PICK_CLOSE = 26;

    // Dragon Rush setup slots (36-slot / 4-row chest).
    private static final int RUSH_STATUS = 4;
    // Settings row.
    private static final int RUSH_COUNTDOWN = 9, RUSH_PVP = 10, RUSH_LIVES = 11, RUSH_KEEPINV = 12,
            RUSH_SCATTER = 13, RUSH_SCATTER_RADIUS = 14, RUSH_TIMELIMIT = 15;
    // Roles row.
    private static final int RUSH_ROLES = 19, RUSH_RANDOM = 20;
    // Controls + utility row.
    private static final int RUSH_START = 27, RUSH_PAUSE = 28, RUSH_STOP = 29,
            RUSH_REFRESH = 34, RUSH_CLOSE = 35;

    /** Identifies our menus and carries which one it is. */
    private static final class QuarryHolder implements InventoryHolder {
        final String menu;
        Inventory inv;
        QuarryHolder(String menu) { this.menu = menu; }
        @Override public Inventory getInventory() { return inv; }
    }

    private ItemStack gui(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack head(Player owner, String name, String... lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(owner);
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            it.setItemMeta(meta);
        }
        return it;
    }

    // The top-level mode picker: pick Manhunt or Dragon Rush. If a game is already
    // set up or running, the corresponding button is highlighted and the other is
    // locked (only one mode at a time — stop first to switch).
    private void openModePicker(Player p) {
        QuarryHolder holder = new QuarryHolder(MENU_PICKER);
        Inventory inv = Bukkit.createInventory(holder, 27, PICKER_TITLE);
        holder.inv = inv;
        ItemStack filler = gui(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        String activeLine = activeMode == Mode.NONE ? ChatColor.DARK_GRAY + "No mode active"
                : ChatColor.GRAY + "Active: " + ChatColor.WHITE + (activeMode == Mode.MANHUNT ? "Manhunt" : "Dragon Rush")
                        + ChatColor.GRAY + " (" + state + ")";
        inv.setItem(PICK_STATUS, gui(Material.PAPER, ChatColor.GOLD + "Quarry", activeLine,
                ChatColor.DARK_GRAY + "Choose a mode to set up."));

        boolean rushLocked = activeMode == Mode.RUSH;
        boolean mhLocked = activeMode == Mode.MANHUNT;
        inv.setItem(PICK_MANHUNT, gui(Material.COMPASS,
                ChatColor.RED + "" + ChatColor.BOLD + "Manhunt",
                ChatColor.GRAY + "Runners race the dragon while",
                ChatColor.GRAY + "hunters track them down.",
                rushLocked ? ChatColor.DARK_RED + "Locked — a Dragon Rush is active." : ChatColor.YELLOW + "Click to open setup"));
        inv.setItem(PICK_RUSH, gui(Material.DRAGON_HEAD,
                ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Dragon Rush",
                ChatColor.GRAY + "Everyone races to land the",
                ChatColor.GRAY + "final blow on the Ender Dragon.",
                mhLocked ? ChatColor.DARK_RED + "Locked — a Manhunt is active." : ChatColor.YELLOW + "Click to open setup"));
        inv.setItem(PICK_CLOSE, gui(Material.BARRIER, ChatColor.RED + "Close"));
        p.openInventory(inv);
    }

    private void openMainMenu(Player p) {
        QuarryHolder holder = new QuarryHolder(MENU_MAIN);
        Inventory inv = Bukkit.createInventory(holder, 27, GUI_TITLE);
        holder.inv = inv;
        ItemStack filler = gui(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        ItemStack divider = gui(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int d : DIVIDERS) inv.setItem(d, divider);

        inv.setItem(SLOT_STATUS, gui(Material.PAPER, ChatColor.GOLD + "Status",
                ChatColor.GRAY + "State: " + ChatColor.WHITE + state,
                ChatColor.RED + "Hunters: " + ChatColor.WHITE + hunters.size()
                        + ChatColor.GRAY + "   " + ChatColor.GREEN + "Runners: " + ChatColor.WHITE + runners.size(),
                (state == State.RUNNING || state == State.PAUSED)
                        ? ChatColor.GRAY + "Elapsed: " + ChatColor.WHITE + elapsed() : ChatColor.DARK_GRAY + "No game running"));

        inv.setItem(SLOT_HEADSTART, gui(Material.CLOCK,
                ChatColor.GOLD + "Head Start: " + (headStartSet ? ChatColor.WHITE + (headStartSeconds + "s") : ChatColor.RED + "NOT SET"),
                ChatColor.GRAY + "Left-click " + ChatColor.WHITE + "+5s",
                ChatColor.GRAY + "Right-click " + ChatColor.WHITE + "-5s",
                headStartSet ? ChatColor.DARK_GRAY + "Required before a game can start." : ChatColor.RED + "Must be set before starting."));

        inv.setItem(SLOT_LIVES, gui(Material.TOTEM_OF_UNDYING,
                ChatColor.GOLD + "Runner Lives: " + ChatColor.WHITE + runnerLives,
                ChatColor.GRAY + "Left-click " + ChatColor.WHITE + "+1",
                ChatColor.GRAY + "Right-click " + ChatColor.WHITE + "-1"));

        inv.setItem(SLOT_COOLDOWN, gui(Material.COMPASS,
                ChatColor.GOLD + "Compass Cooldown: " + ChatColor.WHITE + (compassCooldownMs / 1000) + "s",
                ChatColor.GRAY + "Left-click " + ChatColor.WHITE + "+1s",
                ChatColor.GRAY + "Right-click " + ChatColor.WHITE + "-1s"));

        inv.setItem(SLOT_HELP, gui(helpHunterEnabled ? Material.BLAZE_POWDER : Material.GUNPOWDER,
                ChatColor.GOLD + "Help Hunter: " + (helpHunterEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"),
                ChatColor.GRAY + "Strength I at " + STRENGTH_AT + " deaths, Speed I at " + SPEED_AT,
                ChatColor.GRAY + "Hunters keep their inventory on death",
                ChatColor.YELLOW + "Click to toggle"));

        inv.setItem(SLOT_ROLES, gui(Material.PLAYER_HEAD,
                ChatColor.AQUA + "Assign Roles",
                ChatColor.GRAY + "Hunters: " + ChatColor.WHITE + names(hunters),
                ChatColor.GRAY + "Runners: " + ChatColor.WHITE + names(runners),
                ChatColor.YELLOW + "Click to open"));

        inv.setItem(SLOT_RANDOM, gui(Material.ENDER_PEARL,
                ChatColor.AQUA + "Randomize Roles",
                ChatColor.GRAY + "Shuffle all online players (1 hunter)."));

        inv.setItem(SLOT_START, gui(Material.LIME_CONCRETE,
                ChatColor.GREEN + "" + ChatColor.BOLD + "START GAME",
                ChatColor.GRAY + "Begins the hunt with current settings."));

        boolean paused = state == State.PAUSED;
        inv.setItem(SLOT_PAUSE, gui(paused ? Material.LIME_DYE : Material.YELLOW_DYE,
                (paused ? ChatColor.GREEN + "Resume" : ChatColor.YELLOW + "Pause"),
                ChatColor.GRAY + (paused ? "Continue the running game." : "Freeze the running game.")));

        inv.setItem(SLOT_STOP, gui(Material.RED_CONCRETE,
                ChatColor.RED + "" + ChatColor.BOLD + "STOP / RESET",
                ChatColor.GRAY + "End the game and clear roles."));

        inv.setItem(SLOT_RULES, gui(Material.WRITTEN_BOOK, ChatColor.GOLD + "Rules", ChatColor.GRAY + "Open the rulebook."));
        inv.setItem(SLOT_REFRESH, gui(Material.SUNFLOWER, ChatColor.YELLOW + "Refresh"));
        inv.setItem(SLOT_CLOSE, gui(Material.BARRIER, ChatColor.RED + "Close"));

        p.openInventory(inv);
    }

    private void openRolesMenu(Player p) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int need = Math.min(45, Math.max(9, ((online.size() + 8) / 9) * 9));
        int size = need + 9; // extra row for the back button
        QuarryHolder holder = new QuarryHolder(MENU_ROLES);
        Inventory inv = Bukkit.createInventory(holder, size, ROLES_TITLE);
        holder.inv = inv;
        for (int i = 0; i < online.size() && i < size - 9; i++) {
            Player t = online.get(i);
            UUID id = t.getUniqueId();
            boolean hasRole = hunters.contains(id) || runners.contains(id);
            String role = hunters.contains(id) ? ChatColor.RED + "HUNTER"
                    : runners.contains(id) ? ChatColor.GREEN + "RUNNER" : ChatColor.GRAY + "none";
            String ready = !hasRole ? ChatColor.DARK_GRAY + "—"
                    : readyPlayers.contains(id) ? ChatColor.GREEN + "✔ Ready"
                    : ChatColor.RED + "✘ Not ready";
            inv.setItem(i, head(t, ChatColor.WHITE + t.getName(),
                    ChatColor.GRAY + "Role: " + role,
                    ChatColor.GRAY + "Status: " + ready,
                    ChatColor.YELLOW + "Click to cycle: none → hunter → runner"));
        }
        inv.setItem(size - 1, gui(Material.ARROW, ChatColor.YELLOW + "Back"));
        p.openInventory(inv);
    }

    // ---------------------------------------------------- Dragon Rush menus

    private void openRushMenu(Player p) {
        QuarryHolder holder = new QuarryHolder(MENU_RUSH);
        Inventory inv = Bukkit.createInventory(holder, 36, RUSH_TITLE);
        holder.inv = inv;
        ItemStack filler = gui(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) inv.setItem(i, filler);

        int alive = 0;
        for (UUID id : racers) if (!eliminated.contains(id)) alive++;
        inv.setItem(RUSH_STATUS, gui(Material.PAPER, ChatColor.GOLD + "Status",
                ChatColor.GRAY + "State: " + ChatColor.WHITE + state,
                ChatColor.LIGHT_PURPLE + "Racers: " + ChatColor.WHITE + racers.size()
                        + ((state == State.RUNNING || state == State.PAUSED) ? ChatColor.GRAY + "  (" + alive + " alive)" : ""),
                (state == State.RUNNING || state == State.PAUSED)
                        ? ChatColor.GRAY + "Elapsed: " + ChatColor.WHITE + elapsed() : ChatColor.DARK_GRAY + "No race running"));

        inv.setItem(RUSH_COUNTDOWN, gui(Material.CLOCK,
                ChatColor.GOLD + "Countdown: " + ChatColor.WHITE + rushCountdownSeconds + "s",
                ChatColor.GRAY + "Freeze before racers are released.",
                ChatColor.GRAY + "Left-click " + ChatColor.WHITE + "+5s",
                ChatColor.GRAY + "Right-click " + ChatColor.WHITE + "-5s"));

        inv.setItem(RUSH_PVP, gui(rushPvp ? Material.DIAMOND_SWORD : Material.SHIELD,
                ChatColor.GOLD + "PvP: " + (rushPvp ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"),
                ChatColor.GRAY + "Whether racers can damage each other.",
                ChatColor.YELLOW + "Click to toggle"));

        inv.setItem(RUSH_LIVES, gui(Material.TOTEM_OF_UNDYING,
                ChatColor.GOLD + "Lives: " + ChatColor.WHITE + (rushLives == 0 ? "∞ (infinite)" : rushLives),
                ChatColor.GRAY + "Deaths before a racer is out.",
                ChatColor.GRAY + "Left-click " + ChatColor.WHITE + "+1",
                ChatColor.GRAY + "Right-click " + ChatColor.WHITE + "-1 (0 = infinite)"));

        inv.setItem(RUSH_KEEPINV, gui(rushKeepInventory ? Material.ENDER_CHEST : Material.CHEST,
                ChatColor.GOLD + "Keep Inventory: " + (rushKeepInventory ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"),
                ChatColor.GRAY + "Racers keep items/XP on death.",
                ChatColor.YELLOW + "Click to toggle"));

        inv.setItem(RUSH_SCATTER, gui(rushScatter ? Material.ENDER_PEARL : Material.SNOWBALL,
                ChatColor.GOLD + "Scatter at Start: " + (rushScatter ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"),
                ChatColor.GRAY + "Random-spread racers around spawn.",
                ChatColor.YELLOW + "Click to toggle"));

        inv.setItem(RUSH_SCATTER_RADIUS, gui(Material.MAP,
                ChatColor.GOLD + "Scatter Radius: " + ChatColor.WHITE + rushScatterRadius + " blocks",
                ChatColor.GRAY + "How far from spawn to scatter.",
                ChatColor.GRAY + "Left-click " + ChatColor.WHITE + "+50",
                ChatColor.GRAY + "Right-click " + ChatColor.WHITE + "-50",
                rushScatter ? ChatColor.DARK_GRAY + "Used when scatter is ON." : ChatColor.DARK_RED + "Scatter is OFF."));

        inv.setItem(RUSH_TIMELIMIT, gui(Material.CLOCK,
                ChatColor.GOLD + "Time Limit: " + ChatColor.WHITE + (rushTimeLimitMinutes == 0 ? "none" : rushTimeLimitMinutes + " min"),
                ChatColor.GRAY + "Race is a draw if it expires.",
                ChatColor.GRAY + "Left-click " + ChatColor.WHITE + "+5 min",
                ChatColor.GRAY + "Right-click " + ChatColor.WHITE + "-5 min (0 = none)"));

        inv.setItem(RUSH_ROLES, gui(Material.PLAYER_HEAD,
                ChatColor.AQUA + "Assign Racers",
                ChatColor.GRAY + "Racers: " + ChatColor.WHITE + names(racers),
                ChatColor.YELLOW + "Click to open"));

        inv.setItem(RUSH_RANDOM, gui(Material.NETHER_STAR,
                ChatColor.AQUA + "Everyone Races",
                ChatColor.GRAY + "Make all online players racers."));

        inv.setItem(RUSH_START, gui(Material.LIME_CONCRETE,
                ChatColor.GREEN + "" + ChatColor.BOLD + "START RACE",
                ChatColor.GRAY + "Begin the countdown (min 2 racers)."));

        boolean paused = state == State.PAUSED;
        inv.setItem(RUSH_PAUSE, gui(paused ? Material.LIME_DYE : Material.YELLOW_DYE,
                (paused ? ChatColor.GREEN + "Resume" : ChatColor.YELLOW + "Pause"),
                ChatColor.GRAY + (paused ? "Continue the running race." : "Freeze the running race.")));

        inv.setItem(RUSH_STOP, gui(Material.RED_CONCRETE,
                ChatColor.RED + "" + ChatColor.BOLD + "STOP / RESET",
                ChatColor.GRAY + "End the race and clear racers."));

        inv.setItem(RUSH_REFRESH, gui(Material.SUNFLOWER, ChatColor.YELLOW + "Refresh"));
        inv.setItem(RUSH_CLOSE, gui(Material.BARRIER, ChatColor.RED + "Close"));
        p.openInventory(inv);
    }

    private void openRushRolesMenu(Player p) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int need = Math.min(45, Math.max(9, ((online.size() + 8) / 9) * 9));
        int size = need + 9;
        QuarryHolder holder = new QuarryHolder(MENU_RUSH_ROLES);
        Inventory inv = Bukkit.createInventory(holder, size, RUSH_ROLES_TITLE);
        holder.inv = inv;
        for (int i = 0; i < online.size() && i < size - 9; i++) {
            Player t = online.get(i);
            UUID id = t.getUniqueId();
            boolean racer = racers.contains(id);
            String ready = !racer ? ChatColor.DARK_GRAY + "—"
                    : readyPlayers.contains(id) ? ChatColor.GREEN + "✔ Ready"
                    : ChatColor.RED + "✘ Not ready";
            inv.setItem(i, head(t, ChatColor.WHITE + t.getName(),
                    ChatColor.GRAY + "Racer: " + (racer ? ChatColor.GREEN + "YES" : ChatColor.GRAY + "no"),
                    ChatColor.GRAY + "Status: " + ready,
                    ChatColor.YELLOW + "Click to toggle racer"));
        }
        inv.setItem(size - 1, gui(Material.ARROW, ChatColor.YELLOW + "Back"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (!(h instanceof QuarryHolder)) return;
        e.setCancelled(true); // our menus are display-only; never let items move
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return; // ignore clicks in the player's own inv
        Player p = (Player) e.getWhoClicked();
        String menu = ((QuarryHolder) h).menu;
        if (menu.equals(MENU_PICKER)) handlePickerClick(p, e);
        else if (menu.equals(MENU_MAIN)) handleMainClick(p, e);
        else if (menu.equals(MENU_ROLES)) handleRolesClick(p, e);
        else if (menu.equals(MENU_RUSH)) handleRushClick(p, e);
        else if (menu.equals(MENU_RUSH_ROLES)) handleRushRolesClick(p, e);
    }

    private void handlePickerClick(Player p, InventoryClickEvent e) {
        switch (e.getRawSlot()) {
            case PICK_MANHUNT:
                if (activeMode == Mode.RUSH) { p.sendMessage(ChatColor.RED + "A Dragon Rush is active — /quarry stop first."); return; }
                Bukkit.getScheduler().runTask(this, () -> openMainMenu(p));
                break;
            case PICK_RUSH:
                if (activeMode == Mode.MANHUNT) { p.sendMessage(ChatColor.RED + "A Manhunt is active — /quarry stop first."); return; }
                Bukkit.getScheduler().runTask(this, () -> openRushMenu(p));
                break;
            case PICK_CLOSE:
                p.closeInventory();
                break;
            default:
                break;
        }
    }

    private void handleMainClick(Player p, InventoryClickEvent e) {
        boolean left = e.isLeftClick();
        switch (e.getRawSlot()) {
            case SLOT_HEADSTART:
                headStartSeconds = Math.max(0, headStartSeconds + (left ? 5 : -5));
                headStartSet = true;
                getConfig().set("head-start-seconds", headStartSeconds);
                getConfig().set("head-start-set", true);
                saveConfig();
                reopenMain(p);
                break;
            case SLOT_LIVES:
                runnerLives = Math.max(1, runnerLives + (left ? 1 : -1));
                getConfig().set("runner-lives", runnerLives);
                saveConfig();
                reopenMain(p);
                break;
            case SLOT_COOLDOWN:
                long secs = Math.max(0, compassCooldownMs / 1000 + (left ? 1 : -1));
                compassCooldownMs = secs * 1000L;
                getConfig().set("compass-cooldown-seconds", secs);
                saveConfig();
                reopenMain(p);
                break;
            case SLOT_HELP:
                helpHunterEnabled = !helpHunterEnabled;
                getConfig().set("help-hunter", helpHunterEnabled);
                saveConfig();
                if (helpHunterEnabled) { for (UUID id : hunters) { Player hp = Bukkit.getPlayer(id); if (hp != null) applyHunterBuffs(hp); } }
                else clearHunterBuffs();
                reopenMain(p);
                break;
            case SLOT_ROLES:
                Bukkit.getScheduler().runTask(this, () -> openRolesMenu(p));
                break;
            case SLOT_RANDOM:
                assignRandom(p, new String[]{"random"});
                reopenMain(p);
                break;
            case SLOT_START:
                p.closeInventory();
                startGame(p);
                break;
            case SLOT_PAUSE:
                if (state == State.RUNNING) pauseGame(p);
                else if (state == State.PAUSED) resumeGame(p);
                else p.sendMessage(ChatColor.RED + "No running game to pause.");
                reopenMain(p);
                break;
            case SLOT_STOP:
                stopActive(p);
                reopenMain(p);
                break;
            case SLOT_RULES:
                p.closeInventory();
                showRules(p);
                break;
            case SLOT_REFRESH:
                reopenMain(p);
                break;
            case SLOT_CLOSE:
                p.closeInventory();
                break;
            default:
                break; // filler / decorative
        }
    }

    private void handleRolesClick(Player p, InventoryClickEvent e) {
        int size = e.getView().getTopInventory().getSize();
        if (e.getRawSlot() == size - 1) { // back
            reopenMain(p);
            return;
        }
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
        SkullMeta sm = (SkullMeta) clicked.getItemMeta();
        if (sm == null || sm.getOwningPlayer() == null) return;
        UUID id = sm.getOwningPlayer().getUniqueId();
        if (state != State.IDLE && state != State.ENDED) {
            p.sendMessage(ChatColor.RED + "Roles can't be changed mid-game (stop the game first).");
            return;
        }
        if (activeMode == Mode.RUSH) {
            p.sendMessage(ChatColor.RED + "A Dragon Rush is set up — /quarry stop first.");
            return;
        }
        // Cycle none -> hunter -> runner -> none.
        if (hunters.contains(id)) { hunters.remove(id); runners.add(id); }
        else if (runners.contains(id)) { runners.remove(id); }
        else { hunters.add(id); }
        // Claim the mode while anyone holds a role, and release it again once the
        // last role is cleared — otherwise Dragon Rush stays locked out.
        if (hunters.isEmpty() && runners.isEmpty()) releaseModeIfIdle(); else claimModeIfIdle(Mode.MANHUNT);
        readyPlayers.clear(); // role change invalidates readiness
        Bukkit.getScheduler().runTask(this, () -> openRolesMenu(p));
    }

    private void reopenMain(Player p) {
        Bukkit.getScheduler().runTask(this, () -> openMainMenu(p));
    }

    private void reopenRush(Player p) {
        Bukkit.getScheduler().runTask(this, () -> openRushMenu(p));
    }

    private void handleRushClick(Player p, InventoryClickEvent e) {
        boolean left = e.isLeftClick();
        switch (e.getRawSlot()) {
            case RUSH_COUNTDOWN:
                rushCountdownSeconds = Math.max(0, rushCountdownSeconds + (left ? 5 : -5));
                getConfig().set("rush.countdown-seconds", rushCountdownSeconds);
                saveConfig();
                reopenRush(p);
                break;
            case RUSH_PVP:
                rushPvp = !rushPvp;
                getConfig().set("rush.pvp", rushPvp);
                saveConfig();
                reopenRush(p);
                break;
            case RUSH_LIVES:
                rushLives = Math.max(0, rushLives + (left ? 1 : -1));
                getConfig().set("rush.lives", rushLives);
                saveConfig();
                reopenRush(p);
                break;
            case RUSH_KEEPINV:
                rushKeepInventory = !rushKeepInventory;
                getConfig().set("rush.keep-inventory", rushKeepInventory);
                saveConfig();
                reopenRush(p);
                break;
            case RUSH_SCATTER:
                rushScatter = !rushScatter;
                getConfig().set("rush.scatter", rushScatter);
                saveConfig();
                reopenRush(p);
                break;
            case RUSH_SCATTER_RADIUS:
                rushScatterRadius = Math.max(0, rushScatterRadius + (left ? 50 : -50));
                getConfig().set("rush.scatter-radius", rushScatterRadius);
                saveConfig();
                reopenRush(p);
                break;
            case RUSH_TIMELIMIT:
                rushTimeLimitMinutes = Math.max(0, rushTimeLimitMinutes + (left ? 5 : -5));
                getConfig().set("rush.time-limit-minutes", rushTimeLimitMinutes);
                saveConfig();
                reopenRush(p);
                break;
            case RUSH_ROLES:
                Bukkit.getScheduler().runTask(this, () -> openRushRolesMenu(p));
                break;
            case RUSH_RANDOM:
                assignRushRandom(p, new String[]{"random"});
                reopenRush(p);
                break;
            case RUSH_START:
                p.closeInventory();
                startRush(p);
                break;
            case RUSH_PAUSE:
                if (state == State.RUNNING) pauseGame(p);
                else if (state == State.PAUSED) resumeGame(p);
                else p.sendMessage(ChatColor.RED + "No running race to pause.");
                reopenRush(p);
                break;
            case RUSH_STOP:
                stopActive(p);
                reopenRush(p);
                break;
            case RUSH_REFRESH:
                reopenRush(p);
                break;
            case RUSH_CLOSE:
                p.closeInventory();
                break;
            default:
                break;
        }
    }

    private void handleRushRolesClick(Player p, InventoryClickEvent e) {
        int size = e.getView().getTopInventory().getSize();
        if (e.getRawSlot() == size - 1) { reopenRush(p); return; }
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
        SkullMeta sm = (SkullMeta) clicked.getItemMeta();
        if (sm == null || sm.getOwningPlayer() == null) return;
        UUID id = sm.getOwningPlayer().getUniqueId();
        if (state != State.IDLE && state != State.ENDED) {
            p.sendMessage(ChatColor.RED + "Racers can't be changed mid-race (stop the race first).");
            return;
        }
        if (activeMode == Mode.MANHUNT) {
            p.sendMessage(ChatColor.RED + "A Manhunt is set up — /quarry stop first.");
            return;
        }
        if (racers.contains(id)) racers.remove(id); else racers.add(id);
        if (racers.isEmpty()) releaseModeIfIdle(); else claimModeIfIdle(Mode.RUSH);
        readyPlayers.clear();
        Bukkit.getScheduler().runTask(this, () -> openRushRolesMenu(p));
    }

    // ------------------------------------------------------------- mode claim

    // Assigning a role claims the mode so the two modes can't be set up at once.
    // Claim only happens from a clean slate (NONE + not mid-game).
    private void claimModeIfIdle(Mode m) {
        if (activeMode == Mode.NONE && (state == State.IDLE || state == State.ENDED)) activeMode = m;
    }

    // Release the claim back to NONE when the last participant of a not-yet-started
    // setup is removed, so the other mode becomes selectable again.
    private void releaseModeIfIdle() {
        if ((state == State.IDLE || state == State.ENDED) && hunters.isEmpty() && runners.isEmpty() && racers.isEmpty()) {
            activeMode = Mode.NONE;
        }
    }

    private boolean assignRole(CommandSender sender, String role, String[] args) {
        if (noPerm(sender)) return true;
        if (activeMode == Mode.RUSH) {
            sender.sendMessage(ChatColor.RED + "A Dragon Rush is set up — /quarry stop first to switch to Manhunt.");
            return true;
        }
        if (state != State.IDLE && state != State.ENDED) {
            sender.sendMessage(ChatColor.RED + "Assign roles before starting (run /quarry stop to reset).");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /quarry manhunt " + role + " <player>");
            return true;
        }
        Player t = Bukkit.getPlayerExact(args[1]);
        if (t == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
            return true;
        }
        UUID id = t.getUniqueId();
        hunters.remove(id);
        runners.remove(id);
        if (role.equals("hunter")) hunters.add(id); else runners.add(id);
        claimModeIfIdle(Mode.MANHUNT);
        readyPlayers.clear();
        sender.sendMessage(ChatColor.GREEN + t.getName() + " is now a " + role.toUpperCase(Locale.ROOT) + ".");
        t.sendMessage(ChatColor.AQUA + "You are a " + role.toUpperCase(Locale.ROOT) + " in the next Quarry game.");
        // Hunters carry a live tracking compass from assignment; assigning a
        // runner immediately re-aims every hunter's compass at the runners.
        pointHuntersAtRunners();
        return true;
    }

    // /quarry manhunt random [hunters]  -- shuffle all online players into roles.
    private boolean assignRandom(CommandSender sender, String[] args) {
        if (noPerm(sender)) return true;
        if (activeMode == Mode.RUSH) {
            sender.sendMessage(ChatColor.RED + "A Dragon Rush is set up — /quarry stop first to switch to Manhunt.");
            return true;
        }
        if (state != State.IDLE && state != State.ENDED) {
            sender.sendMessage(ChatColor.RED + "Assign roles before starting (run /quarry stop to reset).");
            return true;
        }
        List<Player> pool = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (pool.size() < 2) {
            sender.sendMessage(ChatColor.RED + "Need at least 2 online players to randomize.");
            return true;
        }
        int numHunters = 1;
        if (args.length >= 2) {
            try {
                numHunters = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Hunter count must be a number.");
                return true;
            }
        }
        if (numHunters < 1 || numHunters > pool.size() - 1) {
            sender.sendMessage(ChatColor.RED + "Hunter count must be between 1 and " + (pool.size() - 1)
                    + " (at least one runner is required).");
            return true;
        }

        Collections.shuffle(pool);
        hunters.clear();
        runners.clear();
        readyPlayers.clear();
        for (int i = 0; i < pool.size(); i++) {
            Player p = pool.get(i);
            if (i < numHunters) {
                hunters.add(p.getUniqueId());
                p.sendMessage(ChatColor.RED + "You were randomly assigned: " + ChatColor.BOLD + "HUNTER");
            } else {
                runners.add(p.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "You were randomly assigned: " + ChatColor.BOLD + "RUNNER");
            }
        }
        claimModeIfIdle(Mode.MANHUNT);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] Roles randomized!");
        Bukkit.broadcastMessage(ChatColor.RED + "  Hunters: " + ChatColor.WHITE + names(hunters));
        Bukkit.broadcastMessage(ChatColor.GREEN + "  Runners: " + ChatColor.WHITE + names(runners));
        pointHuntersAtRunners();
        return true;
    }

    // ------------------------------------------------------------- rush roles

    // /quarry rush racer <player> — toggle a player in/out of the racer pool.
    private boolean assignRacer(CommandSender sender, String[] args) {
        if (noPerm(sender)) return true;
        if (activeMode == Mode.MANHUNT) {
            sender.sendMessage(ChatColor.RED + "A Manhunt is set up — /quarry stop first to switch to Dragon Rush.");
            return true;
        }
        if (state != State.IDLE && state != State.ENDED) {
            sender.sendMessage(ChatColor.RED + "Assign racers before starting (run /quarry stop to reset).");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /quarry rush racer <player>");
            return true;
        }
        Player t = Bukkit.getPlayerExact(args[1]);
        if (t == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
            return true;
        }
        UUID id = t.getUniqueId();
        if (racers.remove(id)) {
            sender.sendMessage(ChatColor.YELLOW + t.getName() + " is no longer a racer.");
            t.sendMessage(ChatColor.YELLOW + "You were removed from the Dragon Rush.");
            releaseModeIfIdle();
        } else {
            racers.add(id);
            claimModeIfIdle(Mode.RUSH);
            sender.sendMessage(ChatColor.GREEN + t.getName() + " is now a RACER.");
            t.sendMessage(ChatColor.LIGHT_PURPLE + "You are a RACER in the next Dragon Rush.");
        }
        readyPlayers.clear();
        return true;
    }

    // /quarry rush random — make every online player a racer.
    private boolean assignRushRandom(CommandSender sender, String[] args) {
        if (noPerm(sender)) return true;
        if (activeMode == Mode.MANHUNT) {
            sender.sendMessage(ChatColor.RED + "A Manhunt is set up — /quarry stop first to switch to Dragon Rush.");
            return true;
        }
        if (state != State.IDLE && state != State.ENDED) {
            sender.sendMessage(ChatColor.RED + "Assign racers before starting (run /quarry stop to reset).");
            return true;
        }
        List<Player> pool = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (pool.size() < 2) {
            sender.sendMessage(ChatColor.RED + "Need at least 2 online players for a race.");
            return true;
        }
        racers.clear();
        readyPlayers.clear();
        for (Player p : pool) {
            racers.add(p.getUniqueId());
            p.sendMessage(ChatColor.LIGHT_PURPLE + "You are a " + ChatColor.BOLD + "RACER" + ChatColor.LIGHT_PURPLE + " in the next Dragon Rush!");
        }
        claimModeIfIdle(Mode.RUSH);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] " + racers.size() + " racers are in the Dragon Rush!");
        return true;
    }

    private boolean setReady(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player p = (Player) sender;
        UUID id = p.getUniqueId();
        if (!hunters.contains(id) && !runners.contains(id) && !racers.contains(id)) {
            p.sendMessage(ChatColor.RED + "You are not a participant in the current game.");
            return true;
        }
        if (state != State.IDLE && state != State.ENDED) {
            p.sendMessage(ChatColor.RED + "A game is already in progress.");
            return true;
        }
        if (readyPlayers.add(id)) {
            int totalParticipants = activeMode == Mode.RUSH ? racers.size() : hunters.size() + runners.size();
            Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "[Quarry] " + p.getName() + " is now READY! ("
                    + readyPlayers.size() + "/" + totalParticipants + ")");
            if (readyPlayers.size() >= totalParticipants) {
                String how = activeMode == Mode.RUSH
                        ? "The race can now be started via `/quarry rush start`."
                        : "The game can now be started via `/quarry manhunt start` or by a runner punching a hunter.";
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] All players are ready! " + how);
            }
        } else {
            p.sendMessage(ChatColor.YELLOW + "You are already ready.");
        }
        return true;
    }

    private boolean showRules(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.GOLD + "=== Quarry Manhunt Rules ===");
            sender.sendMessage(ChatColor.YELLOW + "1. " + ChatColor.WHITE + "The runner must return through the same portal they used to enter the Nether. (Prevents traveling massive distances in the Nether to easily escape).");
            sender.sendMessage(ChatColor.YELLOW + "2. " + ChatColor.WHITE + "Hunters are prohibited from trapping the exit side of the Nether portal. (Camping and waiting is allowed, but no trapping).");
            sender.sendMessage(ChatColor.YELLOW + "3. " + ChatColor.WHITE + "Once the runner throws their first Ender Eye, they must commit to that specific Stronghold, unless there is a valid reason not to (e.g. portal is broken).");
            return true;
        }

        Player p = (Player) sender;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setTitle("Quarry Rules");
            meta.setAuthor("Quarry System");

            List<String> pages = new ArrayList<>();
            // Page 1
            pages.add(ChatColor.DARK_BLUE + "" + ChatColor.BOLD + "Rule 1: Nether Portals\n\n" 
                    + ChatColor.BLACK + "The runner must return through the same portal they used to enter the Nether.\n\n"
                    + ChatColor.DARK_GRAY + "Prevents traveling massive distances in the Nether to easily escape.");
            // Page 2
            pages.add(ChatColor.DARK_BLUE + "" + ChatColor.BOLD + "Rule 2: Trapping\n\n"
                    + ChatColor.BLACK + "Hunters are prohibited from trapping the exit side of the Nether portal.\n\n"
                    + ChatColor.DARK_GRAY + "Camping and waiting is allowed, but not trapping.");
            // Page 3
            pages.add(ChatColor.DARK_BLUE + "" + ChatColor.BOLD + "Rule 3: Stronghold\n\n"
                    + ChatColor.BLACK + "Once the runner throws their first Ender Eye, they must commit to that specific Stronghold.\n\n"
                    + ChatColor.DARK_GRAY + "Unless there is a valid reason not to (e.g. portal is broken).");

            meta.setPages(pages);
            book.setItemMeta(meta);
            p.openBook(book);
        }
        return true;
    }

    private List<String> getUnreadyNames() {
        // Always work on a copy — never alias the live roster sets.
        Set<UUID> pool = new HashSet<>();
        if (activeMode == Mode.RUSH) {
            pool.addAll(racers);
        } else {
            pool.addAll(runners);
            pool.addAll(hunters);
        }
        List<String> unready = new ArrayList<>();
        for (UUID id : pool) {
            if (!readyPlayers.contains(id)) unready.add(nameOf(id));
        }
        return unready;
    }

    private boolean startGame(CommandSender sender) {
        if (noPerm(sender)) return true;
        if (state == State.HEADSTART || state == State.RUNNING) {
            sender.sendMessage(ChatColor.RED + "A game is already running.");
            return true;
        }
        if (runners.isEmpty() || hunters.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Need at least one runner and one hunter.");
            return true;
        }
        if (!headStartSet) {
            sender.sendMessage(ChatColor.RED + "An op must set the head start first: /quarry headstart <seconds>");
            return true;
        }
        List<String> unready = getUnreadyNames();
        if (!unready.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Cannot start game. The following players are not ready: " 
                    + String.join(", ", unready));
            return true;
        }
        startTheGameSequence();
        return true;
    }

    private void startTheGameSequence() {
        activeMode = Mode.MANHUNT;
        eliminated.clear();
        livesLeft.clear();
        lastSeen.clear();
        hunterDeaths.clear(); // fresh Speed-threshold counters each game
        gameStartMillis = System.currentTimeMillis(); // start the elapsed clock
        pauseStartMillis = 0;
        for (UUID id : runners) livesLeft.put(id, runnerLives);

        // Everyone into survival, full health/food, clear old compasses.
        for (Player p : participants()) {
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            removeTrackingCompasses(p);
        }

        if (headStartSeconds > 0) {
            state = State.HEADSTART;
            // Hunters are frozen (onMove), blinded, and can't break/place blocks
            // (onBlockBreak/onBlockPlace) for the whole head start.
            for (UUID id : hunters) {
                Player h = Bukkit.getPlayer(id);
                if (h != null) h.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, headStartSeconds * 20 + 40, 0, false, false));
            }
            broadcastTitle(ChatColor.GOLD + "The Hunt Begins", ChatColor.GRAY + "Runners, run!");
            Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] " + ChatColor.YELLOW + "Runners have a "
                    + headStartSeconds + "s head start!");

            final int[] left = { headStartSeconds };
            headStartTask = new BukkitRunnable() {
                @Override public void run() {
                    if (state != State.HEADSTART) { cancel(); return; }
                    if (left[0] <= 0) {
                        cancel();
                        releaseHunters();
                        return;
                    }
                    if (left[0] <= 5 || left[0] % 10 == 0) {
                        for (UUID id : hunters) {
                            Player h = Bukkit.getPlayer(id);
                            if (h != null) {
                                h.sendTitle(ChatColor.RED + "" + left[0], ChatColor.GRAY + "until release", 0, 25, 5);
                                h.playSound(h.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                            }
                        }
                        // Runners see the same countdown so they know how long their lead lasts.
                        for (UUID id : runners) {
                            Player r = Bukkit.getPlayer(id);
                            if (r != null) {
                                r.sendTitle(ChatColor.GREEN + "" + left[0], ChatColor.GRAY + "until hunters release — RUN!", 0, 25, 5);
                                r.playSound(r.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
                            }
                        }
                    }
                    left[0]--;
                }
            }.runTaskTimer(this, 0L, 20L);
            saveState();
        } else {
            releaseHunters();
        }
    }

    private void releaseHunters() {
        state = State.RUNNING;
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h == null) continue;
            h.removePotionEffect(PotionEffectType.BLINDNESS);
            ensureCompass(h);
            aimAtNearestRunner(h);
            applyHunterBuffs(h); // re-apply any catch-up buffs already earned (if help-hunter is on)
        }
        broadcastTitle(ChatColor.DARK_RED + "Hunters Released", ChatColor.GRAY + "The hunt is on.");
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1f);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Quarry] " + ChatColor.RED + "Hunters are loose! Right-click your compass to track.");
        saveState();
    }

    // ============================================================= Dragon Rush

    private boolean startRush(CommandSender sender) {
        if (noPerm(sender)) return true;
        if (activeMode == Mode.MANHUNT) {
            sender.sendMessage(ChatColor.RED + "A Manhunt is set up — /quarry stop first.");
            return true;
        }
        if (state == State.HEADSTART || state == State.RUNNING) {
            sender.sendMessage(ChatColor.RED + "A race is already running.");
            return true;
        }
        if (racers.size() < 2) {
            sender.sendMessage(ChatColor.RED + "Need at least 2 racers. Add them with /quarry rush racer <player> or /quarry rush random.");
            return true;
        }
        List<String> unready = getUnreadyNames();
        if (!unready.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Cannot start race. Not ready: " + String.join(", ", unready));
            return true;
        }
        activeMode = Mode.RUSH;
        startRushSequence();
        return true;
    }

    private void startRushSequence() {
        eliminated.clear();
        livesLeft.clear();
        gameStartMillis = System.currentTimeMillis();
        pauseStartMillis = 0;
        if (rushLives > 0) for (UUID id : racers) livesLeft.put(id, rushLives);

        for (Player p : participants()) {
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
        }
        if (rushScatter) scatterRacers();

        if (rushCountdownSeconds > 0) {
            state = State.HEADSTART; // pre-race freeze
            broadcastTitle(ChatColor.LIGHT_PURPLE + "Dragon Rush", ChatColor.GRAY + "Race to slay the dragon!");
            Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] " + ChatColor.LIGHT_PURPLE + "Dragon Rush starting in "
                    + rushCountdownSeconds + "s — whoever lands the final blow on the Ender Dragon wins!");
            final int[] left = { rushCountdownSeconds };
            headStartTask = new BukkitRunnable() {
                @Override public void run() {
                    if (state != State.HEADSTART || activeMode != Mode.RUSH) { cancel(); return; }
                    if (left[0] <= 0) { cancel(); releaseRacers(); return; }
                    if (left[0] <= 5 || left[0] % 10 == 0) {
                        for (UUID id : racers) {
                            Player r = Bukkit.getPlayer(id);
                            if (r != null) {
                                r.sendTitle(ChatColor.LIGHT_PURPLE + "" + left[0], ChatColor.GRAY + "until the race begins", 0, 25, 5);
                                r.playSound(r.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                            }
                        }
                    }
                    left[0]--;
                }
            }.runTaskTimer(this, 0L, 20L);
            saveState();
        } else {
            releaseRacers();
        }
    }

    private void releaseRacers() {
        state = State.RUNNING;
        broadcastTitle(ChatColor.GREEN + "GO!", ChatColor.GRAY + "First to slay the Ender Dragon wins.");
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.2f);
        Bukkit.broadcastMessage(ChatColor.GREEN + "[Quarry] The Dragon Rush is on — go!");
        startRushTimeLimit();
        saveState();
    }

    // Random-spread racers around the primary world's spawn so nobody shares a
    // starting spot. Uses the highest safe block at each scattered column.
    private void scatterRacers() {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null || rushScatterRadius <= 0) return;
        Location center = world.getSpawnLocation();
        java.util.Random rng = new java.util.Random();
        for (UUID id : racers) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Location spot = findScatterSpot(world, center, rng);
            // No safe spot found after a fair number of tries — leave this racer
            // where they are rather than dropping them into lava or the void.
            if (spot != null) p.teleport(spot);
        }
    }

    /** Pick a random safe surface spot within the scatter radius of `center`.
     *  Rejects lava, fire, and water landings; returns null if none was found. */
    private Location findScatterSpot(World world, Location center, java.util.Random rng) {
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = rng.nextDouble() * rushScatterRadius;
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            int groundY = world.getHighestBlockYAt(x, z);
            Material ground = world.getBlockAt(x, groundY, z).getType();
            if (ground == Material.LAVA || ground == Material.WATER || ground == Material.FIRE
                    || ground == Material.MAGMA_BLOCK || ground == Material.AIR) {
                continue;
            }
            return new Location(world, x + 0.5, groundY + 1.0, z + 0.5);
        }
        return null;
    }

    private void startRushTimeLimit() {
        if (rushTimeLimitTask != null) { rushTimeLimitTask.cancel(); rushTimeLimitTask = null; }
        if (rushTimeLimitMinutes <= 0) return;
        // Schedule off the remaining time (total limit minus elapsed) so a restart
        // mid-race resumes the countdown instead of extending it.
        long limitMs = (long) rushTimeLimitMinutes * 60_000L;
        long elapsedMs = gameStartMillis == 0 ? 0 : Math.max(0, System.currentTimeMillis() - gameStartMillis);
        long ticks = Math.max(1L, (limitMs - elapsedMs) / 50L);
        rushTimeLimitTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (activeMode == Mode.RUSH && state == State.RUNNING) {
                endRush(null); // time's up, no winner
            }
        }, ticks);
    }

    // End the race. winner == null means a draw (time limit or no racers left).
    private void endRush(Player winner) {
        state = State.ENDED;
        String time = elapsed();
        if (headStartTask != null) { headStartTask.cancel(); headStartTask = null; }
        if (rushTimeLimitTask != null) { rushTimeLimitTask.cancel(); rushTimeLimitTask = null; }
        readyPlayers.clear();
        saveState(); // ENDED -> deletes the saved-game file
        if (winner != null) {
            broadcastTitle(ChatColor.GOLD + winner.getName() + " Wins!", ChatColor.GRAY + "Landed the final blow  (" + time + ")");
            Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] " + ChatColor.LIGHT_PURPLE + winner.getName()
                    + ChatColor.GOLD + " slew the Ender Dragon and wins the Dragon Rush! " + ChatColor.YELLOW + "(" + time + ")");
        } else {
            broadcastTitle(ChatColor.YELLOW + "Draw", ChatColor.GRAY + "No one slew the dragon  (" + time + ")");
            Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] The Dragon Rush ended with no winner. " + ChatColor.YELLOW + "(" + time + ")");
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] Run /quarry stop to reset.");
    }

    private void resetRush() {
        if (headStartTask != null) { headStartTask.cancel(); headStartTask = null; }
        if (rushTimeLimitTask != null) { rushTimeLimitTask.cancel(); rushTimeLimitTask = null; }
        for (Player p : participants()) {
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
        }
        state = State.IDLE;
        activeMode = Mode.NONE;
        racers.clear();
        eliminated.clear();
        livesLeft.clear();
        readyPlayers.clear();
        gameStartMillis = 0;
        pauseStartMillis = 0;
        saveState();
    }

    // Stop whichever mode is active (or a not-yet-started setup).
    private boolean stopActive(CommandSender sender) {
        if (noPerm(sender)) return true;
        if (activeMode == Mode.RUSH) {
            resetRush();
            sender.sendMessage(ChatColor.GREEN + "[Quarry] Dragon Rush reset. Racers cleared.");
        } else {
            resetGame();
            sender.sendMessage(ChatColor.GREEN + "[Quarry] Game reset. Roles cleared.");
        }
        return true;
    }

    private boolean pauseGame(CommandSender sender) {
        if (noPerm(sender)) return true;
        if (state != State.RUNNING) {
            sender.sendMessage(ChatColor.RED + "There's no running game to pause.");
            return true;
        }
        state = State.PAUSED;
        pauseStartMillis = System.currentTimeMillis(); // freeze the elapsed clock
        saveState();
        String noun = activeMode == Mode.RUSH ? "Race" : "Game";
        String resumeCmd = activeMode == Mode.RUSH ? "/quarry rush resume" : "/quarry manhunt resume";
        broadcastTitle(ChatColor.YELLOW + noun + " Paused", ChatColor.GRAY + "Everyone is frozen.");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "[Quarry] " + noun + " paused. Players are frozen — "
                + resumeCmd + " to continue.");
        return true;
    }

    private boolean resumeGame(CommandSender sender) {
        if (noPerm(sender)) return true;
        if (state != State.PAUSED) {
            sender.sendMessage(ChatColor.RED + "The game isn't paused.");
            return true;
        }
        state = State.RUNNING;
        // Shift the start forward by however long we were paused, so paused time
        // isn't counted in the elapsed clock.
        if (pauseStartMillis != 0) {
            gameStartMillis += System.currentTimeMillis() - pauseStartMillis;
            pauseStartMillis = 0;
        }
        saveState();
        boolean rush = activeMode == Mode.RUSH;
        broadcastTitle(ChatColor.GREEN + "Resumed", ChatColor.GRAY + (rush ? "The race continues." : "The hunt continues."));
        Bukkit.broadcastMessage(ChatColor.GREEN + "[Quarry] " + (rush ? "Race" : "Game") + " resumed.");
        return true;
    }

    private void resetGame() {
        if (headStartTask != null) { headStartTask.cancel(); headStartTask = null; }
        for (Player p : participants()) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.removePotionEffect(PotionEffectType.SPEED);
            p.removePotionEffect(PotionEffectType.STRENGTH);
            removeTrackingCompasses(p);
            // Pull eliminated runners back out of spectator so they're not stuck.
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
        }
        state = State.IDLE;
        activeMode = Mode.NONE;
        hunters.clear();
        runners.clear();
        eliminated.clear();
        livesLeft.clear();
        lastSeen.clear();
        hunterDeaths.clear();
        readyPlayers.clear();
        gameStartMillis = 0;
        pauseStartMillis = 0;
        saveState(); // IDLE -> deletes the saved-game file
    }

    // Format elapsed game time as H:MM:SS (drops the hours when under an hour).
    // While paused, the clock is frozen at the moment the pause began.
    private String elapsed() {
        if (gameStartMillis == 0) return "0:00";
        long end = pauseStartMillis != 0 ? pauseStartMillis : System.currentTimeMillis();
        long secs = Math.max(0, (end - gameStartMillis) / 1000L);
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private boolean status(CommandSender sender) {
        String modeName = activeMode == Mode.MANHUNT ? ChatColor.RED + "Manhunt"
                : activeMode == Mode.RUSH ? ChatColor.LIGHT_PURPLE + "Dragon Rush"
                : ChatColor.GRAY + "none";
        sender.sendMessage(ChatColor.GOLD + "== Quarry == " + ChatColor.GRAY + "mode: " + modeName
                + ChatColor.GRAY + "  state: " + ChatColor.WHITE + state);
        if (activeMode == Mode.RUSH) return rushStatus(sender);

        sender.sendMessage(ChatColor.RED + "Hunters: " + ChatColor.WHITE + names(hunters));
        List<String> rs = new ArrayList<>();
        for (UUID id : runners) {
            String n = nameOf(id);
            rs.add(eliminated.contains(id) ? (ChatColor.STRIKETHROUGH + n + ChatColor.RESET)
                    : (n + ChatColor.GRAY + "(" + livesLeft.getOrDefault(id, runnerLives) + ")"));
        }
        sender.sendMessage(ChatColor.GREEN + "Runners: " + ChatColor.WHITE + String.join(ChatColor.GRAY + ", ", rs));
        if (state == State.RUNNING || state == State.PAUSED) {
            int alive = 0;
            for (UUID id : runners) if (!eliminated.contains(id)) alive++;
            sender.sendMessage(ChatColor.GRAY + "Runners alive: " + ChatColor.WHITE + alive + "/" + runners.size());
            sender.sendMessage(ChatColor.GRAY + "Elapsed: " + ChatColor.WHITE + elapsed());
        }
        sender.sendMessage(ChatColor.GRAY + "Head start: " + ChatColor.WHITE
                + (headStartSet ? headStartSeconds + "s" : ChatColor.RED + "not set (game can't start)"));
        sender.sendMessage(ChatColor.GRAY + "Help-hunter: " + (helpHunterEnabled
                ? ChatColor.GREEN + "ON " + ChatColor.GRAY + "(Strength at " + STRENGTH_AT + ", Speed at " + SPEED_AT + ", keep-inv)"
                : ChatColor.RED + "OFF"));
        return true;
    }

    private boolean rushStatus(CommandSender sender) {
        List<String> rs = new ArrayList<>();
        for (UUID id : racers) {
            String n = nameOf(id);
            if (eliminated.contains(id)) rs.add(ChatColor.STRIKETHROUGH + n + ChatColor.RESET);
            else if (rushLives > 0) rs.add(n + ChatColor.GRAY + "(" + livesLeft.getOrDefault(id, rushLives) + ")");
            else rs.add(n);
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Racers: " + ChatColor.WHITE + (rs.isEmpty() ? "(none)" : String.join(ChatColor.GRAY + ", ", rs)));
        if (state == State.RUNNING || state == State.PAUSED) {
            int alive = 0;
            for (UUID id : racers) if (!eliminated.contains(id)) alive++;
            sender.sendMessage(ChatColor.GRAY + "Racers alive: " + ChatColor.WHITE + alive + "/" + racers.size());
            sender.sendMessage(ChatColor.GRAY + "Elapsed: " + ChatColor.WHITE + elapsed());
        }
        sender.sendMessage(ChatColor.GRAY + "PvP: " + (rushPvp ? ChatColor.GREEN + "on" : ChatColor.RED + "off")
                + ChatColor.GRAY + "  Lives: " + ChatColor.WHITE + (rushLives == 0 ? "∞" : rushLives)
                + ChatColor.GRAY + "  Keep-inv: " + (rushKeepInventory ? ChatColor.GREEN + "on" : ChatColor.RED + "off"));
        sender.sendMessage(ChatColor.GRAY + "Countdown: " + ChatColor.WHITE + rushCountdownSeconds + "s"
                + ChatColor.GRAY + "  Scatter: " + (rushScatter ? ChatColor.GREEN + (rushScatterRadius + "b") : ChatColor.RED + "off")
                + ChatColor.GRAY + "  Time limit: " + ChatColor.WHITE + (rushTimeLimitMinutes == 0 ? "none" : rushTimeLimitMinutes + "m"));
        return true;
    }

    // ------------------------------------------------------------- compass

    /** Give a hunter a tracking compass if they don't already carry one. */
    private void ensureCompass(Player h) {
        if (!hasTrackingCompass(h)) giveCompass(h);
    }

    /** Arm every online hunter with a compass and aim it at the runners. Used at
     *  assignment time so the compass points before the game even starts. */
    private void pointHuntersAtRunners() {
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h == null) continue;
            ensureCompass(h);
            aimAtNearestRunner(h);
        }
    }

    /** Point one hunter's compass at the nearest online, non-eliminated runner
     *  (falling back to any such runner in another dimension). No-op if there is
     *  no runner to track yet. */
    private void aimAtNearestRunner(Player h) {
        World hw = h.getWorld();
        Player nearestSameWorld = null, anyAlive = null;
        double best = Double.MAX_VALUE;
        for (UUID id : runners) {
            if (eliminated.contains(id)) continue;
            Player r = Bukkit.getPlayer(id);
            if (r == null) continue;
            anyAlive = r;
            if (r.getWorld().equals(hw)) {
                double d = r.getLocation().distanceSquared(h.getLocation());
                if (d < best) { best = d; nearestSameWorld = r; }
            }
        }
        Player target = nearestSameWorld != null ? nearestSameWorld : anyAlive;
        if (target != null) aimCompass(h, target.getLocation());
    }

    /** Aim a hunter's tracking compass at a location. A plain compass only renders
     *  in the Overworld, so we drive it as a lodestone-tracked compass instead —
     *  that points correctly in the Nether and the End too. The target is always
     *  in the hunter's own world (callers guarantee this), which a lodestone
     *  compass requires to render. */
    private void aimCompass(Player h, Location loc) {
        h.setCompassTarget(loc); // keeps the Overworld spawn-HUD pointer in sync
        for (ItemStack it : h.getInventory().getContents()) {
            if (!isTrackingCompass(it)) continue;
            ItemMeta m = it.getItemMeta();
            if (m instanceof CompassMeta) {
                CompassMeta cm = (CompassMeta) m;
                cm.setLodestone(loc);
                cm.setLodestoneTracked(false); // don't require a real lodestone block
                it.setItemMeta(cm);
            }
        }
    }

    private void giveCompass(Player h) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Tracking Compass");
            meta.getPersistentDataContainer().set(compassKey, PersistentDataType.INTEGER, 1);
            compass.setItemMeta(meta);
        }
        h.getInventory().addItem(compass);
    }

    private boolean isTrackingCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(compassKey, PersistentDataType.INTEGER);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (state != State.RUNNING) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player h = e.getPlayer();
        if (!hunters.contains(h.getUniqueId())) return;
        if (!isTrackingCompass(e.getItem())) return;
        // Don't let the tracking right-click also open a chest / interact with the block behind it.
        e.setCancelled(true);

        long now = System.currentTimeMillis();
        Long until = compassCooldownUntil.get(h.getUniqueId());
        if (until != null && now < until) {
            h.sendMessage(ChatColor.GRAY + "Compass recharging… " + ((until - now) / 1000 + 1) + "s");
            return;
        }
        // Only start the cooldown if there was actually someone to track.
        if (lockCompass(h)) {
            compassCooldownUntil.put(h.getUniqueId(), now + compassCooldownMs);
        }
    }

    /** Point the hunter's compass at a runner. Returns true if there was a runner
     *  to track (so a wasted click on zero runners doesn't burn the cooldown). */
    private boolean lockCompass(Player h) {
        World hw = h.getWorld();
        Player nearestSameWorld = null;
        double best = Double.MAX_VALUE;
        Player anyAlive = null;

        for (UUID id : runners) {
            if (eliminated.contains(id)) continue;
            Player r = Bukkit.getPlayer(id);
            if (r == null) continue;
            anyAlive = r;
            if (r.getWorld().equals(hw)) {
                double d = r.getLocation().distanceSquared(h.getLocation());
                if (d < best) { best = d; nearestSameWorld = r; }
            }
        }

        if (nearestSameWorld != null) {
            aimCompass(h, nearestSameWorld.getLocation());
            h.sendMessage(ChatColor.RED + "Tracking " + nearestSameWorld.getName() + ".");
            h.playSound(h.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.4f);
            return true;
        }

        if (anyAlive == null) {
            h.sendMessage(ChatColor.GRAY + "No runners left to track.");
            return false;
        }

        // Cross-dimension: point at the runner's last known spot in THIS world.
        Location trail = lastSeen.getOrDefault(anyAlive.getUniqueId(), java.util.Collections.emptyMap()).get(hw.getName());
        if (trail != null) {
            aimCompass(h, trail);
            h.sendMessage(ChatColor.RED + "Tracking " + anyAlive.getName() + ChatColor.GRAY
                    + " — they're in another dimension, pointing to their trail.");
        } else {
            h.sendMessage(ChatColor.YELLOW + anyAlive.getName() + ChatColor.GRAY
                    + " is in another dimension — no trail here yet.");
        }
        h.playSound(h.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.8f);
        return true;
    }

    // ------------------------------------------------------------- events

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
        Player damager = (Player) e.getDamager();
        Player victim = (Player) e.getEntity();

        UUID damagerId = damager.getUniqueId();
        UUID victimId = victim.getUniqueId();

        // Dragon Rush: block racer-vs-racer damage when PvP is off, and always
        // during the pre-race countdown or a pause.
        if (activeMode == Mode.RUSH) {
            if (racers.contains(damagerId) && racers.contains(victimId)
                    && (!rushPvp || state == State.HEADSTART || state == State.PAUSED)) {
                e.setCancelled(true);
            }
            return;
        }

        // Manhunt: a runner punching a hunter can start the game.
        if (state != State.IDLE && state != State.ENDED) return;
        if (runners.contains(damagerId) && hunters.contains(victimId)) {
            if (!headStartSet) {
                damager.sendMessage(ChatColor.RED + "An op must set the head start before the game can start: /quarry headstart <seconds>");
                e.setCancelled(true);
                return;
            }
            List<String> unready = getUnreadyNames();
            if (!unready.isEmpty()) {
                damager.sendMessage(ChatColor.RED + "Cannot start game. The following players are not ready: "
                        + String.join(", ", unready));
                e.setCancelled(true);
                return;
            }
            // Capture the punch before we cancel: the hunter is NOT immune to the
            // hit that starts the hunt. We cancel the vanilla event (its handling is
            // order-dependent and the game start resets health / grants head-start
            // invulnerability), then re-apply the damage by hand once the sequence
            // has run, via setHealth so nothing can cancel it back out.
            double hitDamage = e.getFinalDamage();
            e.setCancelled(true);
            startTheGameSequence();
            landStartingHit(victim, hitDamage);
        }
    }

    // Apply the runner's opening punch to the hunter after the game has started.
    // startTheGameSequence() has just healed everyone to full, so this lands the
    // hit off full health. setHealth bypasses the head-start invulnerability that
    // would otherwise negate it.
    private void landStartingHit(Player hunter, double damage) {
        if (damage <= 0 || hunter == null || !hunter.isOnline() || hunter.isDead()) return;
        double newHealth = Math.max(0.5, hunter.getHealth() - damage); // opening hit is never lethal
        hunter.setHealth(newHealth);
        hunter.getWorld().playSound(hunter.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // Track each runner's latest position per world for the trail compass.
        if (activeMode == Mode.MANHUNT && runners.contains(id) && !eliminated.contains(id) && e.getTo() != null) {
            lastSeen.computeIfAbsent(id, k -> new HashMap<>()).put(p.getWorld().getName(), e.getTo().clone());
        }

        // Freeze players in place (but let them look around):
        //  - Manhunt HEADSTART: hunters are frozen while runners flee.
        //  - Rush HEADSTART: all racers are frozen during the countdown.
        //  - PAUSED: everyone still in the game is frozen.
        // Hold ALL position axes -- blocking only horizontal/up would still let a
        // frozen player fall off a ledge.
        boolean freezeHeadstart = state == State.HEADSTART
                && ((activeMode == Mode.MANHUNT && hunters.contains(id))
                        || (activeMode == Mode.RUSH && racers.contains(id)));
        boolean freezePaused = state == State.PAUSED && !eliminated.contains(id)
                && ((activeMode == Mode.MANHUNT && (hunters.contains(id) || runners.contains(id)))
                        || (activeMode == Mode.RUSH && racers.contains(id)));
        boolean freeze = freezeHeadstart || freezePaused;
        if (freeze && e.getTo() != null) {
            Location from = e.getFrom(), to = e.getTo();
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location hold = from.clone();
                hold.setYaw(to.getYaw());
                hold.setPitch(to.getPitch());
                e.setTo(hold);
            }
        }
    }

    // Frozen participants are invulnerable during the head start / countdown —
    // they can't move (manhunt hunters can't see either), so no one can farm a
    // free kill on a helpless target.
    @EventHandler
    public void onHeadStartDamage(EntityDamageEvent e) {
        if (state != State.HEADSTART) return;
        if (e.getEntity() instanceof Player && isFrozenParticipant(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    // Frozen participants can't break or place blocks during the head start /
    // countdown — no pre-mining, bridging, or trapping before the go signal.
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (state == State.HEADSTART && isFrozenParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (state == State.HEADSTART && isFrozenParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // Who is frozen during the HEADSTART phase: manhunt hunters, or all racers.
    private boolean isFrozenParticipant(UUID id) {
        return (activeMode == Mode.MANHUNT && hunters.contains(id))
                || (activeMode == Mode.RUSH && racers.contains(id));
    }

    // The tracking compass can never leave an active hunter's hands: they can't
    // drop it (so a runner can't loot it and force the hunter to die for a
    // replacement). Outside a live game the block is lifted, so a leftover compass
    // — e.g. one held by a player who was offline during /quarry stop — can be
    // thrown away instead of being stuck in their inventory forever.
    @EventHandler
    public void onDropItem(PlayerDropItemEvent e) {
        if (!isTrackingCompass(e.getItemDrop().getItemStack())) return;
        boolean liveGame = state == State.HEADSTART || state == State.RUNNING || state == State.PAUSED;
        if (liveGame && hunters.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You can't drop the tracking compass.");
        }
    }

    // ...and while a game is live, only a hunter may pick one up off the ground.
    @EventHandler
    public void onPickupItem(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (!isTrackingCompass(e.getItem().getItemStack())) return;
        boolean liveGame = state == State.HEADSTART || state == State.RUNNING || state == State.PAUSED;
        if (liveGame && !hunters.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (state != State.RUNNING) return;
        if (!(e.getEntity() instanceof EnderDragon)) return;
        if (activeMode == Mode.MANHUNT) {
            endGame(ChatColor.GREEN + "Runners Win!", ChatColor.GRAY + "The Ender Dragon has fallen.");
        } else if (activeMode == Mode.RUSH) {
            // Whoever landed the final blow wins; if it fell to something other than
            // a live racer, the race ends in a draw.
            Player killer = e.getEntity().getKiller();
            if (killer != null && racers.contains(killer.getUniqueId()) && !eliminated.contains(killer.getUniqueId())) {
                endRush(killer);
            } else {
                endRush(null);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (state != State.RUNNING) return;
        Player p = e.getEntity();
        UUID id = p.getUniqueId();

        if (activeMode == Mode.RUSH) { handleRushDeath(e, p, id); return; }

        if (hunters.contains(id)) {
            if (helpHunterEnabled) {
                // Help-hunter: the hunter keeps their whole inventory + XP on death.
                e.setKeepInventory(true);
                e.getDrops().clear();
                e.setKeepLevel(true);
                e.setDroppedExp(0);
            } else {
                // The tracking compass must never drop — a runner mustn't be able to
                // loot it. The hunter is re-issued one on respawn (onRespawn).
                e.getDrops().removeIf(this::isTrackingCompass);
            }
            onHunterKilled(p); // track runner-kills for the catch-up thresholds
            return; // hunters just respawn; buffs are re-applied on respawn
        }
        if (!runners.contains(id) || eliminated.contains(id)) return;

        int left = livesLeft.getOrDefault(id, runnerLives) - 1;
        livesLeft.put(id, left);
        if (left > 0) {
            Bukkit.broadcastMessage(ChatColor.YELLOW + "[Quarry] " + p.getName() + " died — " + left + " life/lives left.");
        } else {
            eliminated.add(id);
            Bukkit.broadcastMessage(ChatColor.RED + "[Quarry] " + p.getName() + " has been eliminated!");
            // Spectator mode is applied on respawn (onRespawn) — changing gamemode on
            // the death screen is unreliable.

            // All runners down → hunters win.
            boolean anyAlive = false;
            for (UUID r : runners) if (!eliminated.contains(r)) { anyAlive = true; break; }
            if (!anyAlive) endGame(ChatColor.DARK_RED + "Hunters Win!", ChatColor.GRAY + "Every runner is down.");
        }
        saveState(); // persist the new lives / elimination so a shutdown can't lose it
    }

    // A racer died in Dragon Rush. With infinite lives they just respawn (death
    // only costs time). With finite lives they lose one and are eliminated at zero;
    // if every racer is out, the race ends in a draw. Spectator is applied on
    // respawn (onRespawn), like manhunt.
    private void handleRushDeath(PlayerDeathEvent e, Player p, UUID id) {
        if (!racers.contains(id) || eliminated.contains(id)) return;
        if (rushKeepInventory) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        }
        if (rushLives <= 0) return; // infinite lives — nothing to track

        int left = livesLeft.getOrDefault(id, rushLives) - 1;
        livesLeft.put(id, left);
        if (left > 0) {
            Bukkit.broadcastMessage(ChatColor.YELLOW + "[Quarry] " + p.getName() + " died — " + left + " life/lives left.");
        } else {
            eliminated.add(id);
            Bukkit.broadcastMessage(ChatColor.RED + "[Quarry] " + p.getName() + " is out of the race!");
            boolean anyAlive = false;
            for (UUID r : racers) if (!eliminated.contains(r)) { anyAlive = true; break; }
            if (!anyAlive) endRush(null); // everyone's out, nobody slew the dragon
        }
        saveState();
    }

    // Apply spectator to eliminated runners/racers here, on respawn, where the
    // gamemode change sticks (changing it on the death screen is unreliable). This
    // covers the final participant too: losing your last life still drops you into
    // spectator when you respawn, even though the game may have already ended.
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if ((state == State.RUNNING || state == State.ENDED) && eliminated.contains(id)) {
            p.setGameMode(GameMode.SPECTATOR);
            return;
        }
        // Re-issue the hunter's tracker (it never drops, so a fresh one is owed on
        // respawn) and re-apply earned catch-up buffs — death clears potion effects.
        // Both are done a tick later, as respawn-time changes are otherwise unreliable.
        if (state == State.RUNNING && hunters.contains(id)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!hasTrackingCompass(p)) giveCompass(p);
                aimAtNearestRunner(p);
                if (helpHunterEnabled) applyHunterBuffs(p);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        compassCooldownUntil.remove(id);
        // A player who disconnects is no longer "ready" -- otherwise a game could
        // start without them.
        readyPlayers.remove(id);
        // Warn when a live participant drops mid-game.
        if (state == State.RUNNING || state == State.PAUSED) {
            if (activeMode == Mode.MANHUNT && runners.contains(id) && !eliminated.contains(id)) {
                Bukkit.broadcastMessage(ChatColor.YELLOW + "[Quarry] " + e.getPlayer().getName() + " (runner) disconnected.");
            } else if (activeMode == Mode.RUSH && racers.contains(id) && !eliminated.contains(id)) {
                Bukkit.broadcastMessage(ChatColor.YELLOW + "[Quarry] " + e.getPlayer().getName() + " (racer) disconnected.");
            }
        }
    }

    // Restore a player's role state when they (re)join during an active game —
    // e.g. logging in the day after a shutdown that we resumed from disk.
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (state != State.RUNNING && state != State.PAUSED) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!hunters.contains(id) && !runners.contains(id) && !racers.contains(id)) return;
        applyActiveRole(p);
        String role = hunters.contains(id) ? "a HUNTER"
                : eliminated.contains(id) ? "eliminated (spectating)"
                : racers.contains(id) ? "a RACER" : "a RUNNER";
        p.sendMessage(ChatColor.GOLD + "[Quarry] A game is still in progress — you are " + role + ".");
    }

    private void applyActiveRole(Player p) {
        UUID id = p.getUniqueId();
        if (eliminated.contains(id)) { p.setGameMode(GameMode.SPECTATOR); return; }
        if (hunters.contains(id)) {
            if (!hasTrackingCompass(p)) giveCompass(p);
            if (helpHunterEnabled) applyHunterBuffs(p);
        }
    }

    private boolean hasTrackingCompass(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isTrackingCompass(it)) return true;
        return false;
    }

    /** Strip only OUR tracking compasses — a plain remove(Material.COMPASS) would
     *  also destroy any ordinary compass the player crafted. */
    private void removeTrackingCompasses(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isTrackingCompass(contents[i])) p.getInventory().setItem(i, null);
        }
    }

    // ------------------------------------------------------------- head start

    // /quarry headstart <seconds> — op-only. Sets the runners' head start AND
    // arms the gate that lets a game start (see startGame / onDamage).
    private boolean setHeadStart(CommandSender sender, String[] args) {
        if (noPerm(sender)) return true;
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /quarry headstart <seconds>");
            sender.sendMessage(ChatColor.GRAY + "Current: " + (headStartSet
                    ? headStartSeconds + "s"
                    : ChatColor.RED + "not set" + ChatColor.GRAY + " — a game can't start until you set this."));
            return true;
        }
        int secs;
        try {
            secs = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Head start must be a whole number of seconds.");
            return true;
        }
        if (secs < 0) {
            sender.sendMessage(ChatColor.RED + "Head start can't be negative.");
            return true;
        }
        headStartSeconds = secs;
        headStartSet = true;
        getConfig().set("head-start-seconds", secs);
        getConfig().set("head-start-set", true);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "[Quarry] Head start set to " + secs + "s. Games can now be started.");
        return true;
    }

    // ------------------------------------------------------------- help hunter

    // /quarry helphunter <on|off> — op-only toggle. When on: catch-up buffs
    // (Strength I at STRENGTH_AT deaths, Speed I at SPEED_AT) plus keep-inventory.
    private boolean helpHunter(CommandSender sender, String[] args) {
        if (noPerm(sender)) return true;
        if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            sender.sendMessage(ChatColor.RED + "Usage: /quarry helphunter <on|off>");
            sender.sendMessage(ChatColor.GRAY + "Currently " + (helpHunterEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF")
                    + ChatColor.GRAY + ". When on: hunters gain Strength I after " + STRENGTH_AT
                    + " deaths and Speed I after " + SPEED_AT + ", and keep their inventory on death.");
            return true;
        }
        helpHunterEnabled = args[1].equalsIgnoreCase("on");
        getConfig().set("help-hunter", helpHunterEnabled);
        saveConfig();
        if (helpHunterEnabled) {
            sender.sendMessage(ChatColor.GREEN + "[Quarry] Hunter assistance ENABLED — Strength I at " + STRENGTH_AT
                    + " deaths, Speed I at " + SPEED_AT + ", and hunters keep their inventory on death.");
            // Grant any buffs already earned this game right away.
            for (UUID id : hunters) { Player h = Bukkit.getPlayer(id); if (h != null) applyHunterBuffs(h); }
        } else {
            sender.sendMessage(ChatColor.YELLOW + "[Quarry] Hunter assistance DISABLED — existing buffs removed.");
            clearHunterBuffs();
        }
        return true;
    }

    // A hunter died. Count it when a runner did the killing, so the buffs can kick
    // in at each threshold (applied on their next respawn — death wipes effects).
    private void onHunterKilled(Player h) {
        Player killer = h.getKiller();
        if (killer == null || !runners.contains(killer.getUniqueId())) return;
        UUID id = h.getUniqueId();
        int deaths = hunterDeaths.getOrDefault(id, 0) + 1;
        hunterDeaths.put(id, deaths);
        if (helpHunterEnabled) {
            if (deaths == STRENGTH_AT) {
                Bukkit.broadcastMessage(ChatColor.RED + "[Quarry] " + h.getName() + " has fallen " + STRENGTH_AT
                        + " times and now hunts with " + ChatColor.BOLD + "Strength I" + ChatColor.RED + "!");
            } else if (deaths == SPEED_AT) {
                Bukkit.broadcastMessage(ChatColor.AQUA + "[Quarry] " + h.getName() + " has fallen " + SPEED_AT
                        + " times and now hunts with " + ChatColor.BOLD + "Speed I" + ChatColor.AQUA + "!");
            }
        }
        saveState(); // persist the count so a restart mid-game keeps it
    }

    // Apply whatever catch-up buffs this hunter has earned: Strength I at STRENGTH_AT
    // deaths, Speed I at SPEED_AT. Idempotent — safe to re-run on respawn, join, or
    // when the feature is toggled on.
    private void applyHunterBuffs(Player h) {
        if (!helpHunterEnabled || !hunters.contains(h.getUniqueId())) return;
        int deaths = hunterDeaths.getOrDefault(h.getUniqueId(), 0);
        if (deaths >= STRENGTH_AT) {
            h.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, INFINITE, 0, false, false, true));
        }
        if (deaths >= SPEED_AT) {
            h.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, INFINITE, 0, false, false, true));
        }
    }

    private void clearHunterBuffs() {
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h == null) continue;
            h.removePotionEffect(PotionEffectType.SPEED);
            h.removePotionEffect(PotionEffectType.STRENGTH);
        }
    }

    // ------------------------------------------------------------- persistence
    // Save the live game to disk so it survives a full server restart. Written on
    // every meaningful change and on disable; deleted once the game is over.

    private File stateFile() { return new File(getDataFolder(), "game-state.yml"); }

    private void saveState() {
        File f = stateFile();
        if (state == State.IDLE || state == State.ENDED) { if (f.exists()) f.delete(); return; }
        YamlConfiguration y = new YamlConfiguration();
        y.set("mode", activeMode.name());
        y.set("state", state.name());
        y.set("startMillis", gameStartMillis);
        y.set("pauseMillis", pauseStartMillis);
        y.set("hunters", uuidList(hunters));
        y.set("runners", uuidList(runners));
        y.set("racers", uuidList(racers));
        y.set("eliminated", uuidList(eliminated));
        for (Map.Entry<UUID, Integer> en : livesLeft.entrySet()) y.set("lives." + en.getKey(), en.getValue());
        for (Map.Entry<UUID, Integer> en : hunterDeaths.entrySet()) y.set("hunterDeaths." + en.getKey(), en.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(f);
        } catch (IOException ex) {
            getLogger().warning("Quarry saveState failed: " + ex);
        }
    }

    private void loadState() {
        File f = stateFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        try { activeMode = Mode.valueOf(y.getString("mode", "MANHUNT")); }
        catch (IllegalArgumentException ex) { activeMode = Mode.MANHUNT; }
        try { state = State.valueOf(y.getString("state", "IDLE")); }
        catch (IllegalArgumentException ex) { state = State.IDLE; }
        gameStartMillis = y.getLong("startMillis", 0);
        pauseStartMillis = y.getLong("pauseMillis", 0);
        readUuids(y.getStringList("hunters"), hunters);
        readUuids(y.getStringList("runners"), runners);
        readUuids(y.getStringList("racers"), racers);
        readUuids(y.getStringList("eliminated"), eliminated);
        livesLeft.clear();
        ConfigurationSection ls = y.getConfigurationSection("lives");
        if (ls != null) for (String k : ls.getKeys(false)) {
            try { livesLeft.put(UUID.fromString(k), ls.getInt(k)); } catch (IllegalArgumentException ignored) {}
        }
        hunterDeaths.clear();
        ConfigurationSection hd = y.getConfigurationSection("hunterDeaths");
        if (hd != null) for (String k : hd.getKeys(false)) {
            try { hunterDeaths.put(UUID.fromString(k), hd.getInt(k)); } catch (IllegalArgumentException ignored) {}
        }
        // A head start / countdown can't meaningfully resume mid-countdown — just
        // treat the restored game as already running.
        if (state == State.HEADSTART) state = State.RUNNING;
        if (state == State.RUNNING || state == State.PAUSED) {
            if (activeMode == Mode.RUSH) {
                getLogger().info("Quarry restored a " + state + " Dragon Rush — " + racers.size() + " racer(s).");
                // The time-limit clock doesn't survive a restart; re-arm it if running.
                if (state == State.RUNNING) startRushTimeLimit();
            } else {
                getLogger().info("Quarry restored a " + state + " Manhunt — "
                        + runners.size() + " runner(s), " + hunters.size() + " hunter(s).");
            }
        } else {
            state = State.IDLE; // nothing worth resuming
            activeMode = Mode.NONE;
        }
    }

    private List<String> uuidList(Set<UUID> ids) {
        List<String> l = new ArrayList<>();
        for (UUID id : ids) l.add(id.toString());
        return l;
    }

    private void readUuids(List<String> src, Set<UUID> dest) {
        dest.clear();
        for (String s : src) {
            try { dest.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void endGame(String title, String subtitle) {
        state = State.ENDED;
        String time = elapsed(); // capture before anything resets the clock
        if (headStartTask != null) { headStartTask.cancel(); headStartTask = null; }
        clearHunterBuffs(); // catch-up buffs only last "until the game ends"
        readyPlayers.clear(); // each new round requires readying up again
        saveState(); // ENDED -> deletes the saved-game file (nothing to resume)
        broadcastTitle(title, subtitle + ChatColor.DARK_GRAY + "  (" + time + ")");
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[Quarry] " + ChatColor.stripColor(title)
                + ChatColor.YELLOW + " Finished in " + time + ". " + ChatColor.GOLD + "Run /quarry stop to reset.");
    }

    // ------------------------------------------------------------- helpers

    private boolean noPerm(CommandSender s) {
        if (s.hasPermission("quarry.admin")) return false;
        s.sendMessage(ChatColor.RED + "No permission.");
        return true;
    }

    private List<Player> participants() {
        List<Player> out = new ArrayList<>();
        for (UUID id : hunters) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p); }
        for (UUID id : runners) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p); }
        for (UUID id : racers) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p); }
        return out;
    }

    private void broadcastTitle(String title, String subtitle) {
        for (Player p : Bukkit.getOnlinePlayers()) p.sendTitle(title, subtitle, 10, 60, 20);
    }


    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : id.toString().substring(0, 8);
    }

    private String names(Set<UUID> ids) {
        List<String> out = new ArrayList<>();
        for (UUID id : ids) out.add(nameOf(id));
        return out.isEmpty() ? "(none)" : String.join(", ", out);
    }

    private static final String[] MANHUNT_SUBS = {"hunter", "runner", "random", "start", "pause", "resume", "stop", "status", "iamready", "rules", "helphunter", "headstart", "gui", "menu"};
    private static final String[] RUSH_SUBS = {"racer", "random", "start", "pause", "resume", "stop", "status", "iamready", "gui", "menu"};

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String lastPre = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String s : new String[]{"manhunt", "rush", "status", "stop", "reload", "help"}) {
                if (s.startsWith(lastPre)) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("manhunt")) {
            for (String s : MANHUNT_SUBS) if (s.startsWith(lastPre)) out.add(s);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("rush") || args[0].equalsIgnoreCase("dragonrush"))) {
            for (String s : RUSH_SUBS) if (s.startsWith(lastPre)) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("manhunt")
                && (args[1].equalsIgnoreCase("hunter") || args[1].equalsIgnoreCase("runner"))) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(lastPre)) out.add(p.getName());
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("rush") || args[0].equalsIgnoreCase("dragonrush"))
                && args[1].equalsIgnoreCase("racer")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(lastPre)) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("manhunt") && args[1].equalsIgnoreCase("helphunter")) {
            for (String s : new String[]{"on", "off"}) if (s.startsWith(lastPre)) out.add(s);
        }
        return out;
    }
}
