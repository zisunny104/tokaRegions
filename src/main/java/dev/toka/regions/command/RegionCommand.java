package dev.toka.regions.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandEnum;
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
    private static final Set<String> RESERVED = Set.of("list", "info", "set", "add", "del", "rename", "settp", "mode", "land", "tax", "affil", "adjust", "treasury", "tag", "label", "color", "help", "menu", "reload", "household");
    private final Main plugin;
    private final HelpPages help;

    public RegionCommand(Main plugin) {
        super("region", "區域管理", "/region help");
        this.plugin = plugin;
        help = new HelpPages("region", List.of(
                new HelpPages.Section("read", null, List.of("list", "info", "menu", "household")),
                new HelpPages.Section("shape", "tokaregions.admin", List.of("set", "setWorld", "add")),
                new HelpPages.Section("edit", "tokaregions.admin", List.of("del", "rename", "label", "settp", "mode", "land", "landOffer", "tax", "taxClear", "reload")),
                new HelpPages.Section("treasury", "tokaregions.admin", List.of("treasuryMode", "treasuryBalance", "treasuryPay", "treasuryMove")),
                new HelpPages.Section("people", "tokaregions.admin", List.of("tag", "color", "affil", "adjust", "adjustClear"))), plugin::text);
        setPermission("tokaregions.use;tokaregions.admin");
        refreshNames();
    }

    private static final String[] COLORS = {"clear", "&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7", "&8", "&9", "&a", "&b", "&c", "&d", "&e", "&f"};

    /** A region name argument: the names that exist now as a list to pick from; a plain text when there are none yet. */
    private CommandParameter regionName(String id, boolean optional, String... extra) {
        // the client refuses a list with a capital letter in it (and crashes), so names are offered in lower case; the lookup ignores case
        List<String> names = new ArrayList<>(plugin.regions().all().stream().map(r -> r.name().toLowerCase(java.util.Locale.ROOT)).distinct().sorted().toList());
        names.addAll(List.of(extra));
        if (names.isEmpty()) return CommandParameter.newType(id, optional, CommandParamType.STRING);
        return CommandParameter.newEnum(id, optional, new CommandEnum("TokaRegionsName" + extra.length, names));
    }

    private static CommandParameter word(String id, boolean optional, String enumName, String... values) {
        return CommandParameter.newEnum(id, optional, new CommandEnum(enumName, values));
    }

    /** Builds the Tab overloads again; the region names are read from the regions that exist now. */
    public void refreshNames() {
        getCommandParameters().clear();
        addCommandParameters("list", new CommandParameter[]{word("action", false, "TokaRegionsList", "list"), CommandParameter.newType("page", true, CommandParamType.INT)});
        addCommandParameters("info", new CommandParameter[]{word("action", false, "TokaRegionsInfo", "info"), regionName("name", true)});
        addCommandParameters("menu", new CommandParameter[]{word("action", false, "TokaRegionsMenu", "menu")});
        addCommandParameters("reload", new CommandParameter[]{word("action", false, "TokaRegionsReload", "reload")});
        addCommandParameters("household", new CommandParameter[]{word("action", false, "TokaRegionsHousehold", "household"), regionName("region", true)});
        addCommandParameters("help", new CommandParameter[]{word("action", false, "TokaRegionsHelp", "help"),
                CommandParameter.newType("page", true, CommandParamType.INT)});
        addCommandParameters("shape", new CommandParameter[]{word("action", false, "TokaRegionsShape", "set", "add"), CommandParameter.newType("name", CommandParamType.STRING),
                word("height", true, "TokaRegionsFull", "full")});
        addCommandParameters("shapeWorld", new CommandParameter[]{word("action", false, "TokaRegionsShapeWorld", "set"), CommandParameter.newType("name", CommandParamType.STRING),
                word("world", false, "TokaRegionsWorld", "world"), CommandParameter.newType("level", true, CommandParamType.STRING)});
        // The region the player stands in is the default for the commands that take a region name, so they are one line of free text
        // after the word (/region help describes them); only the words used most keep a line of their own.
        addCommandParameters("mode", new CommandParameter[]{word("action", false, "TokaRegionsMode", "mode"), regionName("name", false),
                word("value", false, "TokaRegionsModeValue", "open", "protect", "lock")});
        addCommandParameters("modeHere", new CommandParameter[]{word("action", false, "TokaRegionsModeHere", "mode"),
                word("value", false, "TokaRegionsModeValueHere", "open", "protect", "lock")});
        addCommandParameters("admin", new CommandParameter[]{word("action", false, "TokaRegionsAction", "del", "rename", "label", "show", "hide", "settp", "land", "tax", "treasury", "tag", "color", "affil", "adjust"),
                CommandParameter.newType("arguments", true, CommandParamType.RAWTEXT)});
    }

    private void send(CommandSender sender, String key, Object... args) { sender.sendMessage(plugin.text(sender, key, args)); }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { send(sender, "cmd.denied"); return true; }
        String action = args.length == 0 ? (sender instanceof Player ? "menu" : "help") : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "help" -> help.show(sender, args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
                case "list" -> list(sender, args);
                case "info" -> info(sender, args);
                case "household" -> household(sender, args);
                case "menu" -> {
                    if (!(sender instanceof Player player)) throw new IllegalArgumentException("PLAYER_ONLY");
                    plugin.windows().open(player);
                }
                case "reload" -> {
                    if (!sender.hasPermission("tokaregions.admin")) { send(sender, "cmd.denied"); return true; }
                    plugin.reloadSettings();
                    send(sender, "reload.done");
                }
                default -> {
                    if (!sender.hasPermission("tokaregions.admin")) { send(sender, "cmd.denied"); return true; }
                    admin(sender, action, args);
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException failure) {
            send(sender, "cmd.failed", reason(sender, failure.getMessage()));
        }
        return true;
    }

    /** /region household [village]: where the player is registered, and to change it when the server lets players do so. */
    private void household(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("PLAYER_ONLY");
        String language = player.getLocale() == null ? plugin.getConfig().getString("language", "zh-TW") : player.getLocale().toLanguageTag();
        var villages = plugin.villages();
        boolean change = plugin.getConfig().getBoolean("household.player-change", false);
        if (args.length < 2) {
            String current = plugin.profiles().affiliation(player.getUniqueId()).flatMap(plugin.regions()::get).map(r -> r.coloredName(language)).orElse(plugin.text(sender, "household.none"));
            send(sender, "household.current", current);
            if (change && !villages.isEmpty()) send(sender, "household.choices", villages.stream().map(Region::name).toList());
            return;
        }
        if (!change) throw new IllegalArgumentException("HOUSEHOLD_LOCKED");
        Region village = villages.stream().filter(r -> r.name().equalsIgnoreCase(args[1])).findFirst().orElseThrow(() -> new IllegalArgumentException("NOT_A_VILLAGE"));
        plugin.profiles().setAffiliation(player.getUniqueId(), village.id()).whenComplete((ok, error) -> plugin.getServer().getScheduler().scheduleTask(plugin, () ->
                send(sender, error == null ? "household.changed" : "cmd.failed", error == null ? village.coloredName(language) : String.valueOf(error.getCause() != null ? error.getCause().getMessage() : error.getMessage()))));
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
        String shown = region.coloredName(plugin.getConfig().getString("language", "zh-TW"));
        String others = region.localizedNames().isEmpty() ? "-" : region.localizedNames().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining("、"));
        String point = region.teleport() == null ? "-" : String.format("%.0f, %.0f, %.0f", region.teleport().x(), region.teleport().y(), region.teleport().z());
        send(sender, "info", shown, region.name(), region.id(), others, region.tag().isEmpty() ? "-" : region.tag(),
                region.world(), region.protection(), region.treasury(), point, region.selections().size(),
                land.purchaseAllowed(), land.flatPricing(), land.affiliatedOffer(), land.taxTiers().size(), plugin.accountText(sender, region));
    }

    private Region here(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("NAME_REQUIRED");
        return plugin.regions().at(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ())
                .orElseThrow(() -> new IllegalArgumentException("NOT_IN_REGION"));
    }

    private Region find(String name) { return plugin.regions().byName(name).orElseThrow(() -> new IllegalArgumentException("REGION_NOT_FOUND|" + name)); }

    /** The codes the commands fail with; each has a text under err.<code>, a code may carry its detail after a bar. */
    private static final Set<String> ERROR_CODES = Set.of("PLAYER_ONLY", "WORLD_UNKNOWN", "WORLD_REQUIRED", "REGION_NOT_FOUND", "NAME_REQUIRED", "RESERVED_NAME", "SELECTION_REQUIRED", "WORLD_MISMATCH",
            "NOT_IN_REGION", "TELEPORT_OUTSIDE_REGION", "INVALID_COLOR", "TAG_TOO_LONG", "USAGE", "AMOUNT", "PAGE", "HOUSEHOLD_LOCKED", "NOT_A_VILLAGE");

    private String reason(CommandSender sender, String message) {
        if (message == null) return "?";
        if (message.startsWith("regions overlap: ")) return plugin.text(sender, "err.OVERLAP", (Object[]) message.substring("regions overlap: ".length()).split(", ", 2));
        if (message.startsWith("duplicate region name: ")) return plugin.text(sender, "err.DUPLICATE_NAME", message.substring("duplicate region name: ".length()));
        String[] parts = message.split("\\|", 2);
        if (!ERROR_CODES.contains(parts[0])) return message;
        return plugin.text(sender, "err." + parts[0], (Object[]) (parts.length > 1 ? new String[]{parts[1]} : new String[0]));
    }

    /** The commands whose first argument is a region name: without one, the region the player stands in is meant. */
    private static final Set<String> NAMED = Set.of("del", "rename", "label", "show", "hide", "settp", "mode", "land", "tax", "treasury", "tag", "color");

    private String[] withRegionHere(CommandSender sender, String action, String[] args) {
        if (!NAMED.contains(action) || !(sender instanceof Player player)) return args;
        if (args.length > 1 && plugin.regions().byName(args[1]).isPresent()) return args;
        var here = plugin.regions().at(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ());
        if (here.isEmpty()) return args;
        String[] withName = new String[args.length + 1];
        withName[0] = args[0];
        withName[1] = here.get().name();
        System.arraycopy(args, 1, withName, 2, args.length - 1);
        return withName;
    }

    private void admin(CommandSender sender, String action, String[] given) throws IOException {
        String[] args = withRegionHere(sender, action, given);
        switch (action) {
            case "show" -> {
                if (!(sender instanceof Player player)) throw new IllegalArgumentException("PLAYER_ONLY");
                plugin.showBounds(player, find(need(args, 1)));
            }
            case "hide" -> {
                if (!(sender instanceof Player player)) throw new IllegalArgumentException("PLAYER_ONLY");
                plugin.hideBounds(player, find(need(args, 1)));
            }
            case "set", "add" -> shape(sender, action.equals("add"), args);
            case "del" -> {
                Region region = find(need(args, 1));
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) { send(sender, "del.confirm", region.name()); return; }
                save(plugin.regions().all().stream().filter(r -> !r.id().equals(region.id())).toList());
                send(sender, "done.del", region.name());
            }
            case "rename" -> {
                if (RESERVED.contains(need(args, 2).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("RESERVED_NAME|" + args[2]);
                change(sender, need(args, 1), r -> copy(r, need(args, 2), r.protection(), r.land(), r.teleport(), r.selections()), "done.rename", args[1], args[2]);
            }
            case "label" -> {
                // /region label <名稱> <語言> [文字]：設定區域在該語言下顯示的名稱，不填文字就清除
                String language = need(args, 2);
                String text = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim() : "";
                change(sender, need(args, 1), r -> {
                    var names = new java.util.HashMap<>(r.localizedNames());
                    if (text.isEmpty()) names.remove(language); else names.put(language, text);
                    return new Region(r.id(), r.name(), names, r.world(), r.selections(), r.protection(), r.land(), r.teleport(), r.treasury(), r.tag(), r.color());
                }, text.isEmpty() ? "done.labelClear" : "done.label", args[1], language, text);
            }
            case "settp" -> {
                if (!(sender instanceof Player p)) throw new IllegalArgumentException("PLAYER_ONLY");
                change(sender, need(args, 1), r -> {
                    if (!r.contains(p.getLevel().getFolderName(), p.getFloorX(), p.getFloorY(), p.getFloorZ())) throw new IllegalArgumentException("TELEPORT_OUTSIDE_REGION");
                    return copy(r, r.name(), r.protection(), r.land(), new Region.Teleport(p.x, p.y, p.z, (float) p.yaw, (float) p.pitch), r.selections());
                }, "done.settp", args[1], p.getFloorX(), p.getFloorY(), p.getFloorZ());
            }
            case "mode" -> change(sender, need(args, 1), r -> copy(r, r.name(), Region.Protection.valueOf(need(args, 2).toUpperCase(Locale.ROOT)), r.land(), r.teleport(), r.selections()), "done.mode", args[1], need(args, 2).toLowerCase(Locale.ROOT));
            case "land" -> change(sender, need(args, 1), r -> copy(r, r.name(), r.protection(), landRules(r.land(), need(args, 2), need(args, 3)), r.teleport(), r.selections()));
            case "tax" -> change(sender, need(args, 1), r -> copy(r, r.name(), r.protection(), taxRules(r.land(), args), r.teleport(), r.selections()));
            case "treasury" -> treasury(sender, args);
            case "tag" -> change(sender, need(args, 1), r -> new Region(r.id(), r.name(), r.localizedNames(), r.world(), r.selections(), r.protection(), r.land(), r.teleport(), r.treasury(), tag(args), r.color()));
            case "color" -> change(sender, need(args, 1), r -> r.withColor(color(args)));
            case "affil" -> affiliate(sender, args);
            case "adjust" -> adjust(sender, args);
            default -> send(sender, "cmd.usage");
        }
    }

    /** One colour code, written with & or §; "clear" or nothing removes the colour. */
    private static String color(String[] args) {
        if (args.length < 3 || args[2].equalsIgnoreCase("clear")) return "";
        String code = args[2].replace('&', '§');
        if (!code.matches("§[0-9a-fk-or]")) throw new IllegalArgumentException("INVALID_COLOR");
        return code;
    }

    /** Everything after the region name, joined; "clear" or nothing removes the tag. */
    private static String tag(String[] args) {
        if (args.length < 3 || args[2].equalsIgnoreCase("clear")) return "";
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).replace('&', '§');
        if (text.length() > 64) throw new IllegalArgumentException("TAG_TOO_LONG");
        return text;
    }

    private static String need(String[] args, int index) {
        if (args.length <= index) throw new IllegalArgumentException("USAGE");
        return args[index];
    }

    private static Region copy(Region r, String name, Region.Protection protection, LandRules land, Region.Teleport tp, List<RegionSelection> parts) {
        return new Region(r.id(), name, r.localizedNames(), r.world(), parts, protection, land, tp, r.treasury(), r.tag(), r.color());
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

    /** /region set <名稱> world [世界]：整個世界一個區域，範圍用 selection.world-radius，高度同 full；不填世界就是腳下的世界。 */
    private void worldRegion(CommandSender sender, String[] args) throws IOException {
        String name = need(args, 1);
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("RESERVED_NAME|" + name);
        cn.nukkit.level.Level level = args.length > 3 ? plugin.getServer().getLevelByName(args[3]) : sender instanceof Player p ? p.getLevel() : null;
        if (level == null) throw new IllegalArgumentException(args.length > 3 ? "WORLD_UNKNOWN|" + args[3] : "WORLD_REQUIRED");
        int radius = plugin.getConfig().getInt("selection.world-radius", 15000);
        var part = new RegionSelection(-radius, plugin.getConfig().getInt("selection.full-min-y", 0), -radius, radius, plugin.getConfig().getInt("selection.full-max-y", 255), radius);
        List<Region> next = new ArrayList<>(plugin.regions().all());
        next.add(new Region(UUID.randomUUID(), name, Map.of(), level.getFolderName(), List.of(part), Region.Protection.OPEN, LandRules.closed(), null));
        save(next);
        send(sender, "done.world", name, level.getFolderName(), radius, part.minY(), part.maxY());
    }

    private void shape(CommandSender sender, boolean add, String[] args) throws IOException {
        if (!add && args.length > 2 && args[2].equalsIgnoreCase("world")) { worldRegion(sender, args); return; }
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("PLAYER_ONLY");
        String name = need(args, 1);
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("RESERVED_NAME|" + name);
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
        send(sender, add ? "done.add" : "done.set", name, corners.first().world(), part.minX(), part.minZ(), part.maxX(), part.maxZ(), part.minY(), part.maxY());
    }

    /** treasury <region> none|own|main, treasury <region> balance, treasury <region> pay <player> <amount>, treasury <region> move <to> <amount>. */
    private void treasury(CommandSender sender, String[] args) throws IOException {
        Region region = find(need(args, 1));
        String sub = need(args, 2).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "none", "own", "main" -> {
                var mode = Region.Treasury.valueOf(sub.toUpperCase(Locale.ROOT));
                change(sender, region.name(), r -> new Region(r.id(), r.name(), r.localizedNames(), r.world(), r.selections(), r.protection(), r.land(), r.teleport(), mode, r.tag(), r.color()));
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
        plugin.info("log.transfer", sender.getName(), from, to, amount);
        // A fresh operation id per command run: pressing enter twice moves money twice, but a retry of one run cannot.
        plugin.economy().transfer(from, to, amount, "treasury:" + UUID.randomUUID()).whenComplete((status, error) ->
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                    if (error == null && status.equals("OK")) send(sender, "done.transfer", amount);
                    else send(sender, "cmd.failed", error == null ? status : String.valueOf(error.getMessage()));
                }));
    }

    private void change(CommandSender sender, String name, UnaryOperator<Region> edit) throws IOException {
        change(sender, name, edit, "done.updated", name);
    }

    /** Saves the edited region and tells what was done, with the key and arguments of the text that says it. */
    private void change(CommandSender sender, String name, UnaryOperator<Region> edit, String doneKey, Object... doneArgs) throws IOException {
        Region old = find(name);
        List<Region> next = new ArrayList<>(plugin.regions().all());
        next.replaceAll(r -> r.id().equals(old.id()) ? edit.apply(r) : r);
        save(next);
        send(sender, doneKey, doneArgs);
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
        send(sender, "done.adjust");
    }
}
