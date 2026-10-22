package dev.toka.regions.model;

import java.util.Comparator;
import java.util.List;

/** What buying land in a region looks like: whether it is allowed, how it is priced, and the affiliation discount and tax. */
public record LandRules(boolean purchaseAllowed, boolean flatPricing, double affiliatedOffer, List<TaxTier> taxTiers) {
    public LandRules {
        if (!Double.isFinite(affiliatedOffer) || affiliatedOffer < 0 || affiliatedOffer > 1) throw new IllegalArgumentException("affiliatedOffer must be between 0 and 1");
        taxTiers = taxTiers == null ? List.of() : taxTiers.stream().sorted(Comparator.comparingInt(TaxTier::moreThan)).toList();
    }

    /** No purchases, volume pricing, no discount, no tax. */
    public static LandRules closed() { return new LandRules(false, false, 1.0, List.of()); }
}
