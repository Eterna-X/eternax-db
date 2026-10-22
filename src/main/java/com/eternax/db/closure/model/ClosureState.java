package com.eternax.db.closure.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Period lifecycle (HLD 19.1). Illegal moves are rejected by {@link #canMoveTo}. */
public enum ClosureState {
    OPEN,
    IN_PROGRESS,
    READY_FOR_CLOSURE,
    PENDING_APPROVAL,
    CLOSED;

    private static final Map<ClosureState, Set<ClosureState>> ALLOWED =
            new EnumMap<>(ClosureState.class);

    static {
        ALLOWED.put(OPEN, EnumSet.of(IN_PROGRESS, READY_FOR_CLOSURE));
        ALLOWED.put(IN_PROGRESS, EnumSet.of(IN_PROGRESS, READY_FOR_CLOSURE));
        ALLOWED.put(
                READY_FOR_CLOSURE, EnumSet.of(IN_PROGRESS, READY_FOR_CLOSURE, PENDING_APPROVAL));
        ALLOWED.put(PENDING_APPROVAL, EnumSet.of(IN_PROGRESS, CLOSED));
        ALLOWED.put(CLOSED, EnumSet.of(IN_PROGRESS)); // reopen, with a reason
    }

    public boolean canMoveTo(ClosureState target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }
}
