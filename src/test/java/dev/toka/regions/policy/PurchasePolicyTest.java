package dev.toka.regions.policy;

import dev.toka.regions.model.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PurchasePolicyTest {
    /** The legacy flat-land tax table and the 0.73 village discount. */
    private static final LandRules LEGACY = new LandRules(true, true, 0.73, List.of(
            new TaxTier(5, 0.31, null), new TaxTier(7, 0.52, null), new TaxTier(13, 0.93, null), new TaxTier(20, 0.93, 17.73)));

    private static Region region(LandRules rules) {
        return new Region(UUID.randomUUID(), "town", Map.of(), "w", List.of(new RegionBox(0, 0, 0, 99, 255, 99)), Region.Protection.OPEN, rules, null);
    }

    private static PurchasePolicy.Buyer buyer(int owned, boolean affiliated) { return new PurchasePolicy.Buyer(owned, affiliated, false, false, 0); }

    @Test public void tiersFollowTheLegacyThresholds() {
        var town = region(LEGACY);
        assertEquals(1.0, PurchasePolicy.adjust(town, true, buyer(5, false)).tax(), 1e-9);
        assertEquals(1.31, PurchasePolicy.adjust(town, true, buyer(6, false)).tax(), 1e-9);
        assertEquals(1.52, PurchasePolicy.adjust(town, true, buyer(8, false)).tax(), 1e-9);
        assertEquals(1.93, PurchasePolicy.adjust(town, true, buyer(14, false)).tax(), 1e-9);
    }

    @Test public void outsidersPayTheHighestTierOnlyAbove20() {
        var town = region(LEGACY);
        assertEquals(18.73, PurchasePolicy.adjust(town, true, buyer(21, false)).tax(), 1e-9);
        assertEquals(1.93, PurchasePolicy.adjust(town, true, buyer(21, true)).tax(), 1e-9);
    }

    @Test public void volumeLandIsNeverTaxed() {
        assertEquals(1.0, PurchasePolicy.adjust(region(LEGACY), false, buyer(30, false)).tax(), 1e-9);
    }

    @Test public void affiliationGivesTheDiscountOnlyToAffiliatedBuyers() {
        var town = region(LEGACY);
        assertEquals(0.73, PurchasePolicy.adjust(town, true, buyer(0, true)).offer(), 1e-9);
        assertEquals(1.0, PurchasePolicy.adjust(town, true, buyer(0, false)).offer(), 1e-9);
    }

    @Test public void exemptBuyersPayNothingAndReducedBuyersPayHalfTheRate() {
        var town = region(LEGACY);
        assertEquals(0.0, PurchasePolicy.adjust(town, true, new PurchasePolicy.Buyer(30, false, true, false, 0)).tax(), 1e-9);
        assertEquals(1.155, PurchasePolicy.adjust(town, true, new PurchasePolicy.Buyer(6, false, false, true, 0)).tax(), 1e-9);
    }

    @Test public void personalAdjustmentAddsOrRelievesAndNeverGoesBelowNoTax() {
        var town = region(LEGACY);
        assertEquals(1.51, PurchasePolicy.adjust(town, true, new PurchasePolicy.Buyer(6, false, false, false, 0.2)).tax(), 1e-9);
        assertEquals(1.0, PurchasePolicy.adjust(town, true, new PurchasePolicy.Buyer(6, false, false, false, -5)).tax(), 1e-9);
    }

    @Test public void selectionNeedsARegionThatAllowsPurchases() {
        assertFalse(PurchasePolicy.select(Optional.empty()).allowed());
        assertFalse(PurchasePolicy.select(Optional.of(region(LandRules.closed()))).allowed());
        var flat = PurchasePolicy.select(Optional.of(region(LEGACY)));
        assertTrue(flat.allowed());
        assertTrue(flat.flat());
    }
}
