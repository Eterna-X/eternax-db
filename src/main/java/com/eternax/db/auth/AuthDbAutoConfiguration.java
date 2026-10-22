package com.eternax.db.auth;

import com.eternax.db.common.ConditionalOnServiceDatabase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers auth's repositories in eternaX-auth-service only (database {@code auth}). The
 * connection they use comes from the shared settings in {@code com.eternax.db.common}.
 */
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnServiceDatabase("auth")
@Import({IamStore.class})
public class AuthDbAutoConfiguration {}
