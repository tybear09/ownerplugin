package com.example.ownerguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * OwnerGuard - protected players (the owner) can NOT be deopped or banned by normal
 * commands from anyone, console included. The only way is the console-only
 * command:  /happy <player> [deop|ban|both|restore] [reason]
 *
 * /happy only runs from the real server console. Players (even ops), command blocks,
 * RCON and /execute tricks are all rejected, and the command is hidden from players.
 */
public class OwnerGuard extends JavaPlugin implements Listener {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final String UNKNOWN = ChatColor.RED
            + "Unknown or incomplete command, see below for error";

    private final Set<String> protectedNames = new HashSet<>();
    private final Set<UUID> protectedUuids = new HashSet<>();
    private final Set<String> blockedCommands = new HashSet<>();
    private final Set<String> ipCommands = new HashSet<>();

    /** Names (lowercase) and UUID strings that /happy has released from self-heal. */
    private final Set<String> suspended = new HashSet<>();
    private File suspendedFile;

    /** True only while OwnerGuard itself runs a command from /happy. */
    private boolean internalDispatch = false;

    private boolean blockSelectors;
    private boolean selfHeal;
    private boolean keepOp;
    private boolean alertOnline;
    private String denyMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        suspendedFile = new File(getDataFolder(), "suspended.yml");
        loadSettings();
        loadSuspended();
        getServer().getPluginManager().registerEvents(this, this);

        long ticks = Math.max(1, getConfig().getInt("self-heal-interval-seconds", 5)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::selfHealTick, 40L, ticks);

