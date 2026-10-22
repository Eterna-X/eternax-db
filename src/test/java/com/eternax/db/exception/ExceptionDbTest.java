package com.eternax.db.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternax.db.exception.store.CaseRepository;
import com.eternax.db.exception.tat.TatClockRepository;
import com.eternax.db.exception.tat.TatRailClock;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;

/** The module on its own: its settings load, Flyway builds its tables, its queries run. */
@SpringBootTest(
        classes = ExceptionDbTest.App.class,
        properties = "spring.application.name=exception-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class ExceptionDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Autowired CaseRepository cases;
    @Autowired TatClockRepository clocks;
    @Autowired JdbcClient jdbc;

    @Test
    void aTenantsOwnClockWinsOverThePlatformDefault() {
        String rail =
                jdbc.sql("SELECT rail FROM tat_rail_clock WHERE tenant_id = '*' LIMIT 1")
                        .query(String.class)
                        .single();
        TatRailClock platform = clocks.find("t1", rail).orElseThrow();

        jdbc.sql(
                        "INSERT INTO tat_rail_clock (tenant_id, rail, deadline_days,"
                                + " use_working_days, compensation_per_day_minor) VALUES"
                                + " ('t1', :r, 99, false, 0)")
                .param("r", rail)
                .update();

        assertThat(clocks.find("t1", rail).orElseThrow().deadlineDays()).isEqualTo(99);
        assertThat(clocks.find("other-bank", rail).orElseThrow()).isEqualTo(platform);
    }

    @Test
    void anUnknownCaseIsNotFound() {
        assertThat(cases.find("t2", "missing")).isEmpty();
        assertThat(cases.comments("t2", "missing")).isEmpty();
    }
}
