package com.eternax.db.normalizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternax.db.normalizer.deadletter.DltReplayRepository;
import com.eternax.db.normalizer.mapping.FieldMapping;
import com.eternax.db.normalizer.mapping.MappingDefinition;
import com.eternax.db.normalizer.mapping.MappingRepository;
import com.eternax.db.normalizer.mapping.TransformationType;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/** The module on its own: its settings load, Flyway builds its tables, its queries run. */
@SpringBootTest(
        classes = NormalizerDbTest.App.class,
        properties = "spring.application.name=normalizer-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class NormalizerDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Autowired MappingRepository mappings;
    @Autowired DltReplayRepository replays;

    private static final List<FieldMapping> FIELDS =
            List.of(
                    new FieldMapping("rrn", "rrn", TransformationType.REFERENCE, null, null),
                    new FieldMapping(
                            "amountMinor",
                            "amount",
                            TransformationType.AMOUNT_TO_MINOR,
                            null,
                            null));

    @Test
    void draftsGetTheNextVersionAndStartInactive() {
        MappingDefinition v1 = mappings.insertNextDraft("t1", "npci", "UPI", FIELDS, "alice");
        MappingDefinition v2 = mappings.insertNextDraft("t1", "npci", "UPI", FIELDS, "alice");

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(mappings.findActive("t1", "npci")).isEmpty();
        assertThat(mappings.find("t1", "npci", 2))
                .get()
                .extracting(MappingDefinition::active)
                .isEqualTo(false);
    }

    @Test
    void onlyTheActivatedVersionIsActiveAndItsFieldsRoundTrip() {
        mappings.insertNextDraft("t2", "cbs", "UPI", FIELDS, "alice");
        mappings.insertNextDraft("t2", "cbs", "UPI", FIELDS, "alice");

        mappings.makeActive("t2", "cbs", 1);
        mappings.makeActive("t2", "cbs", 2);

        MappingDefinition active = mappings.findActive("t2", "cbs").orElseThrow();
        assertThat(active.version()).isEqualTo(2);
        assertThat(active.fieldMappings()).isEqualTo(FIELDS);
        assertThat(mappings.history("t2", "cbs"))
                .filteredOn(m -> m.tenantId().equals("t2"))
                .extracting(MappingDefinition::active)
                .containsExactly(false, true);
    }

    @Test
    void platformDefaultsFromTheMigrationsAreThere() {
        assertThat(mappings.findActive("*", "bank_switch")).isPresent();
    }

    @Test
    void aReplayIsRecordedOnce() {
        assertThat(replays.wasReplayed("dlt", 0, 42)).isFalse();

        replays.recordReplay("dlt", 0, 42, "ops");
        replays.recordReplay("dlt", 0, 42, "ops");

        assertThat(replays.wasReplayed("dlt", 0, 42)).isTrue();
        assertThat(replays.wasReplayed("dlt", 1, 42)).isFalse();
    }
}
