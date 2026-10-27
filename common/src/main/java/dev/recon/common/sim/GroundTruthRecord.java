package dev.recon.common.sim;

import java.time.LocalDate;

/** One injected fault: which entity got which fault. entityType is PAYMENT or BANK_CREDIT. */
public record GroundTruthRecord(String entityType, String entityId, LocalDate businessDate,
                                String merchantId, FaultType fault) {

    public static final String HEADER = "entity_type,entity_id,business_date,merchant_id,fault";

    public String toCsv() {
        return entityType + "," + entityId + "," + businessDate + "," + merchantId + "," + fault;
    }

    public static GroundTruthRecord parse(String line) {
        String[] p = line.split(",", -1);
        return new GroundTruthRecord(p[0], p[1], LocalDate.parse(p[2]), p[3], FaultType.valueOf(p[4]));
    }
}
