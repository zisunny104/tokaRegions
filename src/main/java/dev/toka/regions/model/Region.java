package dev.toka.regions.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A named area of one world. Players are affiliated with a region by its id, never by name, so renaming is safe.
 * The shape is the region's own and shares nothing with tokaLand's selections.
 */
public record Region(UUID id, String name, Map<String, String> localizedNames, String world, List<RegionBox> boxes,
                     Protection protection, LandRules land, Teleport teleport) {
    public enum Protection { OPEN, PROTECT, LOCK }

    /** A point inside the region where players can be sent. */
    public record Teleport(double x, double y, double z, float yaw, float pitch) {
        public Teleport {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
                throw new IllegalArgumentException("teleport must be finite");
        }
    }

    public Region {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
        boxes = List.copyOf(Objects.requireNonNull(boxes, "boxes"));
        if (boxes.isEmpty()) throw new IllegalArgumentException("a region needs at least one box");
        localizedNames = localizedNames == null ? Map.of() : Map.copyOf(localizedNames);
        protection = protection == null ? Protection.OPEN : protection;
        land = land == null ? LandRules.closed() : land;
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName) && boxes.stream().anyMatch(box -> box.contains(x, y, z));
    }

    public boolean overlaps(Region other) {
        return world.equals(other.world) && boxes.stream().anyMatch(a -> other.boxes.stream().anyMatch(a::overlaps));
    }

    public String displayName(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) return name;
        return localizedNames.getOrDefault(languageTag, localizedNames.getOrDefault(languageTag.split("[-_]")[0], name));
    }
}
