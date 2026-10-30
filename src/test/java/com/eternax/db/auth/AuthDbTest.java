package com.eternax.db.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eternax.db.auth.Iam.PolicyView;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ContextConfiguration;

/** The module on its own: its settings load, Flyway builds its tables, its queries run. */
@SpringBootTest(classes = AuthDbTest.App.class, properties = "spring.application.name=auth-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class AuthDbTest {

    private static final PolicyView DEFAULTS = new PolicyView(true, 14, 15, 8, 5, 30, null);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        DefaultTenantPolicy defaultTenantPolicy() {
            return () -> DEFAULTS;
        }
    }

    @Autowired IamStore store;

    @Test
    void anOrganisationWithoutItsOwnPolicyGetsTheServicesDefault() {
        assertThat(store.policy("t1")).isEqualTo(DEFAULTS);
    }

    @Test
    void aSavedPolicyReplacesTheDefault() {
        PolicyView own = new PolicyView(false, 16, 10, 4, 3, 60, "bank.example");
        store.insertTenant("t2", "Bank Two", "Asia/Kolkata", "admin");
        store.savePolicy("t2", own, "admin");

        assertThat(store.tenant("t2")).isPresent();

        assertThat(store.policy("t2")).isEqualTo(own);
        assertThat(store.policy("other-bank")).isEqualTo(DEFAULTS);
    }

    @Test
    void aContactBelongsToItsOwner_andTheSameAddressCannotBeAddedTwice() {
        store.insertTenant("t4", "Bank Four", "Asia/Kolkata", "admin");
        store.insertUser("t4", "u1", "ravi@t4.example", "Ravi", "hash", "admin");
        store.insertContact(
                "t4", "u1", "c1", "SMS", "cipher", "hash-1", "+919XXXXXX210", null, null);

        assertThat(store.contacts("t4", "u1")).hasSize(1);
        assertThat(store.contact("t4", "someone-else", "c1")).isEmpty();
        assertThat(store.contact("other-bank", "u1", "c1")).isEmpty();
        assertThatThrownBy(
                        () ->
                                store.insertContact(
                                        "t4",
                                        "u1",
                                        "c2",
                                        "SMS",
                                        "cipher2",
                                        "hash-1",
                                        "+919XXXXXX210",
                                        null,
                                        null))
                .isInstanceOf(DuplicateKeyException.class);

        store.recordCodeSent("c1", 1, Instant.parse("2026-10-30T09:00:00Z"));
        store.recordFailedCheck("c1");
        store.markVerified("c1", Instant.parse("2026-10-30T09:01:00Z"));
        assertThat(store.contact("t4", "u1", "c1").orElseThrow().verifiedAt()).isNotNull();
        assertThat(store.contact("t4", "u1", "c1").orElseThrow().failedChecks()).isZero();

        store.deleteContact("c1");
        assertThat(store.contacts("t4", "u1")).isEmpty();
    }

    @Test
    void anUnknownUserIsNotFound() {
        assertThat(store.userRow("t3", "missing")).isEmpty();
        assertThat(store.userByLogin("t3", "nobody")).isEmpty();
    }
}
