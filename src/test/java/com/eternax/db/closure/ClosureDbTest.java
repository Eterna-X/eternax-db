package com.eternax.db.closure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eternax.db.closure.model.CheckResult;
import com.eternax.db.closure.model.ClosurePeriod;
import com.eternax.db.closure.model.ClosureState;
import com.eternax.db.closure.store.ClosureRepository;
import com.eternax.recon.exception.OptimisticLockConflictException;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/** The module on its own: its settings load, Flyway builds its tables, its queries run. */
@SpringBootTest(
        classes = ClosureDbTest.App.class,
        properties = "spring.application.name=closure-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class ClosureDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Autowired ClosureRepository closures;

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");

    private static ClosurePeriod open(String id, String tenant) {
        return new ClosurePeriod(
                id,
                tenant,
                "UPI",
                "2026-10-01/" + id,
                FROM,
                TO,
                ClosureState.OPEN,
                100,
                "maker",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0);
    }

    @Test
    void aPeriodRoundTripsAndAStaleUpdateIsRejected() {
        closures.insert(open("p1", "t1"));
        ClosurePeriod stored = closures.get("t1", "p1");
        assertThat(stored.state()).isEqualTo(ClosureState.OPEN);

        ClosurePeriod moved =
                new ClosurePeriod(
                        "p1",
                        "t1",
                        "UPI",
                        stored.periodKey(),
                        FROM,
                        TO,
                        ClosureState.IN_PROGRESS,
                        100,
                        "maker",
                        Optional.of("maker"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        stored.version());
        closures.update(moved);

        assertThat(closures.get("t1", "p1").state()).isEqualTo(ClosureState.IN_PROGRESS);
        assertThatThrownBy(() -> closures.update(moved))
                .isInstanceOf(OptimisticLockConflictException.class);
    }

    @Test
    void checksAreReplacedAsAWhole() {
        closures.insert(open("p2", "t1"));
        closures.replaceChecks("p2", List.of(new CheckResult("a", false, "x")), FROM);
        closures.replaceChecks(
                "p2",
                List.of(new CheckResult("a", true, "ok"), new CheckResult("b", true, "ok")),
                TO);

        assertThat(closures.checks("p2"))
                .extracting(CheckResult::passed)
                .containsExactly(true, true);
    }

    @Test
    void onlyAClosedPeriodLocksItsWindow() {
        closures.insert(open("p3", "t2"));
        Instant inside = FROM.plusSeconds(60);
        assertThat(closures.isLocked("t2", "UPI", inside)).isFalse();

        ClosurePeriod p = closures.get("t2", "p3");
        closures.update(
                new ClosurePeriod(
                        "p3",
                        "t2",
                        "UPI",
                        p.periodKey(),
                        FROM,
                        TO,
                        ClosureState.CLOSED,
                        100,
                        "maker",
                        Optional.of("maker"),
                        Optional.of("a1"),
                        Optional.of("a2"),
                        Optional.of(TO),
                        Optional.empty(),
                        p.version()));

        assertThat(closures.isLocked("t2", "UPI", inside)).isTrue();
        assertThat(closures.isLocked("t2", "UPI", TO)).isFalse();
        assertThat(closures.isLocked("other-bank", "UPI", inside)).isFalse();
    }
}
