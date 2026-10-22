package dev.toka.regions.storage;

import dev.toka.regions.model.*;
import dev.toka.storage.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class RegionRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static Region region() {
        return new Region(UUID.randomUUID(), "town", Map.of("en-US", "Town"), "w", List.of(new RegionSelection(0, 0, 0, 9, 9, 9)), Region.Protection.PROTECT,
                new LandRules(true, true, 0.73, List.of(new TaxTier(5, 0.31, null), new TaxTier(20, 0.93, 17.73))),
                new Region.Teleport(1.5, 2, 3.5, 90f, 0f));
    }

    @Test public void everythingSurvivesAnyBackend() throws Exception {
        for (Backend backend : Backend.values()) {
            var path = temporary.newFolder(backend.name()).toPath();
            var saved = region();
            var adjustment = new TaxAdjustment(UUID.randomUUID(), 0.2, "late fees", UUID.randomUUID(), 1000L, null);
            try (var store = DocumentStore.open(new StoreConfig(backend, path, 1))) {
                var repository = new RegionRepository(store);
                assertTrue(repository.loadRegions().all().isEmpty());
                repository.saveRegions(List.of(saved));
                repository.saveAdjustments(List.of(adjustment));
            }
            try (var store = DocumentStore.open(new StoreConfig(backend, path, 1))) {
                var repository = new RegionRepository(store);
                assertEquals(saved, repository.loadRegions().get(saved.id()).orElseThrow());
                assertEquals(List.of(adjustment), repository.loadAdjustments());
            }
        }
    }

    @Test public void overlappingRegionsAreNotSaved() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1))) {
            var repository = new RegionRepository(store);
            var a = region();
            var b = new Region(UUID.randomUUID(), "other", Map.of(), "w", List.of(new RegionSelection(5, 0, 0, 14, 9, 9)), null, null, null);
            try { repository.saveRegions(List.of(a, b)); fail(); } catch (IllegalArgumentException expected) { }
            assertTrue(repository.loadRegions().all().isEmpty());
        }
    }

    @Test public void aLaterAdjustmentReplacesTheEarlierOneForThatPlayer() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1))) {
            var repository = new RegionRepository(store);
            var player = UUID.randomUUID();
            var first = new TaxAdjustment(player, 0.1, "a", null, 1L, null);
            var second = new TaxAdjustment(player, -0.1, "b", null, 2L, 99L);
            assertEquals(List.of(second), repository.saveAdjustments(List.of(first, second)));
            assertTrue(second.activeAt(50));
            assertFalse(second.activeAt(100));
        }
    }

    @Test(expected = IllegalArgumentException.class) public void anAdjustmentNeedsAReason() {
        new TaxAdjustment(UUID.randomUUID(), 0.1, " ", null, 1L, null);
    }
}
