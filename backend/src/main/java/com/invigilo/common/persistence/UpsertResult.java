package com.invigilo.common.persistence;

/** Outcome of create-or-update by natural key (used by CSV import). */
public enum UpsertResult { CREATED, UPDATED, UNCHANGED }
