package dev.toka.regions.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A personal change to the tax rate: positive adds, negative relieves. It always has a reason and who set it, and may expire.
 * Keyed by player UUID, never by name.
 */
public record TaxAdjustment(UUID player, double rate, String reason, UUID setBy, long createdAt, Long expiresAt) {
    public TaxAdjustment {
        Objects.requireNonNull(player, "player");
        if (!Double.isFinite(rate)) throw new IllegalArgumentException("rate must be finite");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("a reason is required");
    }

    public boolean activeAt(long now) { return expiresAt == null || now < expiresAt; }
}
