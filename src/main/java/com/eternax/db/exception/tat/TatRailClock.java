package com.eternax.db.exception.tat;

/** One rail's Turn-Around-Time clock (HLD 10.2) as configuration data. */
public record TatRailClock(
        String rail, int deadlineDays, boolean useWorkingDays, long compensationPerDayMinor) {}
