package dev.recon.common.sim;

import dev.recon.common.Money;
import dev.recon.common.csv.BankStatementRow;
import dev.recon.common.csv.GatewaySettlementRow;
import dev.recon.common.event.GatewayCallback;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Deterministic generator of the simulated world. Each business date is generated from its own RNG
 * stream derived from (seed, epochDay), so a day can be produced independently and in O(N) memory;
 * the gateway simulator and the bank simulator each regenerate the days they need and always agree.
 *
 * <p>Every payment draws the same random values in the same order whatever its fault, so changing a
 * fault rate changes which payments are faulty but not their amounts, merchants or timestamps.
 */
public final class WorldGenerator {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final FaultType[] PER_PAYMENT_FAULTS = {
            FaultType.DUPLICATE_CALLBACK, FaultType.MISSING_CALLBACK, FaultType.TIMEOUT_THEN_SUCCESS,
            FaultType.DELAYED_SETTLEMENT, FaultType.AMOUNT_MISMATCH_FEE, FaultType.AMOUNT_MISMATCH_REAL,
            FaultType.MISSING_BANK_RECORD, FaultType.DUPLICATE_BANK_CREDIT, FaultType.MISSING_GATEWAY_RECORD,
            FaultType.BANK_UTR_MISSING};

    private final SimulationConfig config;
    private final double[] cumulative;

    public WorldGenerator(SimulationConfig config) {
        this.config = config;
        this.cumulative = new double[PER_PAYMENT_FAULTS.length];
        double acc = 0;
        for (int i = 0; i < PER_PAYMENT_FAULTS.length; i++) {
            acc += config.rate(PER_PAYMENT_FAULTS[i]);
            cumulative[i] = acc;
        }
    }

    public SimulationConfig config() {
        return config;
    }

    /** Everything the simulated world produces for one business date. */
    public record DaySlice(
            LocalDate businessDate,
            List<GatewayCallback> callbacks,
            List<GatewaySettlementRow> gatewayRows,
            List<BankStatementRow> bankRows,
            List<GroundTruthRecord> groundTruth) {
    }

