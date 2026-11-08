package com.invigilo.common.persistence;

import java.util.Locale;

/** Short identifiers (department, subject and hall codes): case-insensitive, stored upper-case. */
public final class Codes {

    /** Accepts either case; the database CHECK requires the normalized upper-case form. */
    public static final String PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,31}$";
    public static final String MESSAGE = "must be 1-32 letters, digits, '.', '-' or '_'";

    private Codes() {}

    public static String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
