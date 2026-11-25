package com.loxstorm.smp;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoxStormSMP extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String PREFIX = "§6§l[LoxStorm] §r";
    private static final String PERM = "lssmp.admin";
    private static final long MAX_SECONDS = 7L * 24 * 3600;

    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean pvpEnabled = true;
    private boolean running = false;
    private boolean frozen = false;
    private long pvpEndsAtMillis = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("lssmp") != null) {
            getCommand("lssmp").setExecutor(this);
            getCommand("lssmp").setTabCompleter(this);
        }
        getLogger().info("LoxStorm SMP plugin enabled.");
    }

    @Override
    public void onDisable() {
        frozen = false;
        cancelTasks();
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(PREFIX + "§cYou don't have permission.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "st":
            case "start":
                if (running) {
                    sender.sendMessage(PREFIX + "§cA start sequence is already running. Use §e/lssmp stop §cfirst.");
                    return true;
                }
                startSequence();
                sender.sendMessage(PREFIX + "§aStart sequence launched.");
                return true;

            case "cd":
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + "§ePvP-disabled time is currently §6"
                            + formatTime(getConfig().getLong("pvp-disabled-seconds", 600))
                            + "§e. Usage: §f/lssmp cd <time> §7(e.g. 30s, 5m, 1h, 1h30m)");
                    return true;
                }
                long secs = parseTime(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                if (secs < 1) {
                    sender.sendMessage(PREFIX + "§cInvalid time. Examples: §f30s§c, §f5m§c, §f1h§c, §f1h30m§c, §f10 min");
                    return true;
                }
                getConfig().set("pvp-disabled-seconds", secs);
                saveConfig();
                sender.sendMessage(PREFIX + "§aPvP-disabled time set to §6" + formatTime(secs)
                        + "§a. It applies to the next §e/lssmp st§a.");
                return true;

            case "stop":
                if (!running) {
                    sender.sendMessage(PREFIX + "§cNothing is running.");
                    return true;
                }
                cancelTasks();
                running = false;
                frozen = false;
                pvpEnabled = true;
                sender.sendMessage(PREFIX + "§eSequence stopped. PvP is now enabled (no rewards given).");
                return true;

            case "status":
                if (!running && pvpEnabled) {
                    sender.sendMessage(PREFIX + "§7Idle. PvP is §aenabled§7.");
                } else if (pvpEndsAtMillis > 0 && !pvpEnabled) {
                    long left = Math.max(0, (pvpEndsAtMillis - System.currentTimeMillis()) / 1000);
                    sender.sendMessage(PREFIX + "§7PvP is §cdisabled§7. Time left: §6" + formatTime(left));
                } else {
                    sender.sendMessage(PREFIX + "§7Start sequence in progress...");
                }
                return true;

            case "reload":
                if (running) {
                    sender.sendMessage(PREFIX + "§cCan't reload while a sequence is running.");
                    return true;
                }
                reloadConfig();
                sender.sendMessage(PREFIX + "§aConfig reloaded.");
                return true;

            default:
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage("§6§m        §r §6§lLoxStorm SMP §6§m        ");
        s.sendMessage("§e/lssmp st §7- Start the SMP (countdown, PvP timer, rewards)");
        s.sendMessage("§e/lssmp cd <time> §7- Set PvP-disabled time (30s, 5m, 1h, 1h30m)");
        s.sendMessage("§e/lssmp stop §7- Cancel the running sequence");
        s.sendMessage("§e/lssmp status §7- Show PvP timer");
        s.sendMessage("§e/lssmp reload §7- Reload config");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERM)) return out;
        if (args.length == 1) {
            for (String s : Arrays.asList("st", "cd", "stop", "status", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cd")) {
            out.addAll(Arrays.asList("30s", "5m", "10m", "30m", "1h"));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Start sequence
    // ------------------------------------------------------------------

    private void startSequence() {
        running = true;
        pvpEnabled = false;
        pvpEndsAtMillis = 0;

        int countdown = Math.max(3, getConfig().getInt("countdown-seconds", 10));
        long pvpSeconds = Math.max(1, getConfig().getLong("pvp-disabled-seconds", 600));

        // Freeze everyone during the starting popup + countdown
        frozen = getConfig().getBoolean("freeze-during-countdown", true);

        // 1) "STARTING" popup
        title("§6§lLOXSTORM SMP", "§eSTARTING...", 10, 50, 10);
        sound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);

        // 2) countdown 10 -> 1 (begins after 3 seconds)
        long delay = 60;
        for (int i = countdown; i >= 1; i--) {
            final int n = i;
            later(delay, () -> {
                String color = n <= 3 ? "§c§l" : "§e§l";
                title(color + n, "§fLOXSTORM SMP STARTING IN", 0, 25, 5);
                sound(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, n <= 3 ? 2f : 1f);
            });
            delay += 20;
        }

        // 3) STARTED (unfreeze everyone)
        later(delay, () -> {
            frozen = false;
            title("§a§lLOXSTORM SMP", "§fSTARTED", 10, 50, 15);
            sound(Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
        });
        delay += 80;

        // 4) PvP disabled popup + live timer
        final long pvpTicks = pvpSeconds * 20L;
        later(delay, () -> {
            pvpEndsAtMillis = System.currentTimeMillis() + pvpSeconds * 1000L;
            title("§c§lPVP DISABLED", "§efor " + formatTime(pvpSeconds), 10, 60, 10);
            sound(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f);
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::tickActionBar, 20L, 20L));
        });

        // 5) PvP enabled + rewards
        later(delay + pvpTicks, this::enablePvp);
    }

    private void tickActionBar() {
        long left = Math.max(0, (pvpEndsAtMillis - System.currentTimeMillis()) / 1000);
        TextComponent bar = new TextComponent("§c§lPVP DISABLED §7| §e" + formatTime(left) + " left");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, bar);
        }
    }

    private void enablePvp() {
        cancelTasks();
        pvpEnabled = true;
        running = false;
        frozen = false;
        pvpEndsAtMillis = 0;

        title("§a§lPVP ENABLED", "§7Fight fair. Good luck!", 10, 60, 15);
        sound(Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.2f);

        if (getConfig().getBoolean("reward.enabled", true)) {
            int amount = Math.max(1, getConfig().getInt("reward.golden-apples", 10));
            for (Player p : Bukkit.getOnlinePlayers()) {
                Map<Integer, ItemStack> leftover = p.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, amount));
                for (ItemStack item : leftover.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), item);
                }
                p.sendMessage(PREFIX + "§6You received §e" + amount + " golden apples§6!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
        }
    }

    // ------------------------------------------------------------------
    // PvP protection
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (pvpEnabled || !(e.getEntity() instanceof Player)) return;

        Player attacker = null;
        Entity damager = e.getDamager();
        if (damager instanceof Player) {
            attacker = (Player) damager;
        } else if (damager instanceof Projectile) {
            ProjectileSource src = ((Projectile) damager).getShooter();
            if (src instanceof Player) attacker = (Player) src;
        }
        if (attacker == null || attacker.equals(e.getEntity())) return;

        e.setCancelled(true);
        attacker.sendMessage(PREFIX + "§cPvP is disabled right now.");
    }

    // ------------------------------------------------------------------
    // Freeze during countdown (no walking, no jumping; looking around is allowed)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!frozen) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        boolean movedSideways = from.getX() != to.getX() || from.getZ() != to.getZ();
        boolean wentUp = to.getY() > from.getY();
        if (movedSideways || wentUp) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!pvpEnabled && pvpEndsAtMillis > 0) {
            long left = Math.max(0, (pvpEndsAtMillis - System.currentTimeMillis()) / 1000);
            e.getPlayer().sendMessage(PREFIX + "§cPvP is disabled for another §6" + formatTime(left) + "§c.");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void later(long ticks, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskLater(this, r, ticks));
    }

    private void cancelTasks() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
    }

    private void title(String title, String subtitle, int in, int stay, int out) {
        for (Player p : Bukkit.getOnlinePlayers()) p.sendTitle(title, subtitle, in, stay, out);
    }

    private void sound(Sound s, float vol, float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), s, vol, pitch);
    }

    /** Parses "30", "30s", "5m", "1h", "1h30m", "10 min". Returns -1 if invalid. */
    static long parseTime(String input) {
        String text = input.toLowerCase().trim();
        Pattern p = Pattern.compile("(\\d+)\\s*([a-z]*)");
        if (!p.matcher(text).find()) return -1;
        if (!p.matcher(text).replaceAll("").trim().isEmpty()) return -1;

        Matcher m = p.matcher(text);
        long total = 0;
        while (m.find()) {
            long value;
            try {
                value = Long.parseLong(m.group(1));
            } catch (NumberFormatException ex) {
                return -1;
            }
            switch (m.group(2)) {
                case "": case "s": case "sec": case "secs": case "second": case "seconds":
                    total += value; break;
                case "m": case "min": case "mins": case "minute": case "minutes":
                    total += value * 60; break;
                case "h": case "hr": case "hrs": case "hour": case "hours":
                    total += value * 3600; break;
                default:
                    return -1;
            }
        }
        return Math.min(total, MAX_SECONDS);
    }

    static String formatTime(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
