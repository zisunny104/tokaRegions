package dev.toka.regions;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.block.ItemFrameDropItemEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.level.Position;
import dev.toka.regions.link.LandLink;
import dev.toka.regions.policy.RegionAccess;

/**
 * Enforces each region's protection mode on its own, without tokaProtect. If anything goes wrong the action is allowed and
 * the error is logged at most once a minute, so a fault here can never lock players out of the world.
 */
public final class RegionGuard implements Listener {
    private final Main plugin;
    private final LandLink land;
    private long lastReport;

    public RegionGuard(Main plugin, LandLink land) {
        this.plugin = plugin;
        this.land = land;
    }

    private boolean denies(Player player, Position at) {
        if (at.getLevel() == null) return false;
        try {
            String world = at.getLevel().getFolderName();
            int x = at.getFloorX(), y = at.getFloorY(), z = at.getFloorZ();
            var region = plugin.regions().at(world, x, y, z);
            if (region.isEmpty()) return false;
            var mode = region.get().protection();
            boolean administrator = player.isOp() || player.hasPermission("tokaregions.admin") || player.hasPermission("toka.identity.admin");
            // Only ask tokaLand when the answer can matter.
            Boolean claimed = mode == dev.toka.regions.model.Region.Protection.PROTECT && !administrator ? land.claimed(world, x, y, z) : null;
            Boolean owns = mode == dev.toka.regions.model.Region.Protection.PROTECT && !administrator && !Boolean.TRUE.equals(claimed) ? land.ownsAny(player.getUniqueId()) : null;
            return RegionAccess.denies(mode, administrator, claimed, owns);
        } catch (RuntimeException | LinkageError error) {
            long now = System.currentTimeMillis();
            if (now - lastReport > 60_000) {
                lastReport = now;
                plugin.warn("log.guardError", error);
            }
            return false;
        }
    }

    private void refuse(Player player, cn.nukkit.event.Cancellable event) {
        event.setCancelled();
        player.sendMessage(plugin.text(player, "guard.denied"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (denies(event.getPlayer(), event.getBlock())) refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (denies(event.getPlayer(), event.getBlock())) refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        var action = event.getAction();
        if ((action == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK || action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)
                && denies(event.getPlayer(), event.getBlock())) refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrame(ItemFrameDropItemEvent event) {
        Position at = event.getItemFrame() != null ? event.getItemFrame() : event.getBlock();
        if (denies(event.getPlayer(), at)) refuse(event.getPlayer(), event);
    }
}
