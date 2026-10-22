package com.eternax.db.closure;

import com.eternax.db.closure.store.ClosureRepository;
import com.eternax.db.common.ConditionalOnServiceDatabase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers closure's repositories in closure-service only (database {@code closure}). The
 * connection they use comes from the shared settings in {@code com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("closure")
@Import({ClosureRepository.class})
public class ClosureDbAutoConfiguration {}
