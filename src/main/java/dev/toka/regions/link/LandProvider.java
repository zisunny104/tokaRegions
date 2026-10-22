package dev.toka.regions.link;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.toka.regions.Main;
import dev.toka.regions.model.Region;
import dev.toka.regions.policy.PurchasePolicy;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Answers tokaLand's two questions about buying land. SELECT: may this point be selected and is the area priced flat.
 * ADJUST: the discount and tax factors for this buyer. A denial is a reply with status DENIED, which tokaLand treats as a refusal.
 */
public final class LandProvider implements BiFunction<Player, String, String> {
    private final Main plugin;

    public LandProvider(Main plugin) { this.plugin = plugin; }

    /** Hands this provider to tokaLand if it is installed; returns whether it was accepted. */
    public static boolean register(Main plugin) {
        Server server = plugin.getServer();
        Plugin land = server.getPluginManager().getPlugin("tokaLand");
        if (land == null || !land.isEnabled()) return false;
        try {
            land.getClass().getMethod("registerPurchasePolicy", String.class, BiFunction.class).invoke(null, plugin.getName(), new LandProvider(plugin));
            return true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            plugin.getLogger().warning("無法向 tokaLand 登錄區域規則（可能已有其他提供者）：" + cause.getMessage());
            return false;
        }
    }

    @Override public String apply(Player player, String request) {
        JsonObject json = JsonParser.parseString(request).getAsJsonObject();
        JsonObject reply = new JsonObject();
        switch (json.get("action").getAsString()) {
            case "SELECT" -> {
                Optional<Region> region = regionAt(json.getAsJsonObject("point"));
                var selection = PurchasePolicy.select(region);
                if (!selection.allowed()) return denied();
                reply.addProperty("status", "OK");
                reply.addProperty("ignoreHeight", selection.flat());
            }
            case "ADJUST" -> {
                Optional<Region> first = regionAt(json.getAsJsonObject("first"));
                Optional<Region> second = regionAt(json.getAsJsonObject("second"));
                if (!PurchasePolicy.select(first).allowed() || first.isEmpty() || second.isEmpty() || !first.get().id().equals(second.get().id())) return denied();
                Region region = first.get();
                boolean affiliated = plugin.profiles().affiliation(player.getUniqueId()).filter(region.id()::equals).isPresent();
                var buyer = new PurchasePolicy.Buyer(json.get("ownedCount").getAsInt(), affiliated, player.hasPermission("tokaregions.tax-exempt"),
                        player.hasPermission("tokaregions.tax-reduced"), plugin.adjustmentRate(player.getUniqueId(), System.currentTimeMillis()));
                var adjustment = PurchasePolicy.adjust(region, json.get("ignoreHeight").getAsBoolean(), buyer);
                reply.addProperty("status", "OK");
                reply.addProperty("offer", adjustment.offer());
                reply.addProperty("tax", adjustment.tax());
                plugin.regions().accountFor(region).ifPresent(account -> reply.addProperty("beneficiary", account.toString()));
            }
            default -> throw new IllegalArgumentException("Unknown request");
        }
        return reply.toString();
    }

    private Optional<Region> regionAt(JsonObject point) {
        return plugin.regions().at(point.get("world").getAsString(), point.get("x").getAsInt(), point.get("y").getAsInt(), point.get("z").getAsInt());
    }

    private static String denied() {
        JsonObject reply = new JsonObject();
        reply.addProperty("status", "DENIED");
        return reply.toString();
    }
}
