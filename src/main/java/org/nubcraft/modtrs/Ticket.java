package org.nubcraft.modtrs;

import java.time.Instant;
import java.util.UUID;

public record Ticket(
        long id,
        UUID playerUuid,
        String playerName,
        Instant createdAt,
        String serverName,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String message,
        String status,
        UUID staffUuid,
        String staffName,
        String staffComment) {

    public boolean isActive() {
        return !"CLOSED".equalsIgnoreCase(status);
    }
}
