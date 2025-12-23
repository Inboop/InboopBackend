package com.inboop.backend.workspace.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception for workspace-related errors.
 */
public class WorkspaceException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public WorkspaceException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    // Common error factory methods

    /**
     * Plan user limit reached (409 Conflict).
     */
    public static WorkspaceException planUserLimitReached() {
        return new WorkspaceException(
            "PLAN_USER_LIMIT_REACHED",
            "Pro plan supports up to 5 users. Upgrade to add more users.",
            HttpStatus.CONFLICT
        );
    }

    /**
     * Admin permission required (403 Forbidden).
     */
    public static WorkspaceException adminRequired() {
        return new WorkspaceException(
            "ADMIN_REQUIRED",
            "Only admins can invite users to the workspace.",
            HttpStatus.FORBIDDEN
        );
    }

    /**
     * Must have at least one admin (422 Unprocessable Entity).
     */
    public static WorkspaceException mustHaveAdmin() {
        return new WorkspaceException(
            "MUST_HAVE_ADMIN",
            "Workspace must have at least one admin.",
            HttpStatus.UNPROCESSABLE_ENTITY
        );
    }

    /**
     * User already a member (409 Conflict).
     */
    public static WorkspaceException userAlreadyMember(String email) {
        return new WorkspaceException(
            "USER_ALREADY_MEMBER",
            "User " + email + " is already a member of this workspace.",
            HttpStatus.CONFLICT
        );
    }

    /**
     * Workspace not found (404 Not Found).
     */
    public static WorkspaceException workspaceNotFound(Long id) {
        return new WorkspaceException(
            "WORKSPACE_NOT_FOUND",
            "Workspace not found: " + id,
            HttpStatus.NOT_FOUND
        );
    }

    /**
     * Member not found (404 Not Found).
     */
    public static WorkspaceException memberNotFound(Long userId) {
        return new WorkspaceException(
            "MEMBER_NOT_FOUND",
            "Member not found in workspace: " + userId,
            HttpStatus.NOT_FOUND
        );
    }

    /**
     * User not found (404 Not Found).
     */
    public static WorkspaceException userNotFound(String email) {
        return new WorkspaceException(
            "USER_NOT_FOUND",
            "User not found: " + email,
            HttpStatus.NOT_FOUND
        );
    }
}
