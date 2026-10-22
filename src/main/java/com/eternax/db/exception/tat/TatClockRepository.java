package com.eternax.db.exception.tat;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Turnaround-time clocks per rail (HLD 17.4): the tenant's own, else the platform default. */
@Repository
public class TatClockRepository {

    private final JdbcClient jdbc;

    public TatClockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TatRailClock> find(String tenantId, String rail) {
        return jdbc.sql(
                        """
                        SELECT rail, deadline_days, use_working_days, compensation_per_day_minor FROM tat_rail_clock
                        WHERE rail = :r AND tenant_id IN (:t, '*') ORDER BY CASE WHEN tenant_id = '*' THEN 1 ELSE 0 END LIMIT 1
                        """)
                .param("r", rail)
                .param("t", tenantId)
                .query(
                        (rs, n) ->
                                new TatRailClock(
                                        rs.getString(1),
                                        rs.getInt(2),
                                        rs.getBoolean(3),
                                        rs.getLong(4)))
                .optional();
    }
}
