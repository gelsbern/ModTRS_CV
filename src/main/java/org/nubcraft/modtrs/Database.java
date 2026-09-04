package org.nubcraft.modtrs;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Database implements AutoCloseable {

    private final JavaPlugin plugin;
    private Connection connection;

    public Database(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void initialize() throws SQLException {

        try (Statement statement = connection().createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS modtrs_requests (
                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                        player_uuid CHAR(36) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        server_name VARCHAR(64) NOT NULL,
                        world VARCHAR(128) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL DEFAULT 0,
                        pitch FLOAT NOT NULL DEFAULT 0,
                        message TEXT NOT NULL,
                        status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
                        staff_uuid CHAR(36) NULL,
                        staff_name VARCHAR(32) NULL,
                        staff_comment TEXT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        notified TINYINT(1) NOT NULL DEFAULT 0,
                        PRIMARY KEY (id),
                        KEY idx_modtrs_status_created (status, created_at),
                        KEY idx_modtrs_player_status (player_uuid, status),
                        KEY idx_modtrs_player_name (player_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS modtrs_bans (
                        player_uuid CHAR(36) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        banned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        banned_by_uuid CHAR(36) NULL,
                        banned_by_name VARCHAR(32) NULL,
                        reason VARCHAR(255) NULL,
                        PRIMARY KEY (player_uuid),
                        KEY idx_modtrs_ban_name (player_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS modtrs_events (
                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                        ticket_id BIGINT UNSIGNED NOT NULL,
                        event_type VARCHAR(24) NOT NULL,
                        source_server VARCHAR(64) NOT NULL,
                        actor_uuid CHAR(36) NULL,
                        actor_name VARCHAR(32) NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        KEY idx_modtrs_events_ticket (ticket_id),
                        KEY idx_modtrs_events_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS modtrs_backend_state (
                        server_name VARCHAR(64) NOT NULL,
                        last_event_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (server_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS modtrs_pending_teleports (
                        staff_uuid CHAR(36) NOT NULL,
                        ticket_id BIGINT UNSIGNED NOT NULL,
                        target_server VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (staff_uuid),
                        KEY idx_modtrs_pending_expiry (expires_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        }
    }

    public synchronized long createTicket(
            Player player,
            String serverName,
            String message) throws SQLException {

        Location location = player.getLocation();

        String sql = """
                INSERT INTO modtrs_requests
                    (player_uuid, player_name, server_name, world,
                     x, y, z, yaw, pitch, message, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')
                """;

        try (PreparedStatement statement = connection().prepareStatement(
                sql,
                Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, player.getName());
            statement.setString(3, serverName);
            statement.setString(4, location.getWorld().getName());
            statement.setDouble(5, location.getX());
            statement.setDouble(6, location.getY());
            statement.setDouble(7, location.getZ());
            statement.setFloat(8, location.getYaw());
            statement.setFloat(9, location.getPitch());
            statement.setString(10, message);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }

        throw new SQLException("No generated ticket ID was returned");
    }

    public synchronized int countActive(UUID playerUuid) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM modtrs_requests
                WHERE player_uuid = ? AND status <> 'CLOSED'
                """;

        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public synchronized int countOpenAll() throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT COUNT(*) FROM modtrs_requests WHERE status <> 'CLOSED'")) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public synchronized Optional<Ticket> getTicket(long id) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT * FROM modtrs_requests WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readTicket(result))
                        : Optional.empty();
            }
        }
    }

    public synchronized List<Ticket> listOpen(
            int limit,
            int offset) throws SQLException {

        String sql = """
                SELECT * FROM modtrs_requests
                WHERE status <> 'CLOSED'
                ORDER BY created_at ASC, id ASC
                LIMIT ? OFFSET ?
                """;

        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            return readTickets(statement);
        }
    }

    public synchronized List<Ticket> listPlayer(
            UUID playerUuid,
            int limit,
            int offset) throws SQLException {

        String sql = """
                SELECT * FROM modtrs_requests
                WHERE player_uuid = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """;

        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            return readTickets(statement);
        }
    }

    public synchronized void setStatus(
            long id,
            String status,
            UUID staffUuid,
            String staffName,
            String comment) throws SQLException {

        String sql = """
                UPDATE modtrs_requests
                SET status = ?, staff_uuid = ?, staff_name = ?,
                    staff_comment = ?, notified = CASE WHEN ? = 'CLOSED' THEN 0 ELSE notified END
                WHERE id = ?
                """;

        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, status);
            setUuid(statement, 2, staffUuid);
            statement.setString(3, staffName);
            statement.setString(4, emptyToNull(comment));
            statement.setString(5, status);
            statement.setLong(6, id);
            statement.executeUpdate();
        }
    }

    public synchronized void unclaim(long id) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement("""
                UPDATE modtrs_requests
                SET status = 'OPEN', staff_uuid = NULL,
                    staff_name = NULL, staff_comment = NULL
                WHERE id = ?
                """)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    public synchronized boolean isBanned(UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT 1 FROM modtrs_bans WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public synchronized void ban(
            UUID playerUuid,
            String playerName,
            UUID staffUuid,
            String staffName,
            String reason) throws SQLException {

        String sql = """
                INSERT INTO modtrs_bans
                    (player_uuid, player_name, banned_by_uuid, banned_by_name, reason)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    player_name = VALUES(player_name),
                    banned_at = CURRENT_TIMESTAMP,
                    banned_by_uuid = VALUES(banned_by_uuid),
                    banned_by_name = VALUES(banned_by_name),
                    reason = VALUES(reason)
                """;

        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            setUuid(statement, 3, staffUuid);
            statement.setString(4, staffName);
            statement.setString(5, emptyToNull(reason));
            statement.executeUpdate();
        }
    }

    public synchronized boolean unban(UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "DELETE FROM modtrs_bans WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid.toString());
            return statement.executeUpdate() > 0;
        }
    }

    public synchronized Optional<UUID> findKnownUuid(String playerName)
            throws SQLException {

        String sql = """
                SELECT player_uuid
                FROM modtrs_requests
                WHERE LOWER(player_name) = LOWER(?)
                ORDER BY id DESC
                LIMIT 1
                """;

        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(
                            UUID.fromString(result.getString(1))
                    );
                }
            }
        }

        return Optional.empty();
    }

    public synchronized List<Ticket> completedUnnotified(UUID playerUuid)
            throws SQLException {

        String sql = """
                SELECT * FROM modtrs_requests
                WHERE player_uuid = ? AND status = 'CLOSED' AND notified = 0
                ORDER BY updated_at ASC
                """;

        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            return readTickets(statement);
        }
    }

    public synchronized void markNotified(long id) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "UPDATE modtrs_requests SET notified = 1 WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    public synchronized void addEvent(
            long ticketId,
            String eventType,
            String sourceServer,
            UUID actorUuid,
            String actorName) throws SQLException {

        try (PreparedStatement statement = connection().prepareStatement("""
                INSERT INTO modtrs_events
                    (ticket_id, event_type, source_server, actor_uuid, actor_name)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, ticketId);
            statement.setString(2, eventType);
            statement.setString(3, sourceServer);
            setUuid(statement, 4, actorUuid);
            statement.setString(5, actorName);
            statement.executeUpdate();
        }
    }

    public synchronized long getOrCreateBackendCursor(String serverName)
            throws SQLException {

        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT last_event_id FROM modtrs_backend_state WHERE server_name = ?")) {
            statement.setString(1, serverName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
        }

        long currentMax;
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT COALESCE(MAX(id), 0) FROM modtrs_events")) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                currentMax = result.getLong(1);
            }
        }

        try (PreparedStatement statement = connection().prepareStatement("""
                INSERT INTO modtrs_backend_state (server_name, last_event_id)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE server_name = VALUES(server_name)
                """)) {
            statement.setString(1, serverName);
            statement.setLong(2, currentMax);
            statement.executeUpdate();
        }

        return currentMax;
    }

    public synchronized List<TicketEvent> listEventsAfter(
            long afterId,
            int limit) throws SQLException {

        List<TicketEvent> events = new ArrayList<>();

        try (PreparedStatement statement = connection().prepareStatement("""
                SELECT id, ticket_id, event_type, source_server,
                       actor_uuid, actor_name, created_at
                FROM modtrs_events
                WHERE id > ?
                ORDER BY id ASC
                LIMIT ?
                """)) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit);

            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String actorUuid = result.getString("actor_uuid");
                    Timestamp created = result.getTimestamp("created_at");

                    events.add(new TicketEvent(
                            result.getLong("id"),
                            result.getLong("ticket_id"),
                            result.getString("event_type"),
                            result.getString("source_server"),
                            actorUuid == null ? null : UUID.fromString(actorUuid),
                            result.getString("actor_name"),
                            created == null ? Instant.EPOCH : created.toInstant()
                    ));
                }
            }
        }

        return events;
    }

    public synchronized void updateBackendCursor(
            String serverName,
            long eventId) throws SQLException {

        try (PreparedStatement statement = connection().prepareStatement("""
                INSERT INTO modtrs_backend_state (server_name, last_event_id)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE last_event_id = VALUES(last_event_id)
                """)) {
            statement.setString(1, serverName);
            statement.setLong(2, eventId);
            statement.executeUpdate();
        }
    }

    public synchronized void queueTeleport(
            UUID staffUuid,
            long ticketId,
            String targetServer) throws SQLException {

        try (PreparedStatement statement = connection().prepareStatement("""
                INSERT INTO modtrs_pending_teleports
                    (staff_uuid, ticket_id, target_server, expires_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    ticket_id = VALUES(ticket_id),
                    target_server = VALUES(target_server),
                    created_at = CURRENT_TIMESTAMP,
                    expires_at = VALUES(expires_at)
                """)) {
            statement.setString(1, staffUuid.toString());
            statement.setLong(2, ticketId);
            statement.setString(3, targetServer);
            statement.setTimestamp(
                    4,
                    Timestamp.from(Instant.now().plusSeconds(45))
            );
            statement.executeUpdate();
        }
    }

    public synchronized Optional<Long> takePendingTeleport(
            UUID staffUuid,
            String targetServer) throws SQLException {

        try (PreparedStatement cleanup = connection().prepareStatement(
                "DELETE FROM modtrs_pending_teleports WHERE expires_at <= CURRENT_TIMESTAMP")) {
            cleanup.executeUpdate();
        }

        Long ticketId = null;

        try (PreparedStatement statement = connection().prepareStatement("""
                SELECT ticket_id
                FROM modtrs_pending_teleports
                WHERE staff_uuid = ?
                  AND LOWER(target_server) = LOWER(?)
                  AND expires_at > CURRENT_TIMESTAMP
                """)) {
            statement.setString(1, staffUuid.toString());
            statement.setString(2, targetServer);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    ticketId = result.getLong(1);
                }
            }
        }

        if (ticketId == null) {
            return Optional.empty();
        }

        try (PreparedStatement statement = connection().prepareStatement(
                "DELETE FROM modtrs_pending_teleports WHERE staff_uuid = ?")) {
            statement.setString(1, staffUuid.toString());
            statement.executeUpdate();
        }

        return Optional.of(ticketId);
    }

    private List<Ticket> readTickets(PreparedStatement statement)
            throws SQLException {

        List<Ticket> tickets = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                tickets.add(readTicket(result));
            }
        }
        return tickets;
    }

    private Ticket readTicket(ResultSet result) throws SQLException {
        Timestamp created = result.getTimestamp("created_at");
        String staffUuid = result.getString("staff_uuid");

        return new Ticket(
                result.getLong("id"),
                UUID.fromString(result.getString("player_uuid")),
                result.getString("player_name"),
                created == null ? Instant.EPOCH : created.toInstant(),
                result.getString("server_name"),
                result.getString("world"),
                result.getDouble("x"),
                result.getDouble("y"),
                result.getDouble("z"),
                result.getFloat("yaw"),
                result.getFloat("pitch"),
                result.getString("message"),
                result.getString("status"),
                staffUuid == null ? null : UUID.fromString(staffUuid),
                result.getString("staff_name"),
                result.getString("staff_comment")
        );
    }

    private synchronized Connection connection() throws SQLException {
        if (connection != null
                && !connection.isClosed()
                && connection.isValid(2)) {
            return connection;
        }

        String host = plugin.getConfig().getString("database.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("database.port", 3306);
        String database = plugin.getConfig().getString("database.name", "modtrs");
        String username = plugin.getConfig().getString("database.username", "crafty");
        String password = plugin.getConfig().getString("database.password", "");

        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8"
                + "&connectTimeout=5000&socketTimeout=5000";

        connection = DriverManager.getConnection(url, username, password);
        return connection;
    }

    private static void setUuid(
            PreparedStatement statement,
            int index,
            UUID uuid) throws SQLException {

        if (uuid == null) {
            statement.setString(index, null);
        } else {
            statement.setString(index, uuid.toString());
        }
    }

    private static String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    @Override
    public synchronized void close() {
        if (connection == null) {
            return;
        }

        try {
            connection.close();
        } catch (SQLException ignored) {
            // Server is shutting down; nothing useful remains to do.
        } finally {
            connection = null;
        }
    }
}
