package com.eternax.db.normalizer;

import com.eternax.db.common.ConditionalOnServiceDatabase;
import com.eternax.db.normalizer.deadletter.DltReplayRepository;
import com.eternax.db.normalizer.mapping.MappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Registers normalizer's repositories in normalizer-service only (database {@code normalizer}). The
 * connection they use comes from the shared settings in {@code com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("normalizer")
public class NormalizerDbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MappingRepository mappingRepository(JdbcClient jdbc, ObjectMapper mapper) {
        return new MappingRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    DltReplayRepository dltReplayRepository(JdbcClient jdbc) {
        return new DltReplayRepository(jdbc);
    }
}
