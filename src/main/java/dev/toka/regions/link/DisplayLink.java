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
        } catch (ReflectiveOperationException | LinkageError older) {
            regions.getLogger().warning("tokaDisplay 不支援名稱標籤片段（需要 0.4.0 以上）；歸屬標籤不會顯示。");
        }
    }
}
