package dev.toka.regions.link;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import dev.toka.regions.Main;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Gives tokaDisplay the tag of the region a player is affiliated with, for the name tag. The affiliation is read from the
 * profile, so it works for players who are offline too. Reflective: neither plugin links the other.
 */
public final class DisplayLink {
    private DisplayLink() {}

    /** Registers the "affiliation" part with tokaDisplay if it is running; safe to call again. */
    public static void register(Main regions) {
        Plugin display = regions.getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display == null || !display.isEnabled()) return;
        try {
            Class<?> api = Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader());
            BiFunction<UUID, Player, String> part = (id, online) -> regions.profiles().affiliation(id)
                    .flatMap(regions.regions()::get).map(region -> region.tag()).orElse("");
            api.getMethod("registerNameTagPart", String.class, String.class, BiFunction.class).invoke(null, regions.getName(), "affiliation", part);
            // the sidebar line "region": where the player stands
            java.util.function.Function<Player, String> line = player -> {
                var here = regions.regions().at(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ());
                String language = regions.getConfig().getString("language", "zh-TW");
                return here.map(region -> regions.text(player, "sidebar.region", region.coloredName(language)))
                        .orElseGet(() -> regions.text(player, "sidebar.outside", player.getLevel().getName()));
            };
            api.getMethod("registerSection", String.class, String.class, java.util.function.Function.class).invoke(null, regions.getName(), "region", line);
        } catch (ReflectiveOperationException | LinkageError older) {
            regions.warn("log.displayOld");
        }
    }

    /** Removes the name tag part, so a reload does not leave one that reads a dead plugin. */
    public static void unregister(Main regions) {
        Plugin display = regions.getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display == null || !display.isEnabled()) return;
        try {
            Class<?> api = Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader());
            api.getMethod("unregisterNameTagParts", String.class).invoke(null, regions.getName());
            api.getMethod("unregisterSections", String.class).invoke(null, regions.getName());
        } catch (ReflectiveOperationException | LinkageError older) {
            // An older tokaDisplay has nothing to remove.
        }
    }
}
