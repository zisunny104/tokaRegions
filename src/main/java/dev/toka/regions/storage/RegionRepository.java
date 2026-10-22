package dev.toka.regions.storage;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import dev.toka.regions.model.Region;
import dev.toka.regions.model.RegionIndex;
import dev.toka.regions.model.TaxAdjustment;
import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.util.*;

/** Regions and personal tax adjustments, each as one document. A save is validated first and read back before it counts. */
public final class RegionRepository {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String REGIONS = "regions";
    private static final String ADJUSTMENTS = "tax-adjustments";
    private final DocumentStore store;

    public RegionRepository(DocumentStore store) { this.store = Objects.requireNonNull(store); }

    public RegionIndex loadRegions() throws IOException {
        return new RegionIndex(read(REGIONS, "regions", new TypeToken<List<Region>>() {}));
    }

    public synchronized RegionIndex saveRegions(Collection<Region> regions) throws IOException {
        RegionIndex index = new RegionIndex(regions);
        write(REGIONS, "regions", new ArrayList<>(index.all()));
        RegionIndex readBack = loadRegions();
        if (!new HashSet<>(readBack.all()).equals(new HashSet<>(index.all()))) throw new IOException("Region readback mismatch");
        return readBack;
    }

    public List<TaxAdjustment> loadAdjustments() throws IOException {
        return read(ADJUSTMENTS, "adjustments", new TypeToken<List<TaxAdjustment>>() {});
    }

    /** One adjustment per player; a later one replaces the earlier. */
    public synchronized List<TaxAdjustment> saveAdjustments(Collection<TaxAdjustment> adjustments) throws IOException {
        Map<UUID, TaxAdjustment> byPlayer = new LinkedHashMap<>();
        adjustments.forEach(a -> byPlayer.put(a.player(), a));
        List<TaxAdjustment> list = List.copyOf(byPlayer.values());
        write(ADJUSTMENTS, "adjustments", list);
        if (!new HashSet<>(loadAdjustments()).equals(new HashSet<>(list))) throw new IOException("Adjustment readback mismatch");
        return list;
    }

    private <T> List<T> read(String key, String field, TypeToken<List<T>> type) throws IOException {
        Optional<JsonObject> document = store.load(key);
        if (document.isEmpty()) return List.of();
        JsonObject root = document.get();
        if (!root.has("format") || root.get("format").getAsInt() != 1) throw new IOException("Unsupported " + key + " format");
        try { return GSON.fromJson(root.get(field), type.getType()); }
        catch (JsonParseException | IllegalArgumentException bad) { throw new IOException("Unreadable " + key, bad); }
    }

    private void write(String key, String field, Object value) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add(field, GSON.toJsonTree(value));
        store.save(key, root);
    }
}
