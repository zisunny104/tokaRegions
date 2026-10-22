package dev.toka.regions.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.data.CommandParameter;
import dev.toka.regions.Main;
import dev.toka.regions.SelectionTool;
import dev.toka.regions.model.*;
import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;

/** /region: lookups for everyone, upkeep for administrators. Region data is small, so edits save in place on the server thread. */
public final class RegionCommand extends Command {
    private static final Set<String> RESERVED = Set.of("list", "info", "set", "add", "del", "rename", "settp", "mode", "land", "tax", "affil", "adjust", "treasury", "help");
    private final Main plugin;

    public RegionCommand(Main plugin) {
        super("region", "區域管理", "/region help");
        this.plugin = plugin;
        setPermission("tokaregions.use;tokaregions.admin");
        getCommandParameters().clear();
        addCommandParameters("read", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"help", "list", "info"}), CommandParameter.newType("name", true, CommandParamType.STRING)});
        addCommandParameters("shape", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"set", "add"}), CommandParameter.newType("name", CommandParamType.STRING),
                CommandParameter.newEnum("height", true, new String[]{"full"})});
        addCommandParameters("edit", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"del", "rename", "settp", "mode", "land", "tax", "treasury"}),
                CommandParameter.newType("name", CommandParamType.STRING), CommandParameter.newType("args", true, CommandParamType.RAWTEXT)});
        addCommandParameters("people", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"affil", "adjust"}),
                CommandParameter.newType("player", CommandParamType.STRING), CommandParameter.newType("args", true, CommandParamType.RAWTEXT)});
    }

    private void send(CommandSender sender, String key, Object... args) { sender.sendMessage(plugin.text(sender, key, args)); }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { send(sender, "cmd.denied"); return true; }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "help" -> help(sender);
                case "list" -> list(sender, args);
                case "info" -> info(sender, args);
                default -> {
                    if (!sender.hasPermission("tokaregions.admin")) { send(sender, "cmd.denied"); return true; }
                    admin(sender, action, args);
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException failure) {
            send(sender, "cmd.failed", failure.getMessage());
        }
        return true;
    }

    private void help(CommandSender sender) {
        send(sender, "help.read");
        if (sender.hasPermission("tokaregions.admin")) for (String key : List.of("help.shape", "help.edit", "help.people")) send(sender, key);
    }

    private void list(CommandSender sender, String[] args) {
        List<Region> all = plugin.regions().all().stream().sorted(Comparator.comparing(Region::name, String.CASE_INSENSITIVE_ORDER)).toList();
        int page = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int pages = Math.max(1, (all.size() + 9) / 10);
        if (page < 1 || page > pages) throw new IllegalArgumentException("PAGE");
        send(sender, "list.header", page, pages, all.size());
        all.subList((page - 1) * 10, Math.min(all.size(), page * 10)).forEach(r -> send(sender, "list.row", r.name(), r.world(), r.protection()));
    }

    private void info(CommandSender sender, String[] args) {
        Region region = args.length > 1 ? find(args[1]) : here(sender);
        var land = region.land();
        send(sender, "info", region.name(), region.world(), region.protection(), land.purchaseAllowed(), land.flatPricing(), land.affiliatedOffer(), land.taxTiers().size());
    }

    private Region here(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("NAME_REQUIRED");
        return plugin.regions().at(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ())
                .orElseThrow(() -> new IllegalArgumentException("NOT_IN_REGION"));
    }

    private Region find(String name) { return plugin.regions().byName(name).orElseThrow(() -> new IllegalArgumentException("REGION_NOT_FOUND")); }

    private void admin(CommandSender sender, String action, String[] args) throws IOException {
        switch (action) {
            case "set", "add" -> shape(sender, action.equals("add"), args);
            case "del" -> {
                Region region = find(need(args, 1));
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) { send(sender, "del.confirm", region.name()); return; }
                save(plugin.regions().all().stream().filter(r -> !r.id().equals(region.id())).toList());
                send(sender, "done");
            }
            case "rename" -> change(sender, need(args, 1), r -> copy(r, need(args, 2), r.protection(), r.land(), r.teleport(), r.selections()));
            case "settp" -> {
                if (!(sender instanceof Player p)) throw new IllegalArgumentException("PLAYER_ONLY");
                change(sender, need(args, 1), r -> {
                    if (!r.contains(p.getLevel().getFolderName(), p.getFloorX(), p.getFloorY(), p.getFloorZ())) throw new IllegalArgumentException("TELEPORT_OUTSIDE_REGION");
                    return copy(r, r.name(), r.protection(), r.land(), new Region.Teleport(p.x, p.y, p.z, (float) p.yaw, (float) p.pitch), r.selections());
                });
            }
            case "mode" -> change(sender, need(args, 1), r -> copy(r, r.name(), Region.Protection.valueOf(need(args, 2).toUpperCase(Locale.ROOT)), r.land(), r.teleport(), r.selections()));
            case "land" -> change(sender, need(args, 1), r -> copy(r, r.name(), r.protection(), landRules(r.land(), need(args, 2), need(args, 3)), r.teleport(), r.selections()));
            case "tax" -> change(sender, need(args, 1), r -> copy(r, r.name(), r.protection(), taxRules(r.land(), args), r.teleport(), r.selections()));
            case "treasury" -> treasury(sender, args);
            case "affil" -> affiliate(sender, args);
            case "adjust" -> adjust(sender, args);
            default -> send(sender, "cmd.usage");
        }
    }

    private static String need(String[] args, int index) {
        if (args.length <= index) throw new IllegalArgumentException("USAGE");
        return args[index];
    }

    private static Region copy(Region r, String name, Region.Protection protection, LandRules land, Region.Teleport tp, List<RegionSelection> parts) {
        return new Region(r.id(), name, r.localizedNames(), r.world(), parts, protection, land, tp, r.treasury());
    }

    private static LandRules landRules(LandRules old, String setting, String value) {
        return switch (setting.toLowerCase(Locale.ROOT)) {
            case "buy" -> new LandRules(on(value), old.flatPricing(), old.affiliatedOffer(), old.taxTiers());
            case "flat" -> new LandRules(old.purchaseAllowed(), on(value), old.affiliatedOffer(), old.taxTiers());
            case "offer" -> new LandRules(old.purchaseAllowed(), old.flatPricing(), Double.parseDouble(value), old.taxTiers());
            default -> throw new IllegalArgumentException("USAGE");
        };
    }

    private static boolean on(String value) {
        if (value.equalsIgnoreCase("on")) return true;
        if (value.equalsIgnoreCase("off")) return false;
        throw new IllegalArgumentException("USAGE");
    }

    private static LandRules taxRules(LandRules old, String[] args) {
        String sub = need(args, 2).toLowerCase(Locale.ROOT);
        if (sub.equals("clear")) return new LandRules(old.purchaseAllowed(), old.flatPricing(), old.affiliatedOffer(), List.of());
        if (!sub.equals("add")) throw new IllegalArgumentException("USAGE");
        List<TaxTier> tiers = new ArrayList<>(old.taxTiers());
        int moreThan = Integer.parseInt(need(args, 3));
        tiers.removeIf(t -> t.moreThan() == moreThan);
        tiers.add(new TaxTier(moreThan, Double.parseDouble(need(args, 4)), args.length > 5 ? Double.valueOf(args[5]) : null));
        return new LandRules(old.purchaseAllowed(), old.flatPricing(), old.affiliatedOffer(), tiers);
    }

    private void shape(CommandSender sender, boolean add, String[] args) throws IOException {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("PLAYER_ONLY");
        String name = need(args, 1);
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("RESERVED_NAME");
        SelectionTool.Corners corners = plugin.selection().get(player.getUniqueId()).orElseThrow(() -> new IllegalArgumentException("SELECTION_REQUIRED"));
        boolean full = args.length > 2 && args[2].equalsIgnoreCase("full");
        int minY = full ? plugin.getConfig().getInt("selection.full-min-y", 0) : Math.min(corners.first().y(), corners.second().y());
        int maxY = full ? plugin.getConfig().getInt("selection.full-max-y", 255) : Math.max(corners.first().y(), corners.second().y());
        var part = new RegionSelection(corners.first().x(), minY, corners.first().z(), corners.second().x(), maxY, corners.second().z());
        List<Region> next = new ArrayList<>(plugin.regions().all());
        if (add) {
            Region existing = find(name);
            if (!existing.world().equals(corners.first().world())) throw new IllegalArgumentException("WORLD_MISMATCH");
            List<RegionSelection> parts = new ArrayList<>(existing.selections());
            parts.add(part);
            next.replaceAll(r -> r.id().equals(existing.id()) ? copy(r, r.name(), r.protection(), r.land(), r.teleport(), parts) : r);
        } else {
            next.add(new Region(UUID.randomUUID(), name, Map.of(), corners.first().world(), List.of(part), Region.Protection.OPEN, LandRules.closed(), null));
        }
        save(next);
        plugin.selection().forget(player.getUniqueId());
        send(sender, "done");
    }

    /** treasury <region> none|own|main, treasury <region> balance, treasury <region> pay <player> <amount>, treasury <region> move <to> <amount>. */
    private void treasury(CommandSender sender, String[] args) throws IOException {
        Region region = find(need(args, 1));
        String sub = need(args, 2).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "none", "own", "main" -> {
                var mode = Region.Treasury.valueOf(sub.toUpperCase(Locale.ROOT));
                change(sender, region.name(), r -> new Region(r.id(), r.name(), r.localizedNames(), r.world(), r.selections(), r.protection(), r.land(), r.teleport(), mode));
                plugin.ensureAccounts();
            }
            case "balance" -> {
                var balance = plugin.economy().balance(region.id());
                send(sender, balance.isPresent() ? "treasury.balance" : "treasury.noAccount", region.name(), balance.orElse(0L));
            }
            case "pay" -> {
                UUID who = player(need(args, 3));
                transfer(sender, region.id(), who, Long.parseLong(need(args, 4)));
            }
            case "move" -> {
                Region to = find(need(args, 3));
                transfer(sender, region.id(), to.id(), Long.parseLong(need(args, 4)));
            }
            default -> throw new IllegalArgumentException("USAGE");
        }
    }

    private void transfer(CommandSender sender, UUID from, UUID to, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("AMOUNT");
        plugin.getLogger().info("國庫轉帳：" + sender.getName() + " " + from + " -> " + to + " " + amount);
        // A fresh operation id per command run: pressing enter twice moves money twice, but a retry of one run cannot.
        plugin.economy().transfer(from, to, amount, "treasury:" + UUID.randomUUID()).whenComplete((status, error) ->
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                    if (error == null && status.equals("OK")) send(sender, "done");
                    else send(sender, "cmd.failed", error == null ? status : String.valueOf(error.getMessage()));
                }));
    }

    private void change(CommandSender sender, String name, UnaryOperator<Region> edit) throws IOException {
        Region old = find(name);
        List<Region> next = new ArrayList<>(plugin.regions().all());
        next.replaceAll(r -> r.id().equals(old.id()) ? edit.apply(r) : r);
        save(next);
        send(sender, "done");
    }

    private void save(Collection<Region> next) throws IOException { plugin.saveRegions(next); }

    /** One exact UUID or a name that matches exactly one profile; names are never guessed. */
    private UUID player(String query) {
        try { return UUID.fromString(query); } catch (IllegalArgumentException notAnId) { /* look the name up */ }
        for (Player online : plugin.getServer().getOnlinePlayers().values()) if (online.getName().equalsIgnoreCase(query)) return online.getUniqueId();
        Set<UUID> found = plugin.profiles().resolveName(query);
        if (found.size() != 1) throw new IllegalArgumentException(found.isEmpty() ? "PLAYER_UNKNOWN" : "PLAYER_AMBIGUOUS");
        return found.iterator().next();
    }

    private void affiliate(CommandSender sender, String[] args) {
        UUID who = player(need(args, 1));
        String target = need(args, 2);
        UUID region = target.equalsIgnoreCase("none") ? null : find(target).id();
        plugin.profiles().setAffiliation(who, region).whenComplete((ok, error) -> plugin.getServer().getScheduler().scheduleTask(plugin, () ->
                send(sender, error == null ? "done" : "cmd.failed", error == null ? "" : String.valueOf(error.getCause() != null ? error.getCause().getMessage() : error.getMessage()))));
    }

    private void adjust(CommandSender sender, String[] args) throws IOException {
        UUID who = player(need(args, 1));
        List<TaxAdjustment> all = new ArrayList<>(plugin.adjustments());
        if (need(args, 2).equalsIgnoreCase("clear")) {
            all.removeIf(a -> a.player().equals(who));
        } else {
            if (args.length < 4) throw new IllegalArgumentException("USAGE");
            UUID by = sender instanceof Player p ? p.getUniqueId() : null;
            all.add(new TaxAdjustment(who, Double.parseDouble(args[2]), String.join(" ", Arrays.copyOfRange(args, 3, args.length)), by, System.currentTimeMillis(), null));
        }
        plugin.saveAdjustments(all);
        send(sender, "done");
    }
}
