package com.eternax.db.notification;

import com.eternax.db.common.ConditionalOnServiceDatabase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers notification's repository in notification-service only (database {@code
 * notifications}). The connection it uses comes from the shared settings in {@code
 * com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("notifications")
@Import({NotificationRepository.class})
public class NotificationDbAutoConfiguration {}
