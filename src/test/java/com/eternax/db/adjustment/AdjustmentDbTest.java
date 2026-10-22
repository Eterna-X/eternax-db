package com.eternax.db.adjustment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eternax.db.adjustment.policy.ApprovalPolicyRepository;
import com.eternax.db.adjustment.policy.ApprovalPolicyRepository.Policy;
import com.eternax.db.adjustment.store.AdjustmentRepository;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ContextConfiguration;

/** The module on its own: its settings load, Flyway builds its tables, its queries run. */
@SpringBootTest(
        classes = AdjustmentDbTest.App.class,
        properties = "spring.application.name=adjustment-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class AdjustmentDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Autowired ApprovalPolicyRepository policies;
    @Autowired AdjustmentRepository adjustments;

    private static Policy policy(int priority, String name) {
        return new Policy(null, priority, name, null, "HIGH", "UPI", 0, true, false, true);
    }

    @Test
    void policiesComeBackInPriorityOrderAndCanBeChangedAndRemoved() {
        policies.insert("t1", "p20", policy(20, "second"), "admin");
        policies.insert("t1", "p10", policy(10, "first"), "admin");

        assertThat(policies.list("t1")).extracting(Policy::name).containsExactly("first", "second");

        policies.update("t1", "p20", policy(5, "now first"));
        assertThat(policies.list("t1"))
                .extracting(Policy::name)
                .containsExactly("now first", "first");

        policies.delete("t1", "p20");
        assertThat(policies.list("t1")).extracting(Policy::policyId).containsExactly("p10");
    }

    @Test
    void aPriorityIsUniquePerTenant() {
        policies.insert("t2", "a", policy(1, "a"), "admin");

        assertThatThrownBy(() -> policies.insert("t2", "b", policy(1, "b"), "admin"))
                .isInstanceOf(DuplicateKeyException.class);
        policies.insert("other-bank", "c", policy(1, "c"), "admin");
    }

    @Test
    void anUnknownAdjustmentIsNotFound() {
        assertThat(adjustments.find("t3", "missing")).isEmpty();
    }
}
