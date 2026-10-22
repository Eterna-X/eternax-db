package com.eternax.db.auth;

import com.eternax.db.auth.Iam.PolicyView;

/**
 * The policy an organisation has until it saves its own. It comes from the service's configuration,
 * so auth-service provides it as a bean; {@link IamStore} only falls back to it.
 */
@FunctionalInterface
public interface DefaultTenantPolicy {

    PolicyView get();
}
