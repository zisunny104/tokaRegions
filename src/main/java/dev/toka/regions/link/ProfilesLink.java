package dev.toka.regions.link;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Reads and writes a player's region affiliation through tokaProfiles, reflectively so neither plugin links the other. */
public final class ProfilesLink {
    private final Server server;

    public ProfilesLink(Server server) { this.server = server; }

    private Object repository() throws ReflectiveOperationException {
        Plugin plugin = server.getPluginManager().getPlugin("tokaProfiles");
        if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("PROFILES_UNAVAILABLE");
        return plugin.getClass().getMethod("profiles").invoke(plugin);
    }

    /** The region id the player is affiliated with; empty when unset or when Profiles is not available. */
    public Optional<UUID> affiliation(UUID player) {
        try {
            Object repository = repository();
            Object found = repository.getClass().getMethod("find", UUID.class).invoke(repository, player);
            if (!(found instanceof Optional<?> profile) || profile.isEmpty()) return Optional.empty();
            String id = (String) profile.get().getClass().getMethod("affiliation").invoke(profile.get());
            return id == null || id.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(id));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /** Whether the player is new and still has to read the welcome pages (a profile made at the first login). */
    public boolean isNew(UUID player) {
        try {
            Object repository = repository();
            Object found = repository.getClass().getMethod("find", UUID.class).invoke(repository, player);
            if (!(found instanceof Optional<?> profile) || profile.isEmpty()) return false;
            Object preferences = profile.get().getClass().getMethod("preferences").invoke(profile.get());
            return preferences instanceof java.util.Map<?, ?> map && "pending".equals(map.get("welcome"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** Sets the affiliation, or clears it when region is null. Fails if the player has no profile. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> setAffiliation(UUID player, UUID region) {
        try {
            Object repository = repository();
            return (CompletableFuture<Void>) repository.getClass().getMethod("updateAffiliation", UUID.class, String.class)
                    .invoke(repository, player, region == null ? "" : region.toString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return CompletableFuture.failedFuture(new IllegalStateException("PROFILES_UNAVAILABLE", unavailable));
        }
    }

    /** Players whose name or alias matches; used to turn a typed name into a UUID without guessing. */
    public Set<UUID> resolveName(String name) {
        try {
            Object repository = repository();
            Object found = repository.getClass().getMethod("resolveName", String.class).invoke(repository, name);
            if (!(found instanceof Set<?> ids)) return Set.of();
            Set<UUID> result = new java.util.HashSet<>();
            ids.forEach(id -> result.add((UUID) id));
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Set.of();
        }
    }
}
