package dev.recon.common;

/**
 * Money helpers. All amounts in this project are integer minor units (paise for INR) held in a
 * {@code long}; floating point is never used for money. Formatting is only for display.
 */
public final class Money {

    public static final String INR = "INR";

    private Money() {
    }

    /** Formats paise as rupees with two decimals, e.g. 123456 -> "1234.56", -5 -> "-0.05". */
    public static String format(long minor) {
        long abs = Math.abs(minor);
        String s = (abs / 100) + "." + String.format("%02d", abs % 100);
        return minor < 0 ? "-" + s : s;
    }

    /** Fee in basis points with half-up rounding, integer arithmetic only. */
    public static long feeBps(long grossMinor, int bps) {
        return (grossMinor * bps + 5_000) / 10_000;
    }
}
