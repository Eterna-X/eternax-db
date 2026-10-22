package com.eternax.db.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eternax.db.ingestion.batch.BatchRepository;
import com.eternax.db.ingestion.batch.BatchStatus;
import com.eternax.db.ingestion.source.ConnectionType;
import com.eternax.db.ingestion.source.SourceConfig;
import com.eternax.db.ingestion.source.SourceConfigRepository;
import com.eternax.db.ingestion.source.SourceStatusRepository;
import com.eternax.recon.exception.EntityNotFoundException;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/** The module on its own: its settings load, Flyway builds its tables, its queries run. */
@SpringBootTest(
        classes = IngestionDbTest.App.class,
        properties = "spring.application.name=ingestion-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class IngestionDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Autowired SourceConfigRepository sources;
    @Autowired SourceStatusRepository status;
    @Autowired BatchRepository batches;

    @Value("${spring.flyway.locations}")
    String flywayLocations;

    private static SourceConfig draft(String tenant, String source, String... required) {
        return new SourceConfig(
                tenant,
                source,
                0,
                "UPI",
                ConnectionType.MANUAL_UPLOAD,
                List.of(required),
                List.of("amount"));
    }

    @Test
    void settingsComeFromTheModule() {
        assertThat(flywayLocations).isEqualTo("classpath:db/ingestion,classpath:db/platform");
    }

    @Test
    void eachRegistrationIsTheNextVersion() {
        SourceConfig v1 = sources.insertNextVersion(draft("t1", "npci", "rrn"), "admin");
        SourceConfig v2 = sources.insertNextVersion(draft("t1", "npci", "rrn", "amount"), "admin");

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(sources.getCurrent("t1", "npci").requiredColumns())
                .containsExactly("rrn", "amount");
        assertThat(sources.history("t1", "npci"))
                .extracting(SourceConfig::version)
                .containsExactly(1, 2);
    }

    @Test
    void listCurrentReturnsOnlyTheLatestVersionOfTheTenantsOwnSources() {
        sources.insertNextVersion(draft("t2", "cbs", "rrn"), "admin");
        sources.insertNextVersion(draft("t2", "cbs", "rrn", "amount"), "admin");
        sources.insertNextVersion(draft("t2", "switch", "rrn"), "admin");
        sources.insertNextVersion(draft("other-bank", "cbs", "rrn"), "admin");

        assertThat(sources.listCurrent("t2"))
                .extracting(SourceConfig::sourceId, SourceConfig::version)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("cbs", 2),
                        org.assertj.core.groups.Tuple.tuple("switch", 1));
    }

    @Test
    void unknownSourceIsNotFound() {
        assertThatThrownBy(() -> sources.getCurrent("t3", "missing"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void sourceStatusStartsActiveAndRecordsChanges() {
        assertThat(status.get("t4", "npci").active()).isTrue();

        status.setActive("t4", "npci", false, "ops");

        assertThat(status.get("t4", "npci").active()).isFalse();
        assertThat(status.get("t4", "npci").changedBy()).isEqualTo("ops");
    }

    @Test
    void theSameFileCreatesOneBatch() {
        sources.insertNextVersion(draft("t5", "npci", "rrn"), "admin");

        boolean first =
                batches.createIfAbsent(
                        "b1", "t5", "npci", 1, "feed-1", "sum-1", "vault/b1", null, "u");
        boolean again =
                batches.createIfAbsent(
                        "b2", "t5", "npci", 1, "feed-1", "sum-1", "vault/b2", null, "u");

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        assertThat(batches.findBatchIdByFile("t5", "npci", "feed-1", "sum-1")).contains("b1");
        assertThat(batches.result("t5", "b1").status()).isEqualTo(BatchStatus.RECEIVED);
    }
}
