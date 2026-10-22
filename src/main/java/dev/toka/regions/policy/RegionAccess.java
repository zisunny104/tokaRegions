package dev.toka.regions.policy;

import dev.toka.regions.model.Region;

/**
 * Who may build or use blocks inside a region, from the region's protection mode alone.
 * OPEN: anyone. LOCK: administrators only, even inside a claim. PROTECT: administrators, and players who already own land;
 * where a claim covers the spot, the claim's own rules decide instead and the region steps aside.
 * What tokaLand cannot tell us (not installed, or not ready) is passed as null and never counts in the player's favour.
 */
public final class RegionAccess {
    private RegionAccess() {}

    /** True when the region forbids this player here. */
    public static boolean denies(Region.Protection mode, boolean administrator, Boolean claimedHere, Boolean ownsLand) {
        return switch (mode) {
            case OPEN -> false;
            case LOCK -> !administrator;
            case PROTECT -> {
                if (administrator) yield false;
                if (Boolean.TRUE.equals(claimedHere)) yield false;
                yield !Boolean.TRUE.equals(ownsLand);
            }
        };
    }
}
