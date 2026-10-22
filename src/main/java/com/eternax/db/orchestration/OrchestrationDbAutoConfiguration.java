package com.eternax.db.orchestration;

import com.eternax.db.common.ConditionalOnServiceDatabase;
import com.eternax.db.orchestration.definition.DefinitionRepository;
import com.eternax.db.orchestration.run.RunRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers orchestration's repositories in orchestration-service only (database {@code
 * orchestration}). The connection they use comes from the shared settings in {@code
 * com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("orchestration")
@Import({DefinitionRepository.class, RunRepository.class})
public class OrchestrationDbAutoConfiguration {}