    public DaySlice generateDay(LocalDate date) {
        int n = config.paymentsPerDay();
        List<GatewayCallback> callbacks = new ArrayList<>(n + n / 50);
        List<GatewayCallback> late = new ArrayList<>();
        List<GatewaySettlementRow> gatewayRows = new ArrayList<>(n);
        List<BankStatementRow> bankRows = new ArrayList<>(n + n / 50);
        List<GroundTruthRecord> truth = new ArrayList<>();
        SplittableRandom rng = new SplittableRandom(mix(config.seed(), date.toEpochDay()));
        String ymd = date.format(BASIC);
        long dayStart = date.atStartOfDay(ZONE).toInstant().toEpochMilli();

        for (int k = 0; k < n; k++) {
            // Fixed draw order, independent of the chosen fault.
            int merchantIdx = rng.nextInt(config.merchants());
            long amount = 1_000 + rng.nextLong(999_001);
            long timeMs = rng.nextLong(86_400_000L);
            double u = rng.nextDouble();
            int normalDelay = 1 + rng.nextInt(SimulationConfig.NORMAL_MAX_SETTLEMENT_DAYS);
            long p1 = rng.nextLong(1L << 40);
            boolean negative = rng.nextBoolean();

            FaultType fault = pick(u);
            String merchant = merchantId(merchantIdx);
            String paymentId = "PAY" + ymd + "-" + String.format("%06d", k);
            String gatewayTxnId = "GTX" + opaque(config.seed(), "gtx:" + paymentId);
            String utr = "UTR" + opaque(config.seed(), "utr:" + paymentId);
            Instant createdAt = Instant.ofEpochMilli(dayStart + timeMs);

            // Ledger side: what the gateway tells the merchant through callbacks.
            GatewayCallback success = new GatewayCallback(eventId(paymentId, GatewayCallback.STATUS_SUCCESS),
                    paymentId, gatewayTxnId, merchant, date, GatewayCallback.STATUS_SUCCESS, amount, Money.INR,
                    createdAt.plusSeconds(2));
            switch (fault) {
                case MISSING_CALLBACK -> { /* gateway captured the payment, merchant never hears */ }
                case DUPLICATE_CALLBACK -> {
                    int copies = 2 + (int) (p1 % 2);
                    for (int c = 0; c < copies; c++) {
                        callbacks.add(success);
                    }
                }
                case TIMEOUT_THEN_SUCCESS -> {
                    Instant successAt = createdAt.plusSeconds(60L * (15 + p1 % 46));
                    GatewayCallback pending = new GatewayCallback(eventId(paymentId, GatewayCallback.STATUS_PENDING),
                            paymentId, gatewayTxnId, merchant, date, GatewayCallback.STATUS_PENDING, amount, Money.INR,
                            createdAt.plusSeconds(30));
                    // Both arrive at the end of the day's stream, newest first.
                    late.add(new GatewayCallback(success.eventId(), paymentId, gatewayTxnId, merchant, date,
                            GatewayCallback.STATUS_SUCCESS, amount, Money.INR, successAt));
                    late.add(pending);
                }
                default -> callbacks.add(success);
            }

            // Gateway settlement file.
            long gross = amount;
            if (fault == FaultType.AMOUNT_MISMATCH_REAL) {
                long delta = SimulationConfig.REAL_MISMATCH_MIN_MINOR + p1 % 50_000;
                gross = (negative && amount - delta > 0) ? amount - delta : amount + delta;
            }
            long fee = Money.feeBps(gross, config.feeBps());
            long net = gross - fee;
            if (fault != FaultType.MISSING_GATEWAY_RECORD) {
                gatewayRows.add(new GatewaySettlementRow(gatewayTxnId, paymentId, merchant, date, date.plusDays(1),
                        gross, fee, net, Money.INR, utr));
            }

            // Bank statement.
            String account = BankStatementRow.accountFor(merchant);
            LocalDate valueDate = date.plusDays(normalDelay);
            switch (fault) {
                case MISSING_BANK_RECORD, MISSING_GATEWAY_RECORD -> { }
                case DELAYED_SETTLEMENT -> {
                    int span = SimulationConfig.DELAY_MAX_DAYS - SimulationConfig.DELAY_MIN_DAYS + 1;
                    LocalDate delayedDate = date.plusDays(SimulationConfig.DELAY_MIN_DAYS + p1 % span);
                    bankRows.add(bankRow(paymentId, 1, account, delayedDate, net, utr));
                }
                case AMOUNT_MISMATCH_FEE -> bankRows.add(bankRow(paymentId, 1, account, valueDate,
                        net - (1 + p1 % SimulationConfig.FEE_MISMATCH_MAX_MINOR), utr));
                case DUPLICATE_BANK_CREDIT -> {
                    bankRows.add(bankRow(paymentId, 1, account, valueDate, net, utr));
                    bankRows.add(bankRow(paymentId, 2, account, valueDate.plusDays(p1 % 2), net, utr));
                }
                case BANK_UTR_MISSING -> bankRows.add(bankRow(paymentId, 1, account, valueDate, net, null));
                default -> bankRows.add(bankRow(paymentId, 1, account, valueDate, net, utr));
            }

            if (fault != FaultType.NONE) {
                truth.add(new GroundTruthRecord("PAYMENT", paymentId, date, merchant, fault));
            }
        }
        callbacks.addAll(late);

        // Orphan credits come from a separate stream so their count does not shift payment draws.
        SplittableRandom orphanRng = new SplittableRandom(mix(config.seed() ^ 0x0FFA_11L, date.toEpochDay()));
        long orphans = Math.round(n * config.rate(FaultType.ORPHAN_BANK_CREDIT));
        for (int j = 0; j < orphans; j++) {
            String merchant = merchantId(orphanRng.nextInt(config.merchants()));
            long amount = 1_000 + orphanRng.nextLong(999_001);
            String key = "ORPH" + ymd + "-" + j;
            String utr = "UTR" + opaque(config.seed(), "orphan-utr:" + key);
            BankStatementRow row = bankRow(key, 1, BankStatementRow.accountFor(merchant), date.plusDays(1), amount, utr);
            bankRows.add(row);
            truth.add(new GroundTruthRecord("BANK_CREDIT", row.bankRef(), date.plusDays(1), merchant,
                    FaultType.ORPHAN_BANK_CREDIT));
        }
        return new DaySlice(date, callbacks, gatewayRows, bankRows, truth);
    }

    private BankStatementRow bankRow(String sourceKey, int copy, String account, LocalDate valueDate,
                                     long amount, String utr) {
        String bankRef = "BNK" + opaque(config.seed(), "bank:" + sourceKey + ":" + copy);
        String narration = utr == null ? "NEFT CR SIMGATEWAY SETTLEMENT" : "NEFT CR SIMGATEWAY SETTLEMENT " + utr;
        return new BankStatementRow(bankRef, account, valueDate, amount, Money.INR, utr, narration);
    }

    private FaultType pick(double u) {
        for (int i = 0; i < cumulative.length; i++) {
            if (u < cumulative[i]) {
                return PER_PAYMENT_FAULTS[i];
            }
        }
        return FaultType.NONE;
    }

    private UUID eventId(String paymentId, String status) {
        return UUID.nameUUIDFromBytes(("evt:" + config.seed() + ":" + paymentId + ":" + status)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static String merchantId(int idx) {
        return String.format("M%03d", idx + 1);
    }

    /** 13-character base-36 token derived from (seed, salt); stable and opaque. */
    static String opaque(long seed, String salt) {
        long h = 0xcbf29ce484222325L ^ seed;
        for (byte b : salt.getBytes(StandardCharsets.UTF_8)) {
            h ^= b;
            h *= 0x100000001b3L;
        }
        String s = Long.toUnsignedString(splitmix(h), 36).toUpperCase();
        return "0".repeat(13 - s.length()) + s;
    }

    static long mix(long seed, long salt) {
        return splitmix(seed * 0x9E3779B97F4A7C15L + salt);
    }

    private static long splitmix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
