package com.eternax.db.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternax.db.orchestration.definition.DefinitionRepository;
import com.eternax.db.orchestration.run.RunRepository;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * The module on its own: its settings load, Flyway builds its tables, its queries run. The full
 * definition and run lifecycle is covered by orchestration-service's integration tests.
 */
@SpringBootTest(
        classes = OrchestrationDbTest.App.class,
        properties = "spring.application.name=orchestration-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class OrchestrationDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Autowired DefinitionRepository definitions;
    @Autowired RunRepository runs;

    @Test
    void aNewTenantHasNoDefinitionsOrRuns() {
        assertThat(definitions.list("t1")).isEmpty();
        assertThat(definitions.definitionIdByName("t1", "upi-daily")).isEmpty();
        assertThat(definitions.nextVersion("unknown")).isEqualTo(1);
        assertThat(runs.recent("t1", 10)).isEmpty();
        assertThat(runs.checkpoint("t1", "missing")).isEmpty();
    }
}
