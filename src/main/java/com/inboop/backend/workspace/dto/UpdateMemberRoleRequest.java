package com.inboop.backend.workspace.dto;

import com.inboop.backend.workspace.enums.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

/**
 * Request to update a member's role in a workspace.
 */
public class UpdateMemberRoleRequest {

    @NotNull(message = "Role is required")
    private WorkspaceRole role;

    public UpdateMemberRoleRequest() {}

    public UpdateMemberRoleRequest(WorkspaceRole role) {
        this.role = role;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public void setRole(WorkspaceRole role) {
        this.role = role;
    }
}
