package com.eternax.db.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternax.db.adjustment.AdjustmentDbAutoConfiguration;
import com.eternax.db.adjustment.policy.ApprovalPolicyRepository;
import com.eternax.db.adjustment.store.AdjustmentRepository;
import com.eternax.db.audit.AuditDbAutoConfiguration;
import com.eternax.db.audit.store.AuditEventRepository;
import com.eternax.db.auth.AuthDbAutoConfiguration;
import com.eternax.db.auth.DefaultTenantPolicy;
import com.eternax.db.auth.IamStore;
import com.eternax.db.closure.ClosureDbAutoConfiguration;
import com.eternax.db.closure.store.ClosureRepository;
import com.eternax.db.exception.ExceptionDbAutoConfiguration;
import com.eternax.db.exception.store.CaseRepository;
import com.eternax.db.exception.tat.TatClockRepository;
import com.eternax.db.ingestion.IngestionDbAutoConfiguration;
import com.eternax.db.ingestion.batch.BatchRepository;
import com.eternax.db.ingestion.source.SourceConfigRepository;
import com.eternax.db.ingestion.source.SourceStatusRepository;
import com.eternax.db.matching.MatchingDbAutoConfiguration;
import com.eternax.db.matching.rules.DefinitionSnapshotRepository;
import com.eternax.db.matching.settlement.SettlementRepository;
import com.eternax.db.matching.store.MatchingRepository;
import com.eternax.db.normalizer.NormalizerDbAutoConfiguration;
import com.eternax.db.normalizer.deadletter.DltReplayRepository;
import com.eternax.db.normalizer.mapping.MappingRepository;
import com.eternax.db.orchestration.OrchestrationDbAutoConfiguration;
import com.eternax.db.orchestration.definition.DefinitionRepository;
import com.eternax.db.orchestration.run.RunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Every service gets the one jar, but only its own repositories: never another service's. */
class ServiceDatabaseScopeTest {

    /** database (eternax.db.databases) -> that service's repositories */
    private static final Map<String, List<Class<?>>> REPOSITORIES =
            Map.of(
                    "ingestion",
                    List.of(
                            SourceConfigRepository.class,
                            SourceStatusRepository.class,
                            BatchRepository.class),
                    "normalizer",
                    List.of(MappingRepository.class, DltReplayRepository.class),
                    "matching",
                    List.of(
                            MatchingRepository.class,
                            SettlementRepository.class,
                            DefinitionSnapshotRepository.class),
                    "orchestration",
                    List.of(DefinitionRepository.class, RunRepository.class),
                    "exceptions",
                    List.of(CaseRepository.class, TatClockRepository.class),
                    "adjustments",
                    List.of(AdjustmentRepository.class, ApprovalPolicyRepository.class),
                    "audit",
                    List.of(AuditEventRepository.class),
                    "closure",
                    List.of(ClosureRepository.class),
                    "auth",
                    List.of(IamStore.class));

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    IngestionDbAutoConfiguration.class,
                                    NormalizerDbAutoConfiguration.class,
                                    MatchingDbAutoConfiguration.class,
                                    OrchestrationDbAutoConfiguration.class,
                                    ExceptionDbAutoConfiguration.class,
                                    AdjustmentDbAutoConfiguration.class,
                                    AuditDbAutoConfiguration.class,
                                    ClosureDbAutoConfiguration.class,
                                    AuthDbAutoConfiguration.class))
                    .withBean(
                            JdbcClient.class,
                            () -> JdbcClient.create(new DriverManagerDataSource()))
                    .withBean(ObjectMapper.class);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ingestion",
                "normalizer",
                "matching",
                "orchestration",
                "exceptions",
                "adjustments",
                "audit",
                "closure",
                "auth"
            })
    void aServiceGetsOnlyItsOwnRepositories(String database) {
        ApplicationContextRunner service = runner.withPropertyValues("eternax.db.name=" + database);
        if (database.equals("auth")) {
            // auth-service provides this bean itself; no other service has one
            service = service.withBean(DefaultTenantPolicy.class, () -> () -> null);
        }
        service.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    REPOSITORIES.forEach(
                            (owner, repositories) ->
                                    repositories.forEach(
                                            repository -> {
                                                if (owner.equals(database)) {
                                                    assertThat(context).hasSingleBean(repository);
                                                } else {
                                                    assertThat(context).doesNotHaveBean(repository);
                                                }
                                            }));
                });
    }
}
