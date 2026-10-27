package dev.recon.service.engine;

import dev.recon.service.config.ReconProperties;

/** The subset of configuration the classifier depends on. */
public record ReconPolicy(long toleranceMinor, int settlementDays, int graceDays, int fallbackWindowDays) {

    public static ReconPolicy from(ReconProperties p) {
        return new ReconPolicy(p.toleranceMinor(), p.settlementDays(), p.graceDays(), p.fallbackWindowDays());
    }

    public static ReconPolicy defaults() {
        return new ReconPolicy(100, 2, 5, 7);
    }
}
