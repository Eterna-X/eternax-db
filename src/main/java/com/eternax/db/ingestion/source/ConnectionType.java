package com.eternax.db.ingestion.source;

/**
 * Supported source connection types (HLD 18.1). Only FILE and MANUAL_UPLOAD are wired in Phase 0.
 */
public enum ConnectionType {
    API,
    FILE,
    SFTP,
    DATABASE_CDC,
    EVENT_STREAM,
    MANUAL_UPLOAD
}
