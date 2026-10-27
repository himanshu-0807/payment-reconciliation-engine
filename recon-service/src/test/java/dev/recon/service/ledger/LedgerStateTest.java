package dev.recon.service.ledger;

import dev.recon.common.event.GatewayCallback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerStateTest {

    private static final Instant T0 = Instant.parse("2026-01-05T06:00:00Z");

    private static GatewayCallback ev(String status, int minutes) {
        return new GatewayCallback(UUID.randomUUID(), "P1", "G1", "M001", LocalDate.of(2026, 1, 5), status,
                12_345, "INR", T0.plusSeconds(60L * minutes));
    }

    static Stream<Arguments> sequences() {
        return Stream.of(
                Arguments.of("single success", List.of(ev("SUCCESS", 0)), "SUCCESS", 0),
                Arguments.of("pending then success (in order)", List.of(ev("PENDING", 0), ev("SUCCESS", 5)), "SUCCESS", 0),
                Arguments.of("success then stale pending", List.of(ev("SUCCESS", 30), ev("PENDING", 0)), "SUCCESS", 1),
                Arguments.of("newer pending never regresses success", List.of(ev("SUCCESS", 0), ev("PENDING", 9)), "SUCCESS", 0),
                Arguments.of("stale success still advances pending", List.of(ev("PENDING", 10), ev("SUCCESS", 5)), "SUCCESS", 1),
                Arguments.of("pending only", List.of(ev("PENDING", 0)), "PENDING", 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sequences")
    void foldsEventsDeterministically(String name, List<GatewayCallback> events, String status, int outOfOrder) {
        LedgerState s = null;
        for (GatewayCallback e : events) {
            s = LedgerState.apply(s, e);
        }
        assertThat(s.status()).isEqualTo(status);
        assertThat(s.outOfOrderEvents()).isEqualTo(outOfOrder);
        assertThat(s.amountMinor()).isEqualTo(12_345);
    }
}
