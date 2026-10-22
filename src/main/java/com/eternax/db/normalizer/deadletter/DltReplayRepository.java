package com.eternax.db.normalizer.deadletter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Which dead-letter records were already sent back for another try (NRM-06). */
@Repository
public class DltReplayRepository {

    private final JdbcClient jdbc;

    public DltReplayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean wasReplayed(String topic, int partition, long offset) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM dlt_replay WHERE topic=:t AND part=:p AND"
                                + " msg_offset=:o)")
                .param("t", topic)
                .param("p", partition)
                .param("o", offset)
                .query(Boolean.class)
                .single();
    }

    /** Idempotent: recording the same record twice changes nothing. */
    public void recordReplay(String topic, int partition, long offset, String actor) {
        jdbc.sql(
                        "INSERT INTO dlt_replay (topic, part, msg_offset, replayed_by) VALUES"
                                + " (:t,:p,:o,:by) ON CONFLICT DO NOTHING")
                .param("t", topic)
                .param("p", partition)
                .param("o", offset)
                .param("by", actor)
                .update();
    }
}
