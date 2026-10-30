package com.eternax.db.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternax.recon.testsupport.EmbeddedPostgresInitializer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/** The module on its own: its settings load, Flyway builds its tables, its queries run. */
@SpringBootTest(
        classes = NotificationDbTest.App.class,
        properties = "spring.application.name=notification-service")
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
class NotificationDbTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Autowired NotificationRepository repo;

    private static final Instant T0 = Instant.parse("2026-10-30T09:00:00Z");

    private void person(String tenant, String id, String status, List<String> perms, Instant at) {
        repo.upsertUser(id, tenant, id + "@bank.example", id, status, perms, at);
    }

    @Test
    void recipientsAreActivePeopleHoldingAnyOfThePermissions() {
        person("t1", "t1-ravi", "ACTIVE", List.of("cases.work"), T0);
        person("t1", "t1-neha", "ACTIVE", List.of("cases.read"), T0);
        person("t1", "t1-old", "DISABLED", List.of("cases.work"), T0);
        person("t2", "t2-amit", "ACTIVE", List.of("cases.work"), T0);

        assertThat(repo.activeRecipients("t1", List.of("cases.work", "closure.approve")))
                .extracting(NotificationRepository.Recipient::userId)
                .containsExactly("t1-ravi");
        assertThat(repo.activeRecipients("t1", List.of())).isEmpty();
    }

    @Test
    void anOlderDirectoryEventDoesNotOverwriteANewerCopy() {
        assertThat(repo.upsertUser("t3-a", "t3", "a@x", "A", "ACTIVE", List.of("x"), T0)).isTrue();
        assertThat(
                        repo.upsertUser(
                                "t3-a",
                                "t3",
                                "a@x",
                                "A",
                                "DISABLED",
                                List.of(),
                                T0.minusSeconds(60)))
                .isFalse();
        assertThat(repo.activeRecipients("t3", List.of("x"))).hasSize(1);
    }

    @Test
    void theSameNotificationTwiceIsSkipped_andTheClaimHandsEachDeliveryOutOnce() {
        person("t4", "t4-ravi", "ACTIVE", List.of("cases.work"), T0);
        assertThat(
                        repo.insertNotification(
                                "n1",
                                "t4",
                                null,
                                "t4-ravi",
                                "TAT_WARNING",
                                "C1",
                                "TAT_WARNING|C1|t4-ravi",
                                "title",
                                "body",
                                "/cases/C1"))
                .isTrue();
        assertThat(
                        repo.insertNotification(
                                "n2",
                                "t4",
                                null,
                                "t4-ravi",
                                "TAT_WARNING",
                                "C1",
                                "TAT_WARNING|C1|t4-ravi",
                                "title",
                                "body",
                                "/cases/C1"))
                .isFalse();

        Instant now = Instant.now();
        repo.insertDelivery("d1", "n1", "t4", "EMAIL", null, "PENDING", now, null);
        assertThat(repo.claimDue(now, 50, now.plusSeconds(60)))
                .extracting(NotificationRepository.ClaimedDelivery::deliveryId)
                .contains("d1");
        assertThat(repo.claimDue(now, 50, now.plusSeconds(60)))
                .extracting(NotificationRepository.ClaimedDelivery::deliveryId)
                .doesNotContain("d1");

        repo.recordSent("d1", "msg-1", now);
        assertThat(repo.deliveries("t4", "n1"))
                .singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo("SENT"));
        assertThat(repo.deliveries("other-tenant", "n1")).isEmpty();
    }
}
