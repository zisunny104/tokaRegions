package dev.toka.regions.model;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RegionIndexTest {
    private static Region region(String name, String world, RegionSelection... selections) {
        return new Region(UUID.randomUUID(), name, Map.of("en-US", name + "!"), world, List.of(selections), Region.Protection.OPEN, LandRules.closed(), null);
    }

    @Test public void findsTheRegionAPointIsIn() {
        var a = region("a", "w", new RegionSelection(0, 0, 0, 9, 9, 9));
        var b = region("b", "w", new RegionSelection(20, 0, 0, 29, 9, 9));
        var index = new RegionIndex(List.of(a, b));
        assertEquals(a, index.at("w", 5, 5, 5).orElseThrow());
        assertEquals(b, index.at("w", 25, 5, 5).orElseThrow());
        assertTrue(index.at("w", 15, 5, 5).isEmpty());
        assertTrue(index.at("other", 5, 5, 5).isEmpty());
    }

    @Test public void oneRegionMayHaveSeveralSelections() {
        var l = region("l", "w", new RegionSelection(0, 0, 0, 9, 9, 9), new RegionSelection(10, 0, 0, 19, 9, 4));
        assertTrue(new RegionIndex(List.of(l)).at("w", 15, 2, 2).isPresent());
        assertTrue(new RegionIndex(List.of(l)).at("w", 15, 2, 8).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class) public void overlappingRegionsAreRejected() {
        new RegionIndex(List.of(region("a", "w", new RegionSelection(0, 0, 0, 9, 9, 9)), region("b", "w", new RegionSelection(5, 0, 0, 14, 9, 9))));
    }

    @Test public void sameSpaceInAnotherWorldIsFine() {
        new RegionIndex(List.of(region("a", "w", new RegionSelection(0, 0, 0, 9, 9, 9)), region("b", "x", new RegionSelection(0, 0, 0, 9, 9, 9))));
    }

    @Test(expected = IllegalArgumentException.class) public void duplicateNamesAreRejectedIgnoringCase() {
        new RegionIndex(List.of(region("Town", "w", new RegionSelection(0, 0, 0, 1, 1, 1)), region("town", "x", new RegionSelection(0, 0, 0, 1, 1, 1))));
    }

    @Test public void displayNameFallsBackToTheDefaultName() {
        var r = region("town", "w", new RegionSelection(0, 0, 0, 1, 1, 1));
        assertEquals("town!", r.displayName("en-US"));
        assertEquals("town", r.displayName("zh-TW"));
    }

    private static Region withTreasury(String name, int x, Region.Treasury treasury) {
        return new Region(UUID.randomUUID(), name, Map.of(), "w", List.of(new RegionSelection(x, 0, 0, x + 5, 9, 9)), Region.Protection.OPEN, LandRules.closed(), null, treasury);
    }

    @Test public void paymentsGoToTheRegionsOwnAccountElseTheMainOne() {
        var main = withTreasury("main", 0, Region.Treasury.MAIN);
        var village = withTreasury("village", 20, Region.Treasury.OWN);
        var plain = withTreasury("plain", 40, Region.Treasury.NONE);
        var index = new RegionIndex(List.of(main, village, plain));
        assertEquals(village.id(), index.accountFor(village).orElseThrow());
        assertEquals(main.id(), index.accountFor(plain).orElseThrow());
        assertEquals(main.id(), index.accountFor(main).orElseThrow());
    }

    @Test public void withoutAMainAccountAnAccountlessRegionHasNoBeneficiary() {
        var plain = withTreasury("plain", 40, Region.Treasury.NONE);
        assertTrue(new RegionIndex(List.of(plain)).accountFor(plain).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class) public void onlyOneRegionCanBeMain() {
        new RegionIndex(List.of(withTreasury("a", 0, Region.Treasury.MAIN), withTreasury("b", 20, Region.Treasury.MAIN)));
    }

    @Test public void aRegionStoredWithoutTheSettingHasNoAccount() {
        assertEquals(Region.Treasury.NONE, new Region(UUID.randomUUID(), "x", null, "w", List.of(new RegionSelection(0, 0, 0, 1, 1, 1)), null, null, null, null).treasury());
    }

    @Test public void theTagDefaultsToNothingAndIsKept() {
        var plain = new Region(UUID.randomUUID(), "x", null, "w", List.of(new RegionSelection(0, 0, 0, 1, 1, 1)), null, null, null);
        assertEquals("", plain.tag());
        var tagged = new Region(plain.id(), "x", null, "w", plain.selections(), null, null, null, Region.Treasury.OWN, "§a[和平村]");
        assertEquals("§a[和平村]", tagged.tag());
        assertEquals("", new Region(plain.id(), "x", null, "w", plain.selections(), null, null, null, Region.Treasury.OWN, null).tag());
    }
}
