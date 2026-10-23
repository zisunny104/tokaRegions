package dev.toka.regions;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.PluginCommand;
import cn.nukkit.event.HandlerList;
import cn.nukkit.item.Item;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.toka.i18n.Messages;
import dev.toka.regions.command.RegionCommand;
import dev.toka.regions.link.EconomyLink;
import dev.toka.regions.link.LandLink;
import dev.toka.regions.link.DisplayLink;
import dev.toka.regions.link.LandProvider;
import dev.toka.regions.form.RegionWindow;
import dev.toka.regions.link.ProfilesLink;
import dev.toka.regions.model.Region;
import dev.toka.regions.model.RegionIndex;
import dev.toka.regions.model.TaxAdjustment;
import dev.toka.regions.storage.RegionRepository;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Switchboard: opens storage, hands the region rules to tokaLand and registers the command and selection tool. */
public final class Main extends PluginBase implements Listener {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static volatile Main instance;
    private DocumentStore store;
    private RegionRepository repository;
    private ProfilesLink profiles;
    private EconomyLink economy;
    private SelectionTool selection;
    private RegionCommand command;
    private RegionGuard guard;
    private RegionWindow windows;
    /** Memberships waiting for the player's profile to exist (player to region). */
    private final Map<UUID, UUID> pendingAffiliations = new java.util.concurrent.ConcurrentHashMap<>();
    private LandLink land;
    private final Map<String, Messages> languages = new HashMap<>();
    private volatile RegionIndex regions = new RegionIndex(List.of());
    private volatile List<TaxAdjustment> adjustments = List.of();

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
        try {
            var config = getConfig();
            Path pluginDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path directory = pluginDirectory.resolve(config.getString("storage.directory", "storage")).normalize();
            if (!directory.startsWith(pluginDirectory)) throw new IllegalArgumentException("storage.directory must remain inside the plugin data folder");
            Backend backend = Backend.valueOf(config.getString("storage.backend", "SQLITE").strip().toUpperCase(Locale.ROOT));
            store = DocumentStore.open(new StoreConfig(backend, directory, config.getInt("storage.schema-version", 1)));
            repository = new RegionRepository(store);
            regions = repository.loadRegions();
            adjustments = repository.loadAdjustments();
            repository.loadPending().forEach(p -> pendingAffiliations.put(p.player(), p.region()));
            profiles = new ProfilesLink(getServer());
            getServer().getScheduler().scheduleTask(this, this::listenLegacy);
            land = new LandLink(getServer());
            applyConfig();
            getServer().getPluginManager().registerEvents(this, this);
            windows = new RegionWindow(this);
            command = new RegionCommand(this);
            var commands = getServer().getCommandMap();
            var existing = commands.getCommand("region");
            if (existing != null && existing != command) {
                warn("log.commandTaken", ownerOf(existing));
                command = null;
            } else if (!commands.register("tokaRegions", command) || commands.getCommand("region") != command) {
                error("log.commandFailed");
                command = null;
            }
            if (LandProvider.register(this)) info("log.landRegistered");
            ensureAccounts();
            DisplayLink.register(this);
            getServer().getPluginManager().registerEvents(windows, this);
            refreshSuggestions();
            info("log.loaded", regions.all().size());
        } catch (Exception failure) {
            getLogger().error(text(getServer().getConsoleSender(), "log.loadFailed"), failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** Everything built from config.yml, so that a reload can rebuild it: the money link, the selection tool and the guard. */
    private void applyConfig() {
        var config = getConfig();
        economy = new EconomyLink(getServer(), config.getString("treasury.currency", "money"));
        if (selection != null) HandlerList.unregisterAll(selection);
        selection = new SelectionTool(this, Item.fromString(config.getString("selection.tool", "294:0")));
        getServer().getPluginManager().registerEvents(selection, this);
        if (guard != null) HandlerList.unregisterAll(guard);
        guard = null;
        if (config.getBoolean("protection.enforce", true)) {
            guard = new RegionGuard(this, land);
            getServer().getPluginManager().registerEvents(guard, this);
        }
    }

    /** /region reload: config, texts and what was built from them. Regions are read from storage only at start. */
    public void reloadSettings() {
        reloadConfig();
        languages.clear();
        applyConfig();
        ensureAccounts();
        refreshSuggestions();
    }

    /** Rebuilds the region names offered in Tab and sends the new list to everyone online. */
    public void refreshSuggestions() {
        if (command == null) return;
        command.refreshNames();
        getServer().getOnlinePlayers().values().forEach(Player::sendCommandData);
    }

    public RegionWindow windows() { return windows; }
    public RegionCommand command() { return command; }

    /** The plugin that owns a command, for the log when a name is taken. */
    private static String ownerOf(Command taken) {
        return taken instanceof PluginCommand<?> owned ? owned.getPlugin().getName() : taken.getClass().getName();
    }

    public void info(String key, Object... args) { getLogger().info(text(getServer().getConsoleSender(), key, args)); }
    public void warn(String key, Object... args) { getLogger().warning(text(getServer().getConsoleSender(), key, args)); }
    public void error(String key, Object... args) { getLogger().error(text(getServer().getConsoleSender(), key, args)); }

    private void savePending() {
        try {
            repository.savePending(pendingAffiliations.entrySet().stream().map(e -> new RegionRepository.Pending(e.getKey(), e.getValue())).toList());
        } catch (IOException failure) {
            warn("log.pendingFailed", failure);
        }
    }

    /** Applies a waiting membership once the player's profile exists; a membership of a region that is gone, or one the player already has, is dropped. */
    private void applyPending(Player player) {
        UUID id = player.getUniqueId(), region = pendingAffiliations.get(id);
        if (region == null) return;
        if (profiles.affiliation(id).isPresent() || regions.all().stream().noneMatch(r -> r.id().equals(region))) {
            pendingAffiliations.remove(id);
            savePending();
            return;
        }
        profiles.setAffiliation(id, region).whenComplete((done, failure) -> {
            if (failure == null) getServer().getScheduler().scheduleTask(this, () -> { pendingAffiliations.remove(id); savePending(); });
        });
    }

    /** The name of the region the player is registered in, in its colour; null when there is none. For the city pass, by reflection. */
    public static String householdNameOf(UUID player) {
        Main main = instance;
        if (main == null) return null;
        String language = main.getConfig().getString("language", "zh-TW");
        return main.profiles.affiliation(player).flatMap(main.regions::get).map(r -> r.coloredName(language)).orElse(null);
    }

    /** The regions a player can be registered in (the household), from {@code household.villages}, in that order. */
    public List<Region> villages() {
        return getConfig().getStringList("household.villages").stream().map(regions::byName).flatMap(Optional::stream).toList();
    }

    /**
     * Registers the player in one of the villages, at random. For whoever receives new players (tokaProfiles), by reflection; the result
     * is the region's name, or a failure when the player already has a household or no village is set.
     */
    public static java.util.concurrent.CompletableFuture<String> assignHousehold(UUID player) {
        Main main = instance;
        if (main == null) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("tokaRegions unavailable"));
        List<Region> choices = main.villages();
        if (choices.isEmpty()) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NO_VILLAGES"));
        if (main.profiles.affiliation(player).isPresent()) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("ALREADY_REGISTERED"));
        Region village = choices.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(choices.size()));
        return main.profiles.setAffiliation(player, village.id()).thenApply(done -> {
            main.getServer().getScheduler().scheduleTask(main, () -> {
                Player online = main.getServer().getOnlinePlayers().get(player);
                String language = online == null || online.getLocale() == null ? main.getConfig().getString("language", "zh-TW") : online.getLocale().toLanguageTag();
                if (online != null) online.sendMessage(main.text(online, "affil.assigned", village.coloredName(language)));
                main.info("affil.assignedLog", online == null ? player : online.getName(), village.name());
            });
            return village.name();
        });
    }

    /** Profiles creates the profile as the player joins, so the membership is tried a moment later. */
    @EventHandler public void onJoin(cn.nukkit.event.player.PlayerJoinEvent event) {
        if (pendingAffiliations.isEmpty()) return;
        Player player = event.getPlayer();
        getServer().getScheduler().scheduleDelayedTask(this, () -> { if (player.isOnline()) applyPending(player); }, 40);
    }

    /** tokaLand may start after us or be reloaded; hand it the rules again whenever it comes up. */
    @EventHandler public void onPluginEnabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("tokaLand") && LandProvider.register(this)) info("log.landRegistered");
        if (event.getPlugin().getName().equals("tokaEconomy")) ensureAccounts();
        if (event.getPlugin().getName().equals("tokaDisplay")) DisplayLink.register(this);
        if (event.getPlugin().getName().equals("tokaProfiles")) {
            getServer().getOnlinePlayers().values().forEach(this::applyPending);
            getServer().getScheduler().scheduleTask(this, this::listenLegacy);
        }
    }

    private boolean listeningLegacy;

    /** When tokaProfiles hands an old name to an account, the village the old record named becomes that account's affiliation. */
    private void listenLegacy() {
        Plugin profilesPlugin = getServer().getPluginManager().getPlugin("tokaProfiles");
        if (profilesPlugin == null || !profilesPlugin.isEnabled()) return;
        try {
            profilesPlugin.getClass().getMethod("onLegacyResolved", java.util.function.BiConsumer.class).invoke(null, (java.util.function.BiConsumer<String, UUID>) (name, uuid) ->
                    getServer().getScheduler().scheduleTask(this, () -> applyVillage(profilesPlugin, uuid)));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            warn("log.legacyUnavailable", unavailable.getClass().getSimpleName());
        }
    }

    private void applyVillage(Plugin profilesPlugin, UUID uuid) {
        try {
            @SuppressWarnings("unchecked") var detail = (java.util.Map<String, String>) profilesPlugin.getClass().getMethod("legacyDetail", UUID.class).invoke(null, uuid);
            String village = detail.getOrDefault("village", "");
            if (village.isBlank() || profiles.affiliation(uuid).isPresent()) return;
            regions.byName(village).ifPresent(region -> profiles.setAffiliation(uuid, region.id()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            warn("log.villageUnavailable", unavailable.getClass().getSimpleName());
        }
    }

    @Override public void onDisable() {
        getServer().getScheduler().cancelTask(this);
        LandProvider.unregister(this);
        DisplayLink.unregister(this);
        if (command != null) {
            var commands = getServer().getCommandMap();
            commands.getCommands().entrySet().removeIf(entry -> entry.getValue() == command);
            command.unregister(commands);
        }
        if (store != null) try { store.close(); } catch (IOException error) { getLogger().error(text(getServer().getConsoleSender(), "log.closeFailed"), error); }
        if (instance == this) instance = null;
    }

    public static Main instance() { return instance; }

    /**
     * For tools such as the one-time treasury import: the account that receives payments for land at a point, as a UUID
     * string, or null when no region or account applies. Safe to call from any thread (the region index is immutable).
     */
    /** The id (UUID string) of the region at a point, or null. Safe from any thread. */
    public static String regionIdAt(String world, int x, int y, int z) {
        Main main = instance;
        return main == null ? null : main.regions.at(world, x, y, z).map(r -> r.id().toString()).orElse(null);
    }

    /** The name players read for the region at a point (in the server language), or null. Safe from any thread. */
    public static String regionNameAt(String world, int x, int y, int z) {
        Main main = instance;
        return main == null ? null : main.regions.at(world, x, y, z).map(r -> r.coloredName(main.getConfig().getString("language", "zh-TW"))).orElse(null);
    }

    /** The name (the code, not the name players read) of the region at a point, or null. Safe from any thread. */
    public static String regionCodeAt(String world, int x, int y, int z) {
        Main main = instance;
        return main == null ? null : main.regions.at(world, x, y, z).map(r -> r.name()).orElse(null);
    }

    /** The same name without its colour, for text that is not shown in the game (logs, Discord). */
    public static String plainRegionNameAt(String world, int x, int y, int z) {
        Main main = instance;
        return main == null ? null : main.regions.at(world, x, y, z).map(r -> r.displayName(main.getConfig().getString("language", "zh-TW"))).orElse(null);
    }

    /** The id (UUID string) of the region with this name, or null. */
    public static String regionIdByName(String name) {
        Main main = instance;
        return main == null ? null : main.regions.byName(name).map(r -> r.id().toString()).orElse(null);
    }

    /** The account (UUID string) of the main treasury, or null when no region holds it or the regions are not loaded. */
    public static String treasuryAccount() {
        Main main = instance;
        return main == null ? null : main.regions.mainAccountRegion().map(r -> r.id().toString()).orElse(null);
    }

    public static String accountAt(String world, int x, int y, int z) {
        Main main = instance;
        if (main == null) return null;
        RegionIndex index = main.regions;
        return index.at(world, x, y, z).flatMap(index::accountFor).map(UUID::toString).orElse(null);
    }
    public RegionIndex regions() { return regions; }
    public ProfilesLink profiles() { return profiles; }
    public EconomyLink economy() { return economy; }

    /** Makes sure every region that keeps money has its account; safe to repeat. */
    public void ensureAccounts() {
        for (Region region : regions.all())
            if (region.hasAccount() && !economy.ensureAccount(region.id(), region.name()))
                warn("log.accountMissing", region.name());
    }
    public SelectionTool selection() { return selection; }

    /** Saves the whole region set (validated: no overlaps, unique names) and switches to it only after a verified read-back. */
    public void saveRegions(Collection<Region> next) throws IOException {
        regions = repository.saveRegions(next);
        refreshSuggestions();
    }

    /** Sum of the player's personal tax adjustments that are still in force. */
    public double adjustmentRate(UUID player, long now) {
        return adjustments.stream().filter(a -> a.player().equals(player) && a.activeAt(now)).mapToDouble(TaxAdjustment::rate).sum();
    }

    public List<TaxAdjustment> adjustments() { return adjustments; }
    public void saveAdjustments(Collection<TaxAdjustment> next) throws IOException { adjustments = repository.saveAdjustments(next); }

    public String text(CommandSender sender, String key, Object... args) {
        String tag = sender instanceof Player player && player.getLocale() != null ? player.getLocale().toLanguageTag() : getConfig().getString("language", "zh-TW");
        String selected = tag.startsWith("en") ? "en-US" : "zh-TW";
        return languages.computeIfAbsent(selected, t -> new Messages(getClass().getClassLoader(), getDataFolder().toPath(), t, line -> getLogger().warning(line))).text(key, args);
    }
}
