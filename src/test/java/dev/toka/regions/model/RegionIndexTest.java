package dev.toka.regions.model;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RegionIndexTest {
    private static Region region(String name, String world, RegionBox... boxes) {
        return new Region(UUID.randomUUID(), name, Map.of("en-US", name + "!"), world, List.of(boxes), Region.Protection.OPEN, LandRules.closed(), null);
    }

    @Test public void findsTheRegionAPointIsIn() {
        var a = region("a", "w", new RegionBox(0, 0, 0, 9, 9, 9));
        var b = region("b", "w", new RegionBox(20, 0, 0, 29, 9, 9));
        var index = new RegionIndex(List.of(a, b));
        assertEquals(a, index.at("w", 5, 5, 5).orElseThrow());
        assertEquals(b, index.at("w", 25, 5, 5).orElseThrow());
        assertTrue(index.at("w", 15, 5, 5).isEmpty());
        assertTrue(index.at("other", 5, 5, 5).isEmpty());
    }

    @Test public void oneRegionMayHaveSeveralBoxes() {
        var l = region("l", "w", new RegionBox(0, 0, 0, 9, 9, 9), new RegionBox(10, 0, 0, 19, 9, 4));
        assertTrue(new RegionIndex(List.of(l)).at("w", 15, 2, 2).isPresent());
        assertTrue(new RegionIndex(List.of(l)).at("w", 15, 2, 8).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class) public void overlappingRegionsAreRejected() {
        new RegionIndex(List.of(region("a", "w", new RegionBox(0, 0, 0, 9, 9, 9)), region("b", "w", new RegionBox(5, 0, 0, 14, 9, 9))));
    }

    @Test public void sameSpaceInAnotherWorldIsFine() {
        new RegionIndex(List.of(region("a", "w", new RegionBox(0, 0, 0, 9, 9, 9)), region("b", "x", new RegionBox(0, 0, 0, 9, 9, 9))));
    }

    @Test(expected = IllegalArgumentException.class) public void duplicateNamesAreRejectedIgnoringCase() {
        new RegionIndex(List.of(region("Town", "w", new RegionBox(0, 0, 0, 1, 1, 1)), region("town", "x", new RegionBox(0, 0, 0, 1, 1, 1))));
    }

    @Test public void displayNameFallsBackToTheDefaultName() {
        var r = region("town", "w", new RegionBox(0, 0, 0, 1, 1, 1));
        assertEquals("town!", r.displayName("en-US"));
        assertEquals("town", r.displayName("zh-TW"));
    }
}
