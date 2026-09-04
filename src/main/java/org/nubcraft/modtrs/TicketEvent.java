package org.nubcraft.modtrs;

import java.time.Instant;
import java.util.UUID;

public record TicketEvent(
        long id,
        long ticketId,
        String eventType,
        String sourceServer,
        UUID actorUuid,
        String actorName,
        Instant createdAt) {
}
