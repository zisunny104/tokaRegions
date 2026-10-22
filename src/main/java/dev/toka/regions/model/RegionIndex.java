package dev.toka.regions.model;

import java.util.*;

/** All regions, immutable. Regions may not overlap, so a point is in at most one region. */
public final class RegionIndex {
    private final Map<UUID, Region> byId;
    private final Map<String, List<Region>> byWorld = new HashMap<>();
    private final Optional<Region> main;

    public RegionIndex(Collection<Region> regions) {
        Map<UUID, Region> ids = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Region region : regions) {
            if (ids.putIfAbsent(region.id(), region) != null) throw new IllegalArgumentException("duplicate region ID: " + region.id());
            if (!names.add(region.name().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("duplicate region name: " + region.name());
        }
        List<Region> all = List.copyOf(ids.values());
        if (all.stream().filter(r -> r.treasury() == Region.Treasury.MAIN).count() > 1) throw new IllegalArgumentException("only one region can hold the main account");
        for (int i = 0; i < all.size(); i++)
            for (int j = i + 1; j < all.size(); j++)
                if (all.get(i).overlaps(all.get(j))) throw new IllegalArgumentException("regions overlap: " + all.get(i).name() + ", " + all.get(j).name());
        byId = Map.copyOf(ids);
        main = all.stream().filter(r -> r.treasury() == Region.Treasury.MAIN).findFirst();
        all.forEach(region -> byWorld.computeIfAbsent(region.world(), w -> new ArrayList<>()).add(region));
    }

    public Optional<Region> at(String world, int x, int y, int z) {
        return byWorld.getOrDefault(world, List.of()).stream().filter(r -> r.contains(world, x, y, z)).findFirst();
    }

    public Optional<Region> get(UUID id) { return Optional.ofNullable(byId.get(id)); }

    public Optional<Region> byName(String name) {
        return byId.values().stream().filter(r -> r.name().equalsIgnoreCase(name)).findFirst();
    }

    public Optional<Region> mainAccountRegion() { return main; }

    /** The account that receives payments for land in the region: its own, else the main one, else none. */
    public Optional<UUID> accountFor(Region region) {
        if (region.hasAccount()) return Optional.of(region.id());
        return mainAccountRegion().map(Region::id);
    }

    public Collection<Region> all() { return byId.values(); }
}
