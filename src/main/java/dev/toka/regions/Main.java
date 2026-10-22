package dev.toka.regions;

import cn.nukkit.plugin.PluginBase;
import dev.toka.regions.model.RegionIndex;
import dev.toka.regions.storage.RegionRepository;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Switchboard for regions; the rules and storage live in the model, policy and storage packages. */
public final class Main extends PluginBase {
    private static volatile Main instance;
    private DocumentStore store;
    private RegionRepository repository;
    private volatile RegionIndex regions = new RegionIndex(List.of());

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
            getLogger().info("區域資料載入完成，共 " + regions.all().size() + " 個；儲存格式：" + backend);
        } catch (Exception error) {
            getLogger().error("tokaRegions 未能載入；為避免以空資料覆蓋原資料，插件將停用。", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (store != null) try { store.close(); } catch (IOException error) { getLogger().error("關閉區域儲存庫失敗。", error); }
        if (instance == this) instance = null;
    }

    public static Main instance() { return instance; }
    public RegionIndex regions() { return regions; }
    public RegionRepository repository() { return repository; }
}