        getLogger().info("OwnerGuard enabled. Protecting "
                + (protectedNames.size() + protectedUuids.size()) + " entries.");
    }

    private void loadSettings() {
        reloadConfig();
        protectedNames.clear();
        protectedUuids.clear();
        blockedCommands.clear();
        ipCommands.clear();

        for (String entry : getConfig().getStringList("protected-players")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            try {
                protectedUuids.add(UUID.fromString(e));
            } catch (IllegalArgumentException ex) {
                protectedNames.add(e.toLowerCase(Locale.ROOT));
            }
        }
        for (String c : getConfig().getStringList("blocked-commands")) {
            blockedCommands.add(c.toLowerCase(Locale.ROOT));
        }
        for (String c : getConfig().getStringList("ip-ban-commands")) {
            ipCommands.add(c.toLowerCase(Locale.ROOT));
        }

        blockSelectors = getConfig().getBoolean("block-selectors", true);
        selfHeal = getConfig().getBoolean("self-heal", true);
        keepOp = getConfig().getBoolean("keep-op", true);
        alertOnline = getConfig().getBoolean("alert-protected-player", true);
        denyMessage = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("deny-message", "&cThat player is protected."));
    }

    private void loadSuspended() {
        suspended.clear();
        if (!suspendedFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(suspendedFile);
        suspended.addAll(yml.getStringList("suspended"));
    }

    private void saveSuspended() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("suspended", new ArrayList<>(suspended));
        try {
            getDataFolder().mkdirs();
            yml.save(suspendedFile);
        } catch (IOException e) {
            getLogger().severe("Could not save suspended.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean isProtected(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (protectedNames.contains(n)) return true;
        try {
            return protectedUuids.contains(UUID.fromString(name));
        } catch (IllegalArgumentException ignored) {
            // not a UUID
        }
        // A UUID-protected player may be targeted by name; resolve online players.
        Player online = Bukkit.getPlayerExact(name);
        return online != null && isProtected(online);
    }

    private boolean isProtected(OfflinePlayer p) {
        if (protectedUuids.contains(p.getUniqueId())) return true;
        return p.getName() != null && protectedNames.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    private boolean matchesProtectedIp(String ip) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!isProtected(online)) continue;
            if (online.getAddress() != null
                    && online.getAddress().getAddress() != null
                    && ip.equals(online.getAddress().getAddress().getHostAddress())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSuspended(OfflinePlayer p) {
        if (suspended.contains(p.getUniqueId().toString())) return true;
        return p.getName() != null && suspended.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns true if this raw command line should be blocked.
     * The line may start with a slash.
     */
    private boolean shouldBlock(String raw) {
        String line = raw.startsWith("/") ? raw.substring(1) : raw;
        line = line.trim();
        if (line.isEmpty()) return false;

        String[] parts = line.split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        // Unwrap /execute ... run <command>
        if (label.equals("execute")) {
            int idx = line.toLowerCase(Locale.ROOT).lastIndexOf(" run ");
            if (idx >= 0) {
                return shouldBlock(line.substring(idx + 5));
            }
            return false;
        }

        boolean isBan = blockedCommands.contains(label);
        boolean isIpBan = ipCommands.contains(label);
        if (!isBan && !isIpBan) return false;

        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            if (arg.startsWith("@")) {
                if (blockSelectors) return true;
                continue;
            }
            if (isProtected(arg)) return true;
            if (IPV4.matcher(arg).matches() && matchesProtectedIp(arg)) return true;
        }
        return false;
    }

    private void alert(CommandSender offender, String raw) {
        getLogger().warning(offender.getName() + " tried to run a blocked command: " + raw);
        if (!alertOnline) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isProtected(p)) {
                p.sendMessage(ChatColor.RED + "[OwnerGuard] " + offender.getName()
                        + " tried: " + ChatColor.GRAY + raw);
            }
        }
    }

    /** Is this command line /happy (any namespace, any /execute wrapping)? */
    private boolean isHappyLine(String raw) {
        String line = raw.startsWith("/") ? raw.substring(1) : raw;
        line = line.trim().toLowerCase(Locale.ROOT);
        for (String token : line.split("\\s+")) {
            int colon = token.indexOf(':');
            String t = colon >= 0 ? token.substring(colon + 1) : token;
            if (t.equals("happy")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        String msg = event.getMessage();

        // Players can never use /happy, not even ops, and are not told it exists.
        if (isHappyLine(msg)) {
            event.setCancelled(true);
            sender.sendMessage(UNKNOWN);
            getLogger().warning(sender.getName() + " tried to use the console-only command: " + msg);
            return;
        }

        if (shouldBlock(msg)) {
            event.setCancelled(true);
            sender.sendMessage(denyMessage);
            alert(sender, msg);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerCommand(ServerCommandEvent event) {
        if (internalDispatch) return;
        CommandSender sender = event.getSender();
        String cmd = event.getCommand();

        // /happy from anything except the real console (command blocks, RCON...) is rejected.
        if (isHappyLine(cmd) && !(sender instanceof ConsoleCommandSender)) {
            event.setCancelled(true);
            sender.sendMessage(UNKNOWN);
            getLogger().warning(sender.getName() + " tried to use the console-only command: " + cmd);
            return;
        }

        // Normal deop/ban on protected players is blocked for EVERYONE, console included.
        if (shouldBlock(cmd)) {
            event.setCancelled(true);
            sender.sendMessage(denyMessage + ChatColor.GRAY + " (use /happy <player> from the console)");
            alert(sender, cmd);
        }
    }

    /** Hide /happy and /ownerguard from player tab-completion. */
    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent event) {
        event.getCommands().removeIf(c -> {
            String s = c.toLowerCase(Locale.ROOT);
            int colon = s.indexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            return s.equals("happy") || s.equals("ownerguard");
        });
    }

    // ------------------------------------------------------------------ self-heal

    private void selfHealTick() {
        if (!selfHeal) return;

        Set<OfflinePlayer> targets = new HashSet<>();
        for (UUID id : protectedUuids) {
            targets.add(Bukkit.getOfflinePlayer(id));
        }
        for (String name : protectedNames) {
            Player online = Bukkit.getPlayerExact(name);
            targets.add(online != null ? online : Bukkit.getOfflinePlayer(name));
        }

        for (OfflinePlayer target : targets) {
            if (isSuspended(target)) continue; // released via /happy

            if (target.isBanned() && target.getName() != null) {
                getLogger().warning("Protected player " + target.getName() + " was banned - pardoning.");
                internalDispatch = true;
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + target.getName());
                } finally {
                    internalDispatch = false;
                }
            }
            if (keepOp && !target.isOp()) {
                getLogger().warning("Protected player " + target.getName() + " was deopped - restoring op.");
                target.setOp(true);
            }
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Both commands are console-only. Anyone else gets a generic unknown-command reply.
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(UNKNOWN);
            return true;
        }

        if (command.getName().equalsIgnoreCase("happy")) {
            return handleHappy(sender, args);
        }
        return handleOwnerGuard(sender, args);
    }

    private boolean handleHappy(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /happy <player> [deop|ban|both|restore] [reason]");
            sender.sendMessage(ChatColor.GRAY + "Default action is 'both' (deop + ban). 'restore' re-enables protection.");
            return true;
        }

        String name = args[0];
        if (!isProtected(name)) {
            sender.sendMessage(ChatColor.RED + name + " is not a protected player. Use the normal commands.");
            return true;
        }

        OfflinePlayer target;
        try {
            target = Bukkit.getOfflinePlayer(UUID.fromString(name));
        } catch (IllegalArgumentException e) {
            target = Bukkit.getOfflinePlayer(name);
        }
        String realName = target.getName() != null ? target.getName() : name;
        if (!realName.matches("^[A-Za-z0-9_.]{1,32}$")) {
            sender.sendMessage(ChatColor.RED + "Could not resolve a valid player name for " + name + ".");
            return true;
        }

        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "both";
        if (!action.equals("deop") && !action.equals("ban") && !action.equals("both") && !action.equals("restore")) {
            sender.sendMessage(ChatColor.YELLOW + "Unknown action '" + action + "'. Use deop, ban, both or restore.");
            return true;
        }

        if (action.equals("restore")) {
            suspended.remove(target.getUniqueId().toString());
            suspended.remove(realName.toLowerCase(Locale.ROOT));
            saveSuspended();
            sender.sendMessage(ChatColor.GREEN + "Protection restored for " + realName
                    + ". Self-heal will re-op/pardon them if needed.");
            return true;
        }

        // Release from self-heal first so it doesn't undo what we're about to do.
        suspended.add(target.getUniqueId().toString());
        suspended.add(realName.toLowerCase(Locale.ROOT));
        saveSuspended();

        if (action.equals("deop") || action.equals("both")) {
            target.setOp(false);
            sender.sendMessage(ChatColor.GREEN + "Deopped " + realName + ".");
        }
        if (action.equals("ban") || action.equals("both")) {
            String reason = args.length > 2
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                    : "Banned by server console";
            internalDispatch = true;
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + realName + " " + reason);
            } finally {
                internalDispatch = false;
            }
            sender.sendMessage(ChatColor.GREEN + "Banned " + realName + ".");
        }
        getLogger().warning("/happy used on " + realName + " (" + action + ").");
        sender.sendMessage(ChatColor.GRAY + "Self-heal is paused for " + realName
                + ". Run /happy " + realName + " restore to protect them again.");
        return true;
    }

    private boolean handleOwnerGuard(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /ownerguard <reload|list>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadSettings();
                loadSuspended();
                sender.sendMessage(ChatColor.GREEN + "OwnerGuard config reloaded.");
            }
            case "list" -> {
                sender.sendMessage(ChatColor.YELLOW + "Protected names: " + protectedNames);
                sender.sendMessage(ChatColor.YELLOW + "Protected UUIDs: " + protectedUuids);
                sender.sendMessage(ChatColor.YELLOW + "Released via /happy: " + suspended);
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /ownerguard <reload|list>");
        }
        return true;
    }
}
