package com.eternax.db.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternax.db.auth.Iam.PolicyView;
import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
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
    void anUnknownUserIsNotFound() {
        assertThat(store.userRow("t3", "missing")).isEmpty();
        assertThat(store.userByLogin("t3", "nobody")).isEmpty();
    }
}
