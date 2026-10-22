package com.eternax.db.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternax.db.audit.store.AuditEventRepository;
import com.eternax.db.audit.store.AuditEventRepository.ChainHead;
import com.eternax.db.audit.store.AuditRecord;
import com.eternax.db.audit.store.AuditSearchCriteria;
import com.eternax.recon.events.AuditEventMessage;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

/** The module on its own: its settings load, Flyway builds its tables, its queries run. */
@SpringBootTest(
        classes = AuditDbTest.App.class,
        properties = "spring.application.name=audit-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class AuditDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    private static final String GENESIS = "0".repeat(64);

    @Autowired AuditEventRepository events;
    @Autowired TransactionTemplate tx;

    private static AuditEventMessage event(String tenant, String action) {
        return new AuditEventMessage(
                tenant, "alice", action, "Case", "c1", null, "{}", "corr", "test", Instant.now());
    }

    private void append(String tenant, String action, String hash) {
        tx.executeWithoutResult(
                s -> {
                    ChainHead head = events.lockChainHead(tenant, GENESIS);
                    events.append(
                            event(tenant, action),
                            head.lastSeq() + 1,
                            "fp-" + hash,
                            head.lastHash(),
                            hash);
                });
    }

    @Test
    void appendingMovesTheChainHeadAndLinksEachEventToThePrevious() {
        append("t1", "CREATED", "h1");
        append("t1", "CHANGED", "h2");

        assertThat(events.chainHead("t1")).contains(new ChainHead(2, "h2"));
        assertThat(events.page("t1", 0, 10))
                .extracting(AuditRecord::previousHash)
                .containsExactly(GENESIS, "h1");
        assertThat(events.fingerprint("t1", 2)).isEqualTo("fp-h2");
        assertThat(events.existsFingerprint("t1", "fp-h1")).isTrue();
        assertThat(events.existsFingerprint("other-bank", "fp-h1")).isFalse();
    }

    @Test
    void searchFiltersAndPagesByTenant() {
        append("t2", "CREATED", "a");
        append("t2", "CHANGED", "b");
        append("other-bank", "CHANGED", "c");

        AuditSearchCriteria changed =
                new AuditSearchCriteria(
                        Optional.empty(),
                        Optional.of("CHANGED"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        10);
        assertThat(events.search("t2", changed))
                .extracting(AuditRecord::eventHash)
                .containsExactly("b");
        assertThat(events.tenants()).contains("t2", "other-bank");
        assertThat(events.retentionStats("t2", Instant.now().plusSeconds(60)).total()).isEqualTo(2);
    }
}
