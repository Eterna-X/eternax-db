package com.eternax.db.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DatabaseSettingsLoaderTest {

    private final DatabaseSettingsLoader loader = new DatabaseSettingsLoader();

    private static StandardEnvironment service(String name, Map<String, Object> more) {
        Map<String, Object> props = new HashMap<>(more);
        props.put("spring.application.name", name);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("service", props));
        return env;
    }

    private StandardEnvironment loaded(String name, Map<String, Object> more) {
        StandardEnvironment env = service(name, more);
        loader.postProcessEnvironment(env, new SpringApplication());
        return env;
    }

    @Test
    void eachServiceGetsItsOwnDatabase() {
        assertThat(loaded("ingestion-service", Map.of()).getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/ingestion");
        assertThat(loaded("exception-service", Map.of()).getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/exceptions");
        assertThat(loaded("matching-worker", Map.of()).getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/matching");
    }

    @Test
    void everyServiceInTheFileResolves() {
        Map<String, String> expected =
                Map.of(
                        "ingestion-service", "ingestion",
                        "normalizer-service", "normalizer",
                        "matching-worker", "matching",
                        "orchestration-service", "orchestration",
                        "exception-service", "exceptions",
                        "adjustment-service", "adjustments",
                        "audit-service", "audit",
                        "closure-service", "closure",
                        "auth-service", "auth");
        expected.forEach(
                (service, database) ->
                        assertThat(loaded(service, Map.of()).getProperty("eternax.db.name"))
                                .as(service)
                                .isEqualTo(database));
    }

    @Test
    void flywayReadsTheServicesOwnMigrationsAndThePlatforms() {
        assertThat(loaded("ingestion-service", Map.of()).getProperty("spring.flyway.locations"))
                .isEqualTo("classpath:db/ingestion,classpath:db/platform");
    }

    @Test
    void theEnvironmentWins() {
        StandardEnvironment env =
                loaded(
                        "ingestion-service",
                        Map.of(
                                "DB_URL", "jdbc:postgresql://postgres:5432/ingestion",
                                "DB_PASSWORD", "from-secret"));

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://postgres:5432/ingestion");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("from-secret");
    }

    @Test
    void aSettingTheServiceMakesItselfWins() {
        StandardEnvironment env =
                loaded(
                        "ingestion-service",
                        Map.of("spring.datasource.hikari.maximum-pool-size", 5));

        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("5");
    }

    @Test
    void runningTwiceAddsNothing() {
        StandardEnvironment env = loaded("ingestion-service", Map.of());
        int after = env.getPropertySources().size();
        loader.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources().size()).isEqualTo(after);
    }

    @Test
    void anUnknownServiceFailsAtStartupNamingWhereToAddIt() {
        StandardEnvironment env = service("unknown-service", Map.of());

        assertThatThrownBy(() -> loader.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'unknown-service'")
                .hasMessageContaining("eternax.db.databases");
    }

    @Test
    void aServiceWithoutANameFails() {
        assertThatThrownBy(
                        () ->
                                loader.postProcessEnvironment(
                                        new StandardEnvironment(), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.application.name");
    }
}
