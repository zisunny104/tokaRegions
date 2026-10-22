package dev.toka.regions.model;

/**
 * Extra rate on a flat-land purchase once the buyer already owns more than {@code moreThan} claims.
 * {@code outsiderRate} applies to buyers not affiliated with the region; null means the same rate for everyone.
 */
public record TaxTier(int moreThan, double rate, Double outsiderRate) {
    public TaxTier {
        if (moreThan < 0) throw new IllegalArgumentException("moreThan must not be negative");
        if (!Double.isFinite(rate) || rate < 0) throw new IllegalArgumentException("rate must be finite and not negative");
        if (outsiderRate != null && (!Double.isFinite(outsiderRate) || outsiderRate < 0)) throw new IllegalArgumentException("outsiderRate must be finite and not negative");
    }

    public double rateFor(boolean affiliated) { return affiliated || outsiderRate == null ? rate : outsiderRate; }
}
