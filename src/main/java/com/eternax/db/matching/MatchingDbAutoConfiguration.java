package com.eternax.db.matching;

import com.eternax.db.common.ConditionalOnServiceDatabase;
import com.eternax.db.matching.rules.DefinitionSnapshotRepository;
import com.eternax.db.matching.settlement.SettlementRepository;
import com.eternax.db.matching.store.MatchingRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers matching's repositories in matching-worker only (database {@code matching}). The
 * connection they use comes from the shared settings in {@code com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("matching")
@Import({MatchingRepository.class, SettlementRepository.class, DefinitionSnapshotRepository.class})
public class MatchingDbAutoConfiguration {}
