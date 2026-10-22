package dev.toka.regions;

import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.Player;
import cn.nukkit.plugin.PluginBase;
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
    private static volatile Main instance;
    private DocumentStore store;
    private RegionRepository repository;
    private ProfilesLink profiles;
    private EconomyLink economy;
    private SelectionTool selection;
    private RegionCommand command;
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
            profiles = new ProfilesLink(getServer());
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
            if (config.getBoolean("protection.enforce", false)) {
                getServer().getPluginManager().registerEvents(new RegionGuard(this, land), this);
                getLogger().warning("區域保護已啟用：請確認 zero 的舊區域保護已停用，否則兩邊會同時攔截。");
            } else {
                getLogger().info("區域保護待命中：protection.enforce 為 false。");
            }
            getLogger().info("區域資料載入完成，共 " + regions.all().size() + " 個；儲存格式：" + backend);
        } catch (Exception error) {
            getLogger().error("tokaRegions 未能載入；為避免以空資料覆蓋原資料，插件將停用。", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** tokaLand may start after us or be reloaded; hand it the rules again whenever it comes up. */
    @EventHandler public void onPluginEnabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("tokaLand") && LandProvider.register(this)) getLogger().info("已向 tokaLand 登錄區域規則。");
        if (event.getPlugin().getName().equals("tokaEconomy")) ensureAccounts();
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
     * For tools such as the one-time treasury import: the account that receives payments for land at a point, as a UUID
     * string, or null when no region or account applies. Safe to call from any thread (the region index is immutable).
     */
    /** The id (UUID string) of the region at a point, or null. Safe from any thread. */
    public static String regionIdAt(String world, int x, int y, int z) {
        Main main = instance;
        return main == null ? null : main.regions.at(world, x, y, z).map(r -> r.id().toString()).orElse(null);
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
        String tag = sender instanceof Player player ? player.getLocale().toLanguageTag() : getConfig().getString("language", "zh-TW");
        String selected = tag.startsWith("en") ? "en-US" : "zh-TW";
        return languages.computeIfAbsent(selected, t -> new Messages(getClass().getClassLoader(), getDataFolder().toPath(), t, line -> getLogger().warning(line))).text(key, args);
    }
}
