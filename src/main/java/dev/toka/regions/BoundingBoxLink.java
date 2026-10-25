package dev.toka.regions;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import dev.toka.regions.model.RegionSelection;

/**
 * Draws the box of a region through BoundingBoxAPI when that plugin is there. The plugin is reached by reflection, so tokaRegions does not
 * depend on it; without it nothing is drawn.
 */
final class BoundingBoxLink {
    private BoundingBoxLink() { }

    private static Plugin api(Plugin from) {
        Plugin plugin = from.getServer().getPluginManager().getPlugin("BoundingBoxAPI");
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    static boolean available(Plugin from) { return api(from) != null; }

    static void show(Plugin from, Player player, String world, RegionSelection part, String tag) {
        Plugin api = api(from);
        if (api == null || player.getLevel() == null || !player.getLevel().getFolderName().equals(world)) return;
        try {
            api.getClass().getMethod("showBox", Player.class, cn.nukkit.level.Level.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, String.class, int.class)
                    .invoke(api, player, player.getLevel(), part.minX(), part.minY(), part.minZ(), part.maxX(), part.maxY(), part.maxZ(), tag, 0);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            from.getLogger().debug("BoundingBoxAPI cannot be used: " + unavailable);
        }
    }

    static void clear(Plugin from, Player player, String tag) {
        Plugin api = api(from);
        if (api == null) return;
        try {
            api.getClass().getMethod("clearBox", Player.class, String.class).invoke(api, player, tag);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            from.getLogger().debug("BoundingBoxAPI cannot be used: " + unavailable);
        }
    }
}
