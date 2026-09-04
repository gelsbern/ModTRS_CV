package org.nubcraft.modtrs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class ModTRS extends JavaPlugin implements Listener {

    private static final String PROXY_CHANNEL = "nubcraft:modtrs";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    private Database database;
    private String serverName;
    private long lastEventId;
    private BukkitTask eventPollTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverName = getConfig().getString("server-name", "survival");

        database = new Database(this);

        try {
            database.initialize();
            lastEventId = database.getOrCreateBackendCursor(serverName);
        } catch (Exception exception) {
            getLogger().severe(
                    "Could not initialize the ModTRS MariaDB database: "
                            + exception.getMessage()
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getMessenger().registerOutgoingPluginChannel(
                this,
                PROXY_CHANNEL
        );

        getServer().getPluginManager().registerEvents(this, this);

        eventPollTask = getServer().getScheduler().runTaskTimer(
                this,
                this::pollNetworkEvents,
                20L,
                20L
        );

        getLogger().info(
                "ModTRS ticket system ready on backend " + serverName
                        + " with network event synchronization."
        );
    }

    @Override
    public void onDisable() {
        if (eventPollTask != null) {
            eventPollTask.cancel();
            eventPollTask = null;
        }

        getServer().getMessenger().unregisterOutgoingPluginChannel(
                this,
                PROXY_CHANNEL
        );

        if (database != null) {
            database.close();
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args) {

        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "modreq" -> handleModreq(sender, args);
            case "check" -> handleCheck(sender, args);
            case "check-id" -> handleCheckId(sender, args);
            case "claim" -> handleStatus(sender, args, "CLAIMED");
            case "unclaim" -> handleUnclaim(sender, args);
            case "hold" -> handleStatus(sender, args, "HOLD");
            case "complete" -> handleComplete(sender, args);
            case "reopen" -> handleStatus(sender, args, "OPEN");
            case "tp-id" -> handleTeleport(sender, args);
            case "modreq-ban" -> handleBan(sender, args, true);
            case "modreq-unban" -> handleBan(sender, args, false);
            case "modtrs" -> handleAdmin(sender, args);
            default -> false;
        };
    }

    private boolean handleModreq(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (!player.hasPermission("modtrs.command.modreq")) {
            deny(player);
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(
                    ChatColor.YELLOW + "Usage: "
                            + ChatColor.WHITE + "/modreq <message>"
            );
            return true;
        }

        String message = String.join(" ", args).trim();
        if (message.length() > 1000) {
            player.sendMessage(
                    ChatColor.RED + "Your request is too long (maximum 1000 characters)."
            );
            return true;
        }

        try {
            if (database.isBanned(player.getUniqueId())) {
                player.sendMessage(
                        ChatColor.RED + "You are not allowed to submit moderator requests."
                );
                return true;
            }

            int maximum = Math.max(
                    1,
                    getConfig().getInt("max-open-requests", 5)
            );

            if (database.countActive(player.getUniqueId()) >= maximum) {
                player.sendMessage(
                        ChatColor.RED + "You already have the maximum of "
                                + maximum + " open moderator requests."
                );
                return true;
            }

            long id = database.createTicket(player, serverName, message);

            player.sendMessage(
                    ChatColor.GREEN + "Moderator request #" + id
                            + " submitted. Staff have been notified."
            );

            database.addEvent(
                    id,
                    "NEW",
                    serverName,
                    player.getUniqueId(),
                    player.getName()
            );

        } catch (SQLException exception) {
            databaseError(player, exception);
        }

        return true;
    }

    private boolean handleCheck(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        boolean staff = player.hasPermission("modtrs.command.check");
        boolean self = player.hasPermission("modtrs.command.check.self");

        if (!staff && !self) {
            deny(player);
            return true;
        }

        int page = parsePage(args);
        int pageSize = Math.max(5, getConfig().getInt("requests-per-page", 8));
        int offset = (page - 1) * pageSize;

        try {
            List<Ticket> tickets = staff
                    ? database.listOpen(pageSize, offset)
                    : database.listPlayer(player.getUniqueId(), pageSize, offset);

            if (tickets.isEmpty()) {
                player.sendMessage(
                        ChatColor.GRAY + (staff
                                ? "There are no open moderator requests on this page."
                                : "You have no moderator requests on this page.")
                );
                return true;
            }

            player.sendMessage(
                    ChatColor.GOLD + (staff
                            ? "Open moderator requests"
                            : "Your moderator requests")
                            + ChatColor.DARK_GRAY + " - page " + page
            );

            for (Ticket ticket : tickets) {
                sendTicketSummary(player, ticket, staff);
            }

            if (staff) {
                int total = database.countOpenAll();
                int pages = Math.max(1, (total + pageSize - 1) / pageSize);
                player.sendMessage(
                        ChatColor.GRAY + "Page " + page + " of " + pages
                );
            }

        } catch (SQLException exception) {
            databaseError(player, exception);
        }

        return true;
    }

    private boolean handleCheckId(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        Long id = parseId(player, args);
        if (id == null) {
            return true;
        }

        try {
            Optional<Ticket> optional = database.getTicket(id);
            if (optional.isEmpty()) {
                player.sendMessage(ChatColor.RED + "No request exists with ID " + id + ".");
                return true;
            }

            Ticket ticket = optional.get();
            boolean staff = player.hasPermission("modtrs.command.check");
            boolean owner = ticket.playerUuid().equals(player.getUniqueId());

            if (!staff && !owner) {
                deny(player);
                return true;
            }

            sendTicketDetail(player, ticket);

        } catch (SQLException exception) {
            databaseError(player, exception);
        }

        return true;
    }

    private boolean handleStatus(
            CommandSender sender,
            String[] args,
            String status) {

        Player staff = requireStaff(sender, "modtrs.command.complete");
        if (staff == null) {
            return true;
        }

        Long id = parseId(staff, args);
        if (id == null) {
            return true;
        }

        try {
            Optional<Ticket> optional = database.getTicket(id);
            if (optional.isEmpty()) {
                staff.sendMessage(ChatColor.RED + "No request exists with ID " + id + ".");
                return true;
            }

            database.setStatus(
                    id,
                    status,
                    staff.getUniqueId(),
                    staff.getName(),
                    null
            );

            staff.sendMessage(
                    ChatColor.GREEN + "Request #" + id + " is now "
                            + statusText(status) + ChatColor.GREEN + "."
            );

            database.addEvent(
                    id,
                    status,
                    serverName,
                    staff.getUniqueId(),
                    staff.getName()
            );

        } catch (SQLException exception) {
            databaseError(staff, exception);
        }

        return true;
    }

    private boolean handleUnclaim(CommandSender sender, String[] args) {
        Player staff = requireStaff(sender, "modtrs.command.complete");
        if (staff == null) {
            return true;
        }

        Long id = parseId(staff, args);
        if (id == null) {
            return true;
        }

        try {
            if (database.getTicket(id).isEmpty()) {
                staff.sendMessage(ChatColor.RED + "No request exists with ID " + id + ".");
                return true;
            }

            database.unclaim(id);
            database.addEvent(
                    id,
                    "UNCLAIM",
                    serverName,
                    staff.getUniqueId(),
                    staff.getName()
            );
            staff.sendMessage(ChatColor.GREEN + "Request #" + id + " is open and unclaimed.");

        } catch (SQLException exception) {
            databaseError(staff, exception);
        }

        return true;
    }

    private boolean handleComplete(CommandSender sender, String[] args) {
        Player staff = requireStaff(sender, "modtrs.command.complete");
        if (staff == null) {
            return true;
        }

        Long id = parseId(staff, args);
        if (id == null) {
            return true;
        }

        String comment = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "";

        try {
            Optional<Ticket> optional = database.getTicket(id);
            if (optional.isEmpty()) {
                staff.sendMessage(ChatColor.RED + "No request exists with ID " + id + ".");
                return true;
            }

            Ticket ticket = optional.get();
            database.setStatus(
                    id,
                    "CLOSED",
                    staff.getUniqueId(),
                    staff.getName(),
                    comment
            );

            staff.sendMessage(ChatColor.GREEN + "Request #" + id + " completed.");

            database.addEvent(
                    id,
                    "CLOSED",
                    serverName,
                    staff.getUniqueId(),
                    staff.getName()
            );

        } catch (SQLException exception) {
            databaseError(staff, exception);
        }

        return true;
    }

    private boolean handleTeleport(CommandSender sender, String[] args) {
        Player staff = requireStaff(sender, "modtrs.command.teleport");
        if (staff == null) {
            return true;
        }

        Long id = parseId(staff, args);
        if (id == null) {
            return true;
        }

        try {
            Optional<Ticket> optional = database.getTicket(id);
            if (optional.isEmpty()) {
                staff.sendMessage(ChatColor.RED + "No request exists with ID " + id + ".");
                return true;
            }

            Ticket ticket = optional.get();
            if (!ticket.serverName().equalsIgnoreCase(serverName)) {
                database.queueTeleport(
                        staff.getUniqueId(),
                        id,
                        ticket.serverName()
                );

                if (!requestServerSwitch(staff, ticket.serverName())) {
                    staff.sendMessage(
                            ChatColor.RED + "Could not request a network switch to "
                                    + ticket.serverName() + "."
                    );
                    return true;
                }

                staff.sendMessage(
                        ChatColor.YELLOW + "Connecting you to "
                                + ChatColor.WHITE + ticket.serverName()
                                + ChatColor.YELLOW + " for request #" + id + "..."
                );
                return true;
            }

            World world = Bukkit.getWorld(ticket.world());
            if (world == null) {
                staff.sendMessage(ChatColor.RED + "World " + ticket.world() + " is not loaded.");
                return true;
            }

            staff.teleport(new Location(
                    world,
                    ticket.x(),
                    ticket.y(),
                    ticket.z(),
                    ticket.yaw(),
                    ticket.pitch()
            ));

            staff.sendMessage(ChatColor.GREEN + "Teleported to request #" + id + ".");

        } catch (SQLException exception) {
            databaseError(staff, exception);
        }

        return true;
    }

    private boolean handleBan(
            CommandSender sender,
            String[] args,
            boolean ban) {

        Player staff = requireStaff(
                sender,
                ban ? "modtrs.command.ban" : "modtrs.command.unban"
        );
        if (staff == null) {
            return true;
        }

        if (args.length == 0) {
            staff.sendMessage(
                    ChatColor.YELLOW + "Usage: " + ChatColor.WHITE
                            + (ban
                            ? "/modreq-ban <player> [reason]"
                            : "/modreq-unban <player>")
            );
            return true;
        }

        String name = args[0];

        try {
            UUID uuid = resolveKnownUuid(name);
            if (uuid == null) {
                staff.sendMessage(
                        ChatColor.RED + "I do not know a player named " + name + "."
                );
                return true;
            }

            if (ban) {
                String reason = args.length > 1
                        ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                        : "";

                database.ban(
                        uuid,
                        name,
                        staff.getUniqueId(),
                        staff.getName(),
                        reason
                );
                staff.sendMessage(ChatColor.GREEN + name + " can no longer submit mod requests.");
            } else {
                boolean changed = database.unban(uuid);
                staff.sendMessage(
                        changed
                                ? ChatColor.GREEN + name + " may submit mod requests again."
                                : ChatColor.YELLOW + name + " was not banned from ModTRS."
                );
            }

        } catch (SQLException exception) {
            databaseError(staff, exception);
        }

        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("modtrs.admin")) {
            deny(sender);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            serverName = getConfig().getString("server-name", "survival");
            database.close();
            try {
                database.initialize();
                lastEventId = database.getOrCreateBackendCursor(serverName);
                sender.sendMessage(ChatColor.GREEN + "ModTRS configuration reloaded.");
            } catch (SQLException exception) {
                databaseError(sender, exception);
            }
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: " + ChatColor.WHITE + "/modtrs reload");
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("modtrs.command.modreq")) {
            getServer().getScheduler().runTaskLater(
                    this,
                    () -> notifyCompleted(player),
                    40L
            );
        }

        if (player.hasPermission("modtrs.command.teleport")) {
            getServer().getScheduler().runTaskLater(
                    this,
                    () -> completePendingTeleport(player),
                    20L
            );
        }
    }

    private void notifyCompleted(Player player) {
        try {
            for (Ticket ticket : database.completedUnnotified(player.getUniqueId())) {
                player.sendMessage(
                        ChatColor.GREEN + "Your moderator request #" + ticket.id()
                                + " has been completed"
                                + (ticket.staffName() == null
                                ? "."
                                : " by " + ticket.staffName() + ".")
                );

                if (ticket.staffComment() != null
                        && !ticket.staffComment().isBlank()) {
                    player.sendMessage(
                            ChatColor.GRAY + "Staff note: "
                                    + ChatColor.WHITE + ticket.staffComment()
                    );
                }

                database.markNotified(ticket.id());
            }
        } catch (SQLException exception) {
            getLogger().warning(
                    "Could not deliver completed ticket notices to "
                            + player.getName() + ": " + exception.getMessage()
            );
        }
    }

    private void pollNetworkEvents() {
        try {
            List<TicketEvent> events = database.listEventsAfter(
                    lastEventId,
                    50
            );

            for (TicketEvent event : events) {
                processNetworkEvent(event);
                lastEventId = event.id();
                database.updateBackendCursor(serverName, lastEventId);
            }
        } catch (SQLException exception) {
            getLogger().warning(
                    "Could not poll ModTRS network events: "
                            + exception.getMessage()
            );
        }
    }

    private void processNetworkEvent(TicketEvent event) throws SQLException {
        Optional<Ticket> optional = database.getTicket(event.ticketId());
        if (optional.isEmpty()) {
            return;
        }

        Ticket ticket = optional.get();
        String actor = event.actorName() == null
                ? "Staff"
                : event.actorName();

        String message = switch (event.eventType().toUpperCase(Locale.ROOT)) {
            case "NEW" ->
                    ChatColor.GOLD + "[ModTRS] "
                            + ChatColor.YELLOW + "New request #" + ticket.id()
                            + ChatColor.GRAY + " from "
                            + ChatColor.WHITE + ticket.playerName()
                            + ChatColor.DARK_GRAY + " [" + ticket.serverName() + "] "
                            + ChatColor.WHITE + abbreviate(ticket.message(), 120);

            case "CLAIMED" ->
                    ChatColor.GOLD + "[ModTRS] "
                            + ChatColor.WHITE + actor
                            + ChatColor.GRAY + " claimed request #" + ticket.id() + ".";

            case "HOLD" ->
                    ChatColor.GOLD + "[ModTRS] "
                            + ChatColor.WHITE + actor
                            + ChatColor.GRAY + " put request #" + ticket.id() + " on hold.";

            case "OPEN" ->
                    ChatColor.GOLD + "[ModTRS] "
                            + ChatColor.WHITE + actor
                            + ChatColor.GRAY + " reopened request #" + ticket.id() + ".";

            case "UNCLAIM" ->
                    ChatColor.GOLD + "[ModTRS] "
                            + ChatColor.WHITE + actor
                            + ChatColor.GRAY + " returned request #" + ticket.id()
                            + " to the open queue.";

            case "CLOSED" ->
                    ChatColor.GOLD + "[ModTRS] "
                            + ChatColor.WHITE + actor
                            + ChatColor.GRAY + " completed request #" + ticket.id() + ".";

            default -> null;
        };

        if (message != null) {
            notifyStaff(message, event.actorUuid());
        }

        if ("CLOSED".equalsIgnoreCase(event.eventType())) {
            Player owner = Bukkit.getPlayer(ticket.playerUuid());
            if (owner != null && owner.isOnline()) {
                notifyCompleted(owner);
            }
        }
    }

    private void completePendingTeleport(Player staff) {
        if (!staff.isOnline()) {
            return;
        }

        try {
            Optional<Long> pending = database.takePendingTeleport(
                    staff.getUniqueId(),
                    serverName
            );

            if (pending.isEmpty()) {
                return;
            }

            Optional<Ticket> optional = database.getTicket(pending.get());
            if (optional.isEmpty()) {
                staff.sendMessage(
                        ChatColor.RED + "The ModTRS request no longer exists."
                );
                return;
            }

            Ticket ticket = optional.get();
            if (!ticket.serverName().equalsIgnoreCase(serverName)) {
                return;
            }

            World world = Bukkit.getWorld(ticket.world());
            if (world == null) {
                staff.sendMessage(
                        ChatColor.RED + "World " + ticket.world() + " is not loaded."
                );
                return;
            }

            staff.teleport(new Location(
                    world,
                    ticket.x(),
                    ticket.y(),
                    ticket.z(),
                    ticket.yaw(),
                    ticket.pitch()
            ));

            staff.sendMessage(
                    ChatColor.GREEN + "Teleported to request #" + ticket.id() + "."
            );

        } catch (SQLException exception) {
            databaseError(staff, exception);
        }
    }

    private boolean requestServerSwitch(
            Player player,
            String targetServer) {

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("CONNECT");
                output.writeUTF(player.getUniqueId().toString());
                output.writeUTF(targetServer);
            }

            player.sendPluginMessage(
                    this,
                    PROXY_CHANNEL,
                    bytes.toByteArray()
            );
            return true;

        } catch (IOException exception) {
            getLogger().warning(
                    "Could not encode ModTRS proxy switch request: "
                            + exception.getMessage()
            );
            return false;
        }
    }

    private void sendTicketSummary(
            Player viewer,
            Ticket ticket,
            boolean showPlayer) {

        String who = showPlayer
                ? ChatColor.WHITE + ticket.playerName() + ChatColor.GRAY + " - "
                : "";

        viewer.sendMessage(
                ChatColor.YELLOW + "#" + ticket.id() + " "
                        + statusText(ticket.status()) + ChatColor.GRAY + " "
                        + who
                        + ChatColor.DARK_GRAY + "[" + ticket.serverName() + "] "
                        + ChatColor.WHITE + abbreviate(ticket.message(), 90)
        );
    }

    private void sendTicketDetail(Player viewer, Ticket ticket) {
        viewer.sendMessage(ChatColor.GOLD + "Moderator request #" + ticket.id());
        viewer.sendMessage(ChatColor.GRAY + "Status: " + statusText(ticket.status()));
        viewer.sendMessage(
                ChatColor.GRAY + "Player: " + ChatColor.WHITE + ticket.playerName()
        );
        viewer.sendMessage(
                ChatColor.GRAY + "Created: " + ChatColor.WHITE
                        + TIME_FORMAT.format(ticket.createdAt())
        );
        viewer.sendMessage(
                ChatColor.GRAY + "Location: " + ChatColor.WHITE
                        + ticket.serverName() + "/" + ticket.world() + " "
                        + Math.round(ticket.x()) + " "
                        + Math.round(ticket.y()) + " "
                        + Math.round(ticket.z())
        );
        viewer.sendMessage(
                ChatColor.GRAY + "Request: " + ChatColor.WHITE + ticket.message()
        );

        if (ticket.staffName() != null) {
            viewer.sendMessage(
                    ChatColor.GRAY + "Staff: " + ChatColor.WHITE + ticket.staffName()
            );
        }

        if (ticket.staffComment() != null
                && !ticket.staffComment().isBlank()) {
            viewer.sendMessage(
                    ChatColor.GRAY + "Staff note: " + ChatColor.WHITE + ticket.staffComment()
            );
        }
    }

    private Player requireStaff(CommandSender sender, String permission) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return null;
        }

        if (!player.hasPermission(permission)) {
            deny(player);
            return null;
        }

        return player;
    }

    private Long parseId(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "A request ID is required.");
            return null;
        }

        try {
            long id = Long.parseLong(args[0]);
            if (id < 1) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Invalid request ID: " + args[0]);
            return null;
        }
    }

    private int parsePage(String[] args) {
        if (args.length == 0) {
            return 1;
        }

        String value = args[0].toLowerCase(Locale.ROOT);
        if (value.startsWith("p:")) {
            value = value.substring(2);
        }

        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private UUID resolveKnownUuid(String name) throws SQLException {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }

        Optional<UUID> known = database.findKnownUuid(name);
        if (known.isPresent()) {
            return known.get();
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    private void notifyStaff(String message, UUID skipPlayer) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("modtrs.staff")) {
                continue;
            }

            if (skipPlayer != null
                    && skipPlayer.equals(player.getUniqueId())) {
                continue;
            }

            player.sendMessage(message);
        }
    }

    private String statusText(String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "OPEN" -> ChatColor.YELLOW + "OPEN";
            case "CLAIMED" -> ChatColor.RED + "CLAIMED";
            case "HOLD" -> ChatColor.LIGHT_PURPLE + "HOLD";
            case "CLOSED" -> ChatColor.GREEN + "CLOSED";
            default -> ChatColor.GRAY + status;
        };
    }

    private String abbreviate(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, Math.max(0, maximum - 3)) + "...";
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "You do not have access to that command.");
    }

    private void databaseError(CommandSender sender, SQLException exception) {
        sender.sendMessage(
                ChatColor.RED + "The moderator request system is temporarily unavailable."
        );
        getLogger().warning("Database operation failed: " + exception.getMessage());
    }
}
