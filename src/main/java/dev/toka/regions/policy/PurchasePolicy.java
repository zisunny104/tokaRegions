package dev.toka.regions.policy;

import dev.toka.regions.model.Region;
import dev.toka.regions.model.TaxTier;
import java.util.Optional;

/** Region rules for buying land: where it may be done, flat or volume pricing, and the discount and tax factors. */
public final class PurchasePolicy {
    private PurchasePolicy() {}

    public record Selection(boolean allowed, boolean flat) {}

    /** Who is buying: how many claims they own, whether they are affiliated with the region, and their permissions and personal adjustment. */
    public record Buyer(int ownedCount, boolean affiliated, boolean taxExempt, boolean taxReduced, double rateAdjustment) {}

    /** Both factors multiply the base price; the tax factor is 1 plus the rate, so 1 (no tax) for an exempt buyer. */
    // The land price (base price times the offer) and the tax are separate: exemption and relief touch only the tax, the offer only the price.
    public record Adjustment(double offer, double tax) {}

    /** Selecting land outside any region, or in a region that forbids purchases, is refused. */
    public static Selection select(Optional<Region> region) {
        return region.filter(r -> r.land().purchaseAllowed()).map(r -> new Selection(true, r.land().flatPricing())).orElse(new Selection(false, false));
    }

    public static Adjustment adjust(Region region, boolean flat, Buyer buyer) {
        double offer = buyer.affiliated() ? region.land().affiliatedOffer() : 1.0;
        if (buyer.taxExempt()) return new Adjustment(offer, 1.0);
        double rate = 0.0;
        if (flat) {
            // Only flat land is taxed. The highest tier the buyer has passed decides the rate.
            TaxTier reached = null;
            for (TaxTier tier : region.land().taxTiers()) if (buyer.ownedCount() > tier.moreThan()) reached = tier;
            if (reached != null) rate = reached.rateFor(buyer.affiliated());
            if (buyer.taxReduced()) rate *= 0.5;
            rate = Math.max(0.0, rate + buyer.rateAdjustment());
        }
        return new Adjustment(offer, 1.0 + rate);
    }
}
