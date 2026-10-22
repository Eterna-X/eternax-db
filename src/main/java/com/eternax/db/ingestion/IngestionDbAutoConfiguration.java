package com.eternax.db.ingestion;

import com.eternax.db.common.ConditionalOnServiceDatabase;
import com.eternax.db.ingestion.batch.BatchRepository;
import com.eternax.db.ingestion.source.SourceConfigRepository;
import com.eternax.db.ingestion.source.SourceStatusRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Registers ingestion's repositories in ingestion-service only (database {@code ingestion}). The
 * connection they use comes from the shared settings in {@code com.eternax.db.common}.
 */
@AutoConfiguration(after = JdbcClientAutoConfiguration.class)
@ConditionalOnServiceDatabase("ingestion")
public class IngestionDbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SourceConfigRepository sourceConfigRepository(JdbcClient jdbc) {
        return new SourceConfigRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    SourceStatusRepository sourceStatusRepository(JdbcClient jdbc) {
        return new SourceStatusRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    BatchRepository batchRepository(JdbcClient jdbc) {
        return new BatchRepository(jdbc);
    }
}
