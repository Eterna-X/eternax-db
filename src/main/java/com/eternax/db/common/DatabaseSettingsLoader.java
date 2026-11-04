package com.eternax.db.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
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
 * it. The user name, password and any other secret are not in the file: it names the variables
 * only.
 *
 * <p>For a developer machine the variables can sit in a {@code .env} file (KEY=VALUE lines) in the
 * service's working directory, or in the file named by {@code eternax.db.env-file}. Real
 * environment variables win over it, and it wins over the defaults in {@code database.yml}. The
 * file is git-ignored; {@code .env.example} lists the keys.
 */
public class DatabaseSettingsLoader implements EnvironmentPostProcessor, Ordered {

    static final String SETTINGS = "eternax-db/database.yml";
    static final String SOURCE_NAME = "eternax-db";
    static final String DOTENV_SOURCE_NAME = "eternax-db-dotenv";
    static final String DEFAULT_ENV_FILE = ".env";

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
        readDotenv(environment.getProperty("eternax.db.env-file", DEFAULT_ENV_FILE))
                .ifPresent(
                        values ->
                                environment
                                        .getPropertySources()
                                        .addLast(
                                                new SystemEnvironmentPropertySource(
                                                        DOTENV_SOURCE_NAME, values)));
        shared.forEach(environment.getPropertySources()::addLast);
    }

    /**
     * KEY=VALUE lines; blank lines, # comments, a leading {@code export} and quotes are handled.
     */
    static java.util.Optional<Map<String, Object>> readDotenv(String file) {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            return java.util.Optional.empty();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.startsWith("export ")) {
                    line = line.substring(7).strip();
                }
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                    continue;
                }
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                                || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(line.substring(0, eq).strip(), value);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return java.util.Optional.of(values);
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
