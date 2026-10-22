package com.eternax.db.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Gives the running service its database settings (connection, pool, Flyway) from the one shared
 * {@code eternax-db/database.yml}, so a service needs no database settings of its own.
 *
 * <p>The service's {@code spring.application.name} is looked up in {@code eternax.db.databases};
 * the database found there becomes {@code eternax.db.name}, which the shared settings use in the
 * URL and the Flyway location. ingestion-service gets database {@code ingestion}.
 *
 * <p>Everything is added with the lowest precedence. Environment variables ({@code DB_URL}, {@code
 * DB_USER}, {@code DB_PASSWORD}), the service's application.yml and test initializers all override
 * it. Real secrets never live in the file: it holds variable names and local development defaults
 * only.
 */
public class DatabaseSettingsLoader implements EnvironmentPostProcessor, Ordered {

    static final String SETTINGS = "eternax-db/database.yml";
    static final String SOURCE_NAME = "eternax-db";

    private final YamlPropertySourceLoader yaml = new YamlPropertySourceLoader();

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        String service = environment.getProperty("spring.application.name");
        if (service == null || service.isBlank()) {
            throw new IllegalStateException(
                    "spring.application.name is not set, so eternax-db cannot tell which"
                            + " service's database to use");
        }
        List<PropertySource<?>> shared = load();
        Object database =
                shared.stream()
                        .map(s -> s.getProperty("eternax.db.databases." + service))
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No database for '"
                                                        + service
                                                        + "': add it under eternax.db.databases in"
                                                        + " eternax-db/src/main/resources/"
                                                        + SETTINGS));
        environment
                .getPropertySources()
                .addLast(
                        new MapPropertySource(
                                SOURCE_NAME, Map.of("eternax.db.name", database.toString())));
        shared.forEach(environment.getPropertySources()::addLast);
    }

    private List<PropertySource<?>> load() {
        Resource file = new ClassPathResource(SETTINGS);
        try {
            return yaml.load(SOURCE_NAME + ": " + SETTINGS, file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read database settings " + SETTINGS, e);
        }
    }

    /** Runs after application.yml is loaded, so {@code addLast} really is the lowest precedence. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
