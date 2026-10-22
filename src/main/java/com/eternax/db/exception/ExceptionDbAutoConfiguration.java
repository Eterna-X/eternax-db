package com.eternax.db.exception;

import com.eternax.db.common.ConditionalOnServiceDatabase;
import com.eternax.db.exception.store.CaseRepository;
import com.eternax.db.exception.tat.TatClockRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers exception's repositories in exception-service only (database {@code exceptions}). The
 * connection they use comes from the shared settings in {@code com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("exceptions")
@Import({CaseRepository.class, TatClockRepository.class})
public class ExceptionDbAutoConfiguration {}
