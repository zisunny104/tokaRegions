package dev.toka.regions;

import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.toka.i18n.Messages;
import dev.toka.regions.command.RegionCommand;
import dev.toka.regions.link.EconomyLink;
import dev.toka.regions.link.LandLink;
import dev.toka.regions.link.LandProvider;
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
            economy = new EconomyLink(getServer(), config.getString("treasury.currency", "money"));
            var item = cn.nukkit.item.Item.fromString(config.getString("selection.tool", "294:0"));
            selection = new SelectionTool(this, item);
            getServer().getPluginManager().registerEvents(selection, this);
            getServer().getPluginManager().registerEvents(this, this);
            command = new RegionCommand(this);
            var commands = getServer().getCommandMap();
            var existing = commands.getCommand("region");
            if (existing != null && existing != command) {
                getLogger().warning("/region 已由其他插件註冊；請先停用該入口再重啟。此次不註冊替代指令。");
                command = null;
            } else if (!commands.register("tokaRegions", command) || commands.getCommand("region") != command) {
                getLogger().error("無法註冊指令 /region；請檢查指令衝突。");
                command = null;
            }
            if (LandProvider.register(this)) getLogger().info("已向 tokaLand 登錄區域規則。");
            ensureAccounts();
            land = new LandLink(getServer());
            dev.toka.regions.link.DisplayLink.register(this);
            if (config.getBoolean("protection.enforce", true)) {
                guard = new RegionGuard(this, land);
                getServer().getPluginManager().registerEvents(guard, this);
            }
            getLogger().info("§b載入區域共 §a" + regions.all().size() + " §b個");
        } catch (Exception error) {
            getLogger().error("tokaRegions 未能載入；為避免以空資料覆蓋原資料，插件將停用。", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void savePending() {
        try {
            repository.savePending(pendingAffiliations.entrySet().stream().map(e -> new RegionRepository.Pending(e.getKey(), e.getValue())).toList());
        } catch (IOException failure) {
            getLogger().warning("無法儲存待處理的區域歸屬：" + failure);
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

    /** Profiles creates the profile as the player joins, so the membership is tried a moment later. */
    @EventHandler public void onJoin(cn.nukkit.event.player.PlayerJoinEvent event) {
        if (pendingAffiliations.isEmpty()) return;
        Player player = event.getPlayer();
        getServer().getScheduler().scheduleDelayedTask(this, () -> { if (player.isOnline()) applyPending(player); }, 40);
    }

    /** tokaLand may start after us or be reloaded; hand it the rules again whenever it comes up. */
    @EventHandler public void onPluginEnabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("tokaLand") && LandProvider.register(this)) getLogger().info("已向 tokaLand 登錄區域規則。");
        if (event.getPlugin().getName().equals("tokaEconomy")) ensureAccounts();
        if (event.getPlugin().getName().equals("tokaDisplay")) dev.toka.regions.link.DisplayLink.register(this);
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
            getLogger().warning("無法連上 tokaProfiles 的舊名稱取回：" + unavailable.getClass().getSimpleName());
        }
    }

    private void applyVillage(Plugin profilesPlugin, UUID uuid) {
        try {
            @SuppressWarnings("unchecked") var detail = (java.util.Map<String, String>) profilesPlugin.getClass().getMethod("legacyDetail", UUID.class).invoke(null, uuid);
            String village = detail.getOrDefault("village", "");
            if (village.isBlank() || profiles.affiliation(uuid).isPresent()) return;
            regions.byName(village).ifPresent(region -> profiles.setAffiliation(uuid, region.id()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            getLogger().warning("無法套用舊紀錄的村莊歸屬：" + unavailable.getClass().getSimpleName());
        }
    }

    @Override public void onDisable() {
        if (command != null) {
            var commands = getServer().getCommandMap();
            commands.getCommands().entrySet().removeIf(entry -> entry.getValue() == command);
            command.unregister(commands);
        }
        if (store != null) try { store.close(); } catch (IOException error) { getLogger().error("關閉區域儲存庫失敗。", error); }
        if (instance == this) instance = null;
    }

    public static Main instance() { return instance; }

    /**
     * For the cutover tool, by reflection: region protection starts now and stays on after a restart. Called after zero's own
     * region protection is switched off.
     */
    public static synchronized void activateProtection() throws IOException {
        Main main = instance;
        if (main == null) throw new IllegalStateException("tokaRegions unavailable");
        ConfigEdit.setBoolean(main.getDataFolder().toPath().resolve("config.yml"), "protection", "enforce", true);
        main.getConfig().set("protection.enforce", true);
        if (main.guard == null) {
            main.guard = new RegionGuard(main, main.land);
            main.getServer().getPluginManager().registerEvents(main.guard, main);
        }
    }

    /** Undoes {@link #activateProtection()}. */
    public static synchronized void deactivateProtection() throws IOException {
        Main main = instance;
        if (main == null) throw new IllegalStateException("tokaRegions unavailable");
        ConfigEdit.setBoolean(main.getDataFolder().toPath().resolve("config.yml"), "protection", "enforce", false);
        main.getConfig().set("protection.enforce", false);
        if (main.guard != null) {
            cn.nukkit.event.HandlerList.unregisterAll(main.guard);
            main.guard = null;
        }
        main.getLogger().info("區域保護已停用（protection.enforce: false）。");
    }

    /** Whether region protection is running here. */
    public static boolean protectionActive() {
        Main main = instance;
        return main != null && main.guard != null;
    }

    /**
     * For the one-time hand-over from zero, by reflection and plain JSON: {"regions":[Region documents],"affiliations":[{"player","region"}]}.
     * Adds only regions whose id and name are new and only sets an affiliation the player does not have, so a second run changes
     * nothing. Returns a one-line report.
     */
    public static String importJson(String json) throws IOException {
        Main main = instance;
        if (main == null) throw new IllegalStateException("tokaRegions unavailable");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Region> fresh = new ArrayList<>();
        List<Region> all = new ArrayList<>(main.regions.all());
        int skipped = 0, tagged = 0;
        for (JsonElement item : root.getAsJsonArray("regions")) {
            Region region = GSON.fromJson(item, Region.class);
            boolean known = all.stream().anyMatch(r -> r.id().equals(region.id()) || r.name().equalsIgnoreCase(region.name()));
            if (known) {
                skipped++;
                String tag = region.tag() == null ? "" : region.tag();
                for (int i = 0; i < all.size(); i++) {
                    Region have = all.get(i);
                    if ((have.id().equals(region.id()) || have.name().equalsIgnoreCase(region.name())) && (have.tag() == null || have.tag().isEmpty()) && !tag.isEmpty()) {
                        all.set(i, new Region(have.id(), have.name(), have.localizedNames(), have.world(), have.selections(), have.protection(), have.land(), have.teleport(), have.treasury(), tag));
                        tagged++;
                    }
                }
                continue;
            }
            fresh.add(region);
            all.add(region);
        }
        if (!fresh.isEmpty() || tagged > 0) {
            main.saveRegions(all);
            main.ensureAccounts();
        }
        int requested = 0, kept = 0;
        List<java.util.concurrent.CompletableFuture<Void>> pending = new ArrayList<>();
        List<RegionRepository.Pending> order = new ArrayList<>();
        for (JsonElement item : root.getAsJsonArray("affiliations")) {
            JsonObject row = item.getAsJsonObject();
            UUID player = UUID.fromString(row.get("player").getAsString()), region = UUID.fromString(row.get("region").getAsString());
            if (main.profiles.affiliation(player).isPresent()) { kept++; continue; }
            pending.add(main.profiles.setAffiliation(player, region));
            order.add(new RegionRepository.Pending(player, region));
            requested++;
        }
        int waiting = 0;
        for (int i = 0; i < pending.size(); i++) {
            try { pending.get(i).get(60, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception noProfileYet) { main.pendingAffiliations.put(order.get(i).player(), order.get(i).region()); waiting++; }
        }
        if (waiting > 0) main.savePending();
        return "regions added " + fresh.size() + ", already there " + skipped + "; affiliations set " + (requested - waiting) + ", kept " + kept + ", waiting for the player's profile " + waiting;
    }

    /**
     * For tools such as the one-time treasury import: the account that receives payments for land at a point, as a UUID
     * string, or null when no region or account applies. Safe to call from any thread (the region index is immutable).
     */
    /** The id (UUID string) of the region at a point, or null. Safe from any thread. */
    public static String regionIdAt(String world, int x, int y, int z) {
        Main main = instance;
        return main == null ? null : main.regions.at(world, x, y, z).map(r -> r.id().toString()).orElse(null);
    }

    /** The name of the region at a point, or null. Safe from any thread. */
    public static String regionNameAt(String world, int x, int y, int z) {
        Main main = instance;
        return main == null ? null : main.regions.at(world, x, y, z).map(r -> r.name()).orElse(null);
    }

    /** The id (UUID string) of the region with this name, or null. */
    public static String regionIdByName(String name) {
        Main main = instance;
        return main == null ? null : main.regions.byName(name).map(r -> r.id().toString()).orElse(null);
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
                getLogger().warning("區域帳戶尚未建立（tokaEconomy 未就緒）：" + region.name());
    }
    public SelectionTool selection() { return selection; }

    /** Saves the whole region set (validated: no overlaps, unique names) and switches to it only after a verified read-back. */
    public void saveRegions(Collection<Region> next) throws IOException { regions = repository.saveRegions(next); }

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
