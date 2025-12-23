package com.inboop.backend.workspace.dto;

import com.inboop.backend.workspace.enums.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to invite a user to a workspace.
 */
public class InviteUserRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    private WorkspaceRole role = WorkspaceRole.MEMBER;

    public InviteUserRequest() {}

    public InviteUserRequest(String email, WorkspaceRole role) {
        this.email = email;
        this.role = role;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public void setRole(WorkspaceRole role) {
        this.role = role;
    }
}
