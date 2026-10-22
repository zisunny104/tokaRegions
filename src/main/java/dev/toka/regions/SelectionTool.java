package dev.toka.regions;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Administrators pick two corners with a configured item: break a block for the first, tap one for the second. Nothing is saved here. */
public final class SelectionTool implements Listener {
    public record Point(String world, int x, int y, int z) {}
    public record Corners(Point first, Point second) {
        public boolean complete() { return first != null && second != null && first.world().equals(second.world()); }
    }

    private final Main plugin;
    private final int itemId;
    private final int itemDamage;
    private final Map<UUID, Corners> corners = new HashMap<>();

    public SelectionTool(Main plugin, Item item) {
        this.plugin = plugin;
        this.itemId = item.getId();
        this.itemDamage = item.getDamage();
    }

    private boolean holds(Player player, Item item) {
        return player.hasPermission("tokaregions.admin") && item != null && item.getId() == itemId && item.getDamage() == itemDamage;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (!holds(event.getPlayer(), event.getItem())) return;
        event.setCancelled();
        var block = event.getBlock();
        set(event.getPlayer(), true, new Point(block.getLevel().getFolderName(), block.getFloorX(), block.getFloorY(), block.getFloorZ()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || !holds(event.getPlayer(), event.getItem())) return;
        event.setCancelled();
        var block = event.getBlock();
        set(event.getPlayer(), false, new Point(block.getLevel().getFolderName(), block.getFloorX(), block.getFloorY(), block.getFloorZ()));
    }

    private void set(Player player, boolean first, Point point) {
        Corners old = corners.getOrDefault(player.getUniqueId(), new Corners(null, null));
        Corners next = first ? new Corners(point, old.second()) : new Corners(old.first(), point);
        corners.put(player.getUniqueId(), next);
        player.sendMessage(plugin.text(player, first ? "selection.first" : "selection.second", point.world(), point.x(), point.y(), point.z()));
    }

    public Optional<Corners> get(UUID player) { return Optional.ofNullable(corners.get(player)).filter(Corners::complete); }

    public void forget(UUID player) { corners.remove(player); }

    @EventHandler public void onQuit(PlayerQuitEvent event) { corners.remove(event.getPlayer().getUniqueId()); }
}
