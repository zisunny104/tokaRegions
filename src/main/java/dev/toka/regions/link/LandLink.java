package dev.toka.regions.link;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.UUID;

/** What tokaRegions asks tokaLand, reflectively. null always means "cannot tell" (not installed, not ready, or failed). */
public final class LandLink {
    private final Server server;

    public LandLink(Server server) { this.server = server; }

    private Method method(String name, Class<?>... parameters) {
        Plugin land = server.getPluginManager().getPlugin("tokaLand");
        if (land == null || !land.isEnabled()) return null;
        try { return Class.forName("dev.toka.land.Main", true, land.getClass().getClassLoader()).getMethod(name, parameters); }
        catch (ReflectiveOperationException | LinkageError missing) { return null; }
    }

    /** Whether any claim covers the block. */
    public Boolean claimed(String world, int x, int y, int z) {
        Method lookup = method("claimsAtJson", String.class, int.class, int.class, int.class);
        if (lookup == null) return null;
        try {
            String json = (String) lookup.invoke(null, world, x, y, z);
            return json == null ? null : !json.equals("[]");
        } catch (ReflectiveOperationException | LinkageError failed) { return null; }
    }

    /** Whether the player owns at least one claim. */
    public Boolean ownsAny(UUID player) {
        Method lookup = method("ownsAnyClaim", UUID.class);
        if (lookup == null) return null;
        try { return (Boolean) lookup.invoke(null, player); }
        catch (ReflectiveOperationException | LinkageError failed) { return null; }
    }
}
