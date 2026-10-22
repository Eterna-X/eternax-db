package com.eternax.db.ingestion.batch;

/** Ingestion batch lifecycle (HLD 18.2). */
public enum BatchStatus {
    RECEIVED,
    VALIDATING,
    ACCEPTED,
    ACCEPTED_WITH_QUARANTINE,
    /** Accepted by ingestion but some records later failed normalisation (NRM-05). */
    ACCEPTED_WITH_FAILURES,
    HELD,
    /**
     * A held or failed batch someone decided not to use (for example a wrong file); it no longer
     * blocks closure.
     */
    DISCARDED,
    FAILED,
    REPLAYED
}
