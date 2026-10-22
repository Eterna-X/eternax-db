package com.eternax.db.closure.model;

/** Outcome of one readiness check (HLD 19.2). */
public record CheckResult(String name, boolean passed, String detail) {}
