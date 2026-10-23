package dev.toka.regions.form;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementButtonImageData;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import dev.toka.regions.Main;
import dev.toka.regions.model.Region;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The windows of /region: a searchable list, a page per region, a household page for players and, for administrators, an
 * edit form. Every change goes through the command, so the window and the command can never disagree.
 */
public final class RegionWindow implements Listener {
    private static final int PER_PAGE = 10;
    private static final String[] COLORS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
    private static final Region.Protection[] MODES = Region.Protection.values();
    private static final Region.Treasury[] TREASURIES = Region.Treasury.values();

    private record Entry(Player player, Consumer<PlayerFormRespondedEvent> handler) {}

    private final Main plugin;
    private final Map<FormWindow, Entry> entries = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<UUID, String> queries = new ConcurrentHashMap<>();

    public RegionWindow(Main plugin) { this.plugin = plugin; }

    private String t(Player player, String key, Object... args) { return plugin.text(player, key, args); }

    private void show(Player player, FormWindow window, Consumer<PlayerFormRespondedEvent> handler) {
        entries.put(window, new Entry(player, handler));
        player.showFormWindow(window);
    }

    @EventHandler public void onResponse(PlayerFormRespondedEvent event) {
        Entry entry = entries.remove(event.getWindow());
        if (entry != null && entry.player() == event.getPlayer()) entry.handler().accept(event);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        queries.remove(player.getUniqueId());
        synchronized (entries) { entries.values().removeIf(e -> e.player() == player); }
    }

    private static ElementButton button(String text, String icon) {
        return icon == null ? new ElementButton(text) : new ElementButton(text, new ElementButtonImageData("path", "textures/ui/" + icon));
    }

