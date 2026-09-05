package com.docmind.model;

/**
 * Role-based access control levels:
 * - ORG_ADMIN: Full org management, billing, member invites
 * - MEMBER: Can upload docs, query, view results
 * - VIEWER: Read-only access to query results
 */
public enum Role {
    ORG_ADMIN,
    MEMBER,
    VIEWER
}
