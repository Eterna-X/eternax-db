package com.eternax.db.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternax.db.matching.rules.DefinitionSnapshotRepository;
import com.eternax.db.matching.settlement.SettlementRepository;
import com.eternax.db.matching.store.MatchingRepository;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * The module on its own: its settings load, Flyway builds its tables, its queries run. Matching and
 * settlement queries are covered by matching-worker's integration tests.
 */
@SpringBootTest(
        classes = MatchingDbTest.App.class,
        properties = "spring.application.name=matching-worker")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class MatchingDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Autowired DefinitionSnapshotRepository snapshots;
    @Autowired MatchingRepository matching;
    @Autowired SettlementRepository settlement;

    @Test
    void theSnapshotKeepsTheNewestVersionAndIgnoresALateOlderOne() {
        snapshots.upsert("t1", "d1", 2, "UPI", "ACTIVE", "{\"v\":2}");
        snapshots.upsert("t1", "d1", 1, "UPI", "ACTIVE", "{\"v\":1}");

        assertThat(snapshots.activePayload("t1", "UPI")).contains("{\"v\":2}");
    }

    @Test
    void onlyAnActiveDefinitionIsUsedAndOnlyForItsTenantAndRail() {
        snapshots.upsert("t2", "d2", 1, "UPI", "RETIRED", "{}");

        assertThat(snapshots.activePayload("t2", "UPI")).isEmpty();
        assertThat(snapshots.activePayload("t2", "IMPS")).isEmpty();
        assertThat(snapshots.activePayload("other-bank", "UPI")).isEmpty();
    }

    @Test
    void allRepositoriesAreRegistered() {
        assertThat(matching).isNotNull();
        assertThat(settlement).isNotNull();
    }
}
