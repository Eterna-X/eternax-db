package com.eternax.db.normalizer.mapping;

/** Field transformations a mapping may apply (HLD FR-031). */
public enum TransformationType {
    // Note: a canonical field named attr.<name> keeps any extra source value with the record. Do
    // not map
    // card or account numbers there; the platform tokenises only the accountReference field.
    /** Copy the source value unchanged. */
    DIRECT_COPY,
    /**
     * Decimal rupees to whole paise; more than two decimals is an error, never rounded. A leading
     * currency mark (₹, INR, Rs) and thousands separators (also Indian grouping) are ignored.
     */
    AMOUNT_TO_MINOR,
    /**
     * The source already holds whole paise (for example 10150 for ₹101.50). A fraction is an error,
     * never rounded, so rupees cannot be mistaken for paise silently.
     */
    PAISE_TO_MINOR,
    /** Parse a date-time with the pattern in {@code parameter} into a UTC instant. */
    TIMESTAMP,
    /**
     * Clean a reference (RRN, UTR, ...) so the same payment gets the same key in every source.
     * {@code parameter} lists steps, comma separated: {@code UPPERCASE}, {@code STRIP_SPACES},
     * {@code STRIP_LEADING_ZEROS} (only for an all-digit reference), {@code
     * STRIP_NON_ALPHANUMERIC}. Without a parameter all of UPPERCASE, STRIP_SPACES and
     * STRIP_LEADING_ZEROS apply (MAT-10).
     */
    REFERENCE,
    /**
     * Parse a calendar date with the pattern in {@code parameter} (for example {@code yyyy-MM-dd});
     * a pattern that also has a time keeps only the date. Used for value, posting and settlement
     * dates.
     */
    DATE,
    /** Translate codes using {@code parameter} as {@code SRC=TARGET,SRC2=TARGET2}. */
    VALUE_MAP
}
