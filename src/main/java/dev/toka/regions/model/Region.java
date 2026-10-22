package dev.toka.regions.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A named area of one world. Players are affiliated with a region by its id, never by name, so renaming is safe.
 * The shape is the region's own and shares nothing with tokaLand's selections.
 */
public record Region(UUID id, String name, Map<String, String> localizedNames, String world, List<RegionSelection> selections,
                     Protection protection, LandRules land, Teleport teleport, Treasury treasury, String tag) {
    public enum Protection { OPEN, PROTECT, LOCK }

    /**
     * Whether the region has a money account. The account's id is the region's id. NONE: payments for land here go to the
     * main account. OWN: the region keeps what is paid here. MAIN: like OWN, and also receives for regions with no account
     * (at most one region is MAIN).
     */
    public enum Treasury { NONE, OWN, MAIN }

    /** A point inside the region where players can be sent. */
    public record Teleport(double x, double y, double z, float yaw, float pitch) {
        public Teleport {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
                throw new IllegalArgumentException("teleport must be finite");
        }
    }

    public Region(UUID id, String name, Map<String, String> localizedNames, String world, List<RegionSelection> selections,
                  Protection protection, LandRules land, Teleport teleport) {
        this(id, name, localizedNames, world, selections, protection, land, teleport, Treasury.NONE);
    }

    public Region(UUID id, String name, Map<String, String> localizedNames, String world, List<RegionSelection> selections,
                  Protection protection, LandRules land, Teleport teleport, Treasury treasury) {
        this(id, name, localizedNames, world, selections, protection, land, teleport, treasury, "");
    }

    public Region {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
        selections = List.copyOf(Objects.requireNonNull(selections, "selections"));
        if (selections.isEmpty()) throw new IllegalArgumentException("a region needs at least one selection");
        localizedNames = localizedNames == null ? Map.of() : Map.copyOf(localizedNames);
        protection = protection == null ? Protection.OPEN : protection;
        land = land == null ? LandRules.closed() : land;
        treasury = treasury == null ? Treasury.NONE : treasury;
        tag = tag == null ? "" : tag;
    }

    public boolean hasAccount() { return treasury != Treasury.NONE; }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName) && selections.stream().anyMatch(part -> part.contains(x, y, z));
    }

    public boolean overlaps(Region other) {
        return world.equals(other.world) && selections.stream().anyMatch(a -> other.selections.stream().anyMatch(a::overlaps));
    }

    public String displayName(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) return name;
        return localizedNames.getOrDefault(languageTag, localizedNames.getOrDefault(languageTag.split("[-_]")[0], name));
    }
}
