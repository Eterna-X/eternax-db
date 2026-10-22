package com.eternax.db.adjustment;

import com.eternax.db.adjustment.policy.ApprovalPolicyRepository;
import com.eternax.db.adjustment.store.AdjustmentRepository;
import com.eternax.db.common.ConditionalOnServiceDatabase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers adjustment's repositories in adjustment-service only (database {@code adjustments}).
 * The connection they use comes from the shared settings in {@code com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("adjustments")
@Import({AdjustmentRepository.class, ApprovalPolicyRepository.class})
public class AdjustmentDbAutoConfiguration {}
