package com.eternax.db.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AliasFor;

/**
 * Registers a service's repositories only in that service. Every service gets the one eternax-db
 * jar, so without this each would also load every other service's queries.
 *
 * <p>The value is the service's database from {@code eternax.db.databases} (for example {@code
 * ingestion}), which {@link DatabaseSettingsLoader} sets as {@code eternax.db.name} at startup.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@ConditionalOnProperty(name = "eternax.db.name")
public @interface ConditionalOnServiceDatabase {

    /** The database this configuration belongs to, as listed in {@code eternax.db.databases}. */
    @AliasFor(annotation = ConditionalOnProperty.class, attribute = "havingValue")
    String value();
}
