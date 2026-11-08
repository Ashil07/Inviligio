package com.invigilo.identity;

/** Roles a user can hold within one college. Spring authorities are {@code ROLE_<name>}. */
public enum Role {
    EXAM_CELL_ADMIN,
    HOD,
    FACULTY
}
