package com.inboop.backend.workspace.dto;

/**
 * Standard error response format for workspace errors.
 */
public class WorkspaceErrorResponse {

    private String code;
    private String message;

    public WorkspaceErrorResponse() {}

    public WorkspaceErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
