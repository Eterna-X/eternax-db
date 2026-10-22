package com.eternax.db.audit;

import com.eternax.db.audit.store.AuditEventRepository;
import com.eternax.db.common.ConditionalOnServiceDatabase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers audit's repositories in audit-service only (database {@code audit}). The connection
 * they use comes from the shared settings in {@code com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("audit")
@Import({AuditEventRepository.class})
public class AuditDbAutoConfiguration {}