    /** A page of buttons; the handler gets the index of the pressed one. A closed window does nothing. */
    private void menu(Player player, String title, String content, List<ElementButton> buttons, Consumer<Integer> pressed) {
        show(player, new FormWindowSimple(title, content, buttons), event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseSimple response)) return;
            pressed.accept(response.getClickedButtonId());
        });
    }

    private String language(Player player) { return player.getLocale() == null ? plugin.getConfig().getString("language", "zh-TW") : player.getLocale().toLanguageTag(); }

    /** The list of regions, from the first page and without a search. */
    public void open(Player player) {
        queries.remove(player.getUniqueId());
        list(player, 1);
    }

    private void list(Player player, int page) {
        String query = queries.getOrDefault(player.getUniqueId(), "");
        String language = language(player);
        String needle = query.toLowerCase(Locale.ROOT);
        List<Region> all = plugin.regions().all().stream()
                .filter(r -> needle.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(needle) || r.displayName(language).toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Region::name, String.CASE_INSENSITIVE_ORDER)).toList();
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.min(Math.max(page, 1), pages);
        List<Region> shown = all.subList((current - 1) * PER_PAGE, Math.min(all.size(), current * PER_PAGE));
        String title = pages > 1 ? t(player, "win.pageTitle", t(player, "win.title"), current, pages) : t(player, "win.title");
        String content = plugin.regions().all().isEmpty() ? t(player, "win.listEmpty")
                : all.isEmpty() ? t(player, "win.noMatch", query) : t(player, "win.listHint", all.size());
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!query.isEmpty()) { buttons.add(button(t(player, "win.back"), "arrow_left")); actions.add(() -> open(player)); }
        buttons.add(button(t(player, "win.search"), "magnifyingGlass"));
        actions.add(() -> search(player));
        buttons.add(button(t(player, "win.household"), "icon_best3"));
        actions.add(() -> household(player));
        for (Region region : shown) {
            buttons.add(button(t(player, "win.row", region.coloredName(language), t(player, "win.treasury." + region.treasury().name().toLowerCase(Locale.ROOT)),
                    t(player, "win.mode." + region.protection().name().toLowerCase(Locale.ROOT))), "icon_map"));
            actions.add(() -> info(player, region.id()));
        }
        if (current > 1) { buttons.add(button(t(player, "win.prev"), null)); actions.add(() -> list(player, current - 1)); }
        if (current < pages) { buttons.add(button(t(player, "win.next"), null)); actions.add(() -> list(player, current + 1)); }
        menu(player, title, content, buttons, index -> actions.get(index).run());
    }

    private void search(Player player) {
        FormWindowCustom window = new FormWindowCustom(t(player, "win.searchTitle"));
        window.addElement(new ElementInput(t(player, "win.query"), t(player, "win.queryHint"), queries.getOrDefault(player.getUniqueId(), "")));
        show(player, window, event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) return;
            String query = response.getInputResponse(0) == null ? "" : response.getInputResponse(0).strip();
            if (query.length() > 64) query = query.substring(0, 64);
            if (query.isEmpty()) queries.remove(player.getUniqueId()); else queries.put(player.getUniqueId(), query);
            list(player, 1);
        });
    }

    private String onOff(Player player, boolean value) { return t(player, value ? "win.on" : "win.off"); }

    private void info(Player player, UUID id) {
        Region region = plugin.regions().get(id).orElse(null);
        if (region == null) { list(player, 1); return; }
        var land = region.land();
        String content = t(player, "win.info", region.world(), t(player, "win.mode." + region.protection().name().toLowerCase(Locale.ROOT)),
                t(player, "win.treasury." + region.treasury().name().toLowerCase(Locale.ROOT)), onOff(player, land.purchaseAllowed()), onOff(player, land.flatPricing()),
                land.affiliatedOffer(), NumberFormat.getIntegerInstance(Locale.US).format(land.taxTiers().size()));
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(button(t(player, "win.back"), "arrow_left")); actions.add(() -> list(player, 1));
        if (plugin.getConfig().getBoolean("household.player-change", false) && plugin.villages().stream().anyMatch(v -> v.id().equals(id))) {
            buttons.add(button(t(player, "win.setHousehold"), "icon_best3"));
            actions.add(() -> { run(player, "household", region.name()); info(player, id); });
        }
        if (player.hasPermission("tokaregions.admin")) {
            buttons.add(button(t(player, "win.edit"), "pencil_edit_icon")); actions.add(() -> edit(player, id));
            buttons.add(button(t(player, "win.delete"), "trash_default")); actions.add(() -> confirmDelete(player, id));
        }
        menu(player, region.coloredName(language(player)), content, buttons, index -> actions.get(index).run());
    }

    private void confirmDelete(Player player, UUID id) {
        Region region = plugin.regions().get(id).orElse(null);
        if (region == null || !player.hasPermission("tokaregions.admin")) { list(player, 1); return; }
        show(player, new FormWindowModal(t(player, "win.deleteTitle", region.name()), t(player, "win.deleteBody"), t(player, "win.deleteYes"), t(player, "win.deleteNo")), event -> {
            if (!event.wasClosed() && event.getResponse() instanceof FormResponseModal response && response.getClickedButtonId() == 0) {
                run(player, "del", region.name(), "confirm");
                list(player, 1);
            } else info(player, id);
        });
    }

    /** The household page: where the player is registered and, when the server lets players change it, the villages to pick. */
    private void household(Player player) {
        String language = language(player);
        String current = plugin.profiles().affiliation(player.getUniqueId()).flatMap(plugin.regions()::get).map(r -> r.coloredName(language)).orElse(t(player, "household.none"));
        boolean change = plugin.getConfig().getBoolean("household.player-change", false);
        List<Region> villages = change ? plugin.villages() : List.of();
        List<ElementButton> buttons = new ArrayList<>();
        buttons.add(button(t(player, "win.back"), "arrow_left"));
        villages.forEach(v -> buttons.add(button(v.coloredName(language), "icon_map")));
        String content = t(player, "win.householdBody", current) + (change ? "" : "\n" + t(player, "win.householdLocked"));
        menu(player, t(player, "win.household"), content, buttons, index -> {
            if (index > 0) run(player, "household", villages.get(index - 1).name());
            list(player, 1);
        });
    }

    private void edit(Player player, UUID id) {
        Region region = plugin.regions().get(id).orElse(null);
        if (region == null || !player.hasPermission("tokaregions.admin")) { list(player, 1); return; }
        FormWindowCustom window = new FormWindowCustom(t(player, "win.editTitle", region.name()));
        window.addElement(new ElementLabel(t(player, "win.editHint")));
        window.addElement(new ElementDropdown(t(player, "win.editMode"), List.of(MODES).stream().map(m -> t(player, "win.mode." + m.name().toLowerCase(Locale.ROOT))).toList(), region.protection().ordinal()));
        window.addElement(new ElementToggle(t(player, "win.editBuy"), region.land().purchaseAllowed()));
        window.addElement(new ElementToggle(t(player, "win.editFlat"), region.land().flatPricing()));
        window.addElement(new ElementInput(t(player, "win.editOffer"), "0~1", String.valueOf(region.land().affiliatedOffer())));
        window.addElement(new ElementDropdown(t(player, "win.editTax", region.land().taxTiers().size()), List.of(t(player, "win.taxKeep"), t(player, "win.taxAdd"), t(player, "win.taxClear")), 0));
        window.addElement(new ElementInput(t(player, "win.editTaxOver"), "100", ""));
        window.addElement(new ElementInput(t(player, "win.editTaxRate"), "0.1", ""));
        window.addElement(new ElementInput(t(player, "win.editTaxOutsider"), "0.2", ""));
        window.addElement(new ElementDropdown(t(player, "win.editTreasury"), List.of(TREASURIES).stream().map(m -> t(player, "win.treasury." + m.name().toLowerCase(Locale.ROOT))).toList(), region.treasury().ordinal()));
        window.addElement(new ElementInput(t(player, "win.editTag"), "", region.tag().replace('§', '&')));
        List<String> colors = new ArrayList<>(List.of(t(player, "win.colorNone")));
        for (String code : COLORS) colors.add("§" + code + "&" + code);
        int colorIndex = region.color().isEmpty() ? 0 : List.of(COLORS).indexOf(region.color().substring(1)) + 1;
        window.addElement(new ElementDropdown(t(player, "win.editColor"), colors, colorIndex));
        show(player, window, event -> {
            if (!event.wasClosed() && event.getResponse() instanceof FormResponseCustom response) apply(player, region, response);
            info(player, id);
        });
    }

    /** Turns the answers into the same commands an administrator would type, sending only what changed. */
    private void apply(Player player, Region region, FormResponseCustom response) {
        if (!player.hasPermission("tokaregions.admin")) return;
        String name = region.name();
        boolean changed = false;
        Region.Protection mode = MODES[response.getDropdownResponse(1).getElementID()];
        if (mode != region.protection()) { run(player, "mode", name, mode.name().toLowerCase(Locale.ROOT)); changed = true; }
        boolean buy = response.getToggleResponse(2), flat = response.getToggleResponse(3);
        if (buy != region.land().purchaseAllowed()) { run(player, "land", name, "buy", buy ? "on" : "off"); changed = true; }
        if (flat != region.land().flatPricing()) { run(player, "land", name, "flat", flat ? "on" : "off"); changed = true; }
        String offer = response.getInputResponse(4) == null ? "" : response.getInputResponse(4).strip();
        if (!offer.isEmpty() && !offer.equals(String.valueOf(region.land().affiliatedOffer()))) { run(player, "land", name, "offer", offer); changed = true; }
        int tax = response.getDropdownResponse(5).getElementID();
        if (tax == 2) { run(player, "tax", name, "clear"); changed = true; }
        if (tax == 1) {
            String outsider = response.getInputResponse(8) == null ? "" : response.getInputResponse(8).strip();
            List<String> args = new ArrayList<>(List.of("tax", name, "add", response.getInputResponse(6).strip(), response.getInputResponse(7).strip()));
            if (!outsider.isEmpty()) args.add(outsider);
            run(player, args.toArray(new String[0]));
            changed = true;
        }
        Region.Treasury treasury = TREASURIES[response.getDropdownResponse(9).getElementID()];
        if (treasury != region.treasury()) { run(player, "treasury", name, treasury.name().toLowerCase(Locale.ROOT)); changed = true; }
        String tag = response.getInputResponse(10) == null ? "" : response.getInputResponse(10).strip();
        if (!tag.equals(region.tag().replace('§', '&'))) { run(player, "tag", name, tag.isEmpty() ? "clear" : tag); changed = true; }
        int color = response.getDropdownResponse(11).getElementID();
        String code = color == 0 ? "" : "§" + COLORS[color - 1];
        if (!code.equals(region.color())) { run(player, "color", name, color == 0 ? "clear" : "&" + COLORS[color - 1]); changed = true; }
        if (!changed) player.sendMessage(t(player, "win.noChange"));
    }

    private void run(Player player, String... args) {
        var command = plugin.command();
        if (command != null) command.execute(player, "region", args);
    }
}
