package com.inboop.backend.workspace.controller;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.auth.repository.UserRepository;
import com.inboop.backend.workspace.dto.*;
import com.inboop.backend.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for workspace management.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;

    public WorkspaceController(WorkspaceService workspaceService, UserRepository userRepository) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
    }

    /**
     * Create a new workspace.
     */
    @PostMapping
    public ResponseEntity<WorkspaceResponse> createWorkspace(@RequestBody CreateWorkspaceRequest request) {
        User currentUser = getCurrentUser();
        WorkspaceResponse response = workspaceService.createWorkspace(request.getName(), currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get workspace details.
     */
    @GetMapping("/{workspaceId}")
    public ResponseEntity<WorkspaceResponse> getWorkspace(@PathVariable Long workspaceId) {
        WorkspaceResponse response = workspaceService.getWorkspace(workspaceId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all workspaces for current user.
     */
    @GetMapping
    public ResponseEntity<List<WorkspaceResponse>> getMyWorkspaces() {
        User currentUser = getCurrentUser();
        List<WorkspaceResponse> workspaces = workspaceService.getWorkspacesForUser(currentUser.getId());
        return ResponseEntity.ok(workspaces);
    }

    /**
     * Invite a user to the workspace.
     * Requires ADMIN role in workspace.
     * Enforces plan user limit.
     *
     * Possible error responses:
     * - 403 ADMIN_REQUIRED: Only admins can invite users
     * - 409 PLAN_USER_LIMIT_REACHED: Pro plan supports up to 5 users
     * - 409 USER_ALREADY_MEMBER: User is already a member
     * - 404 USER_NOT_FOUND: User email not found
     */
    @PostMapping("/{workspaceId}/members")
    public ResponseEntity<WorkspaceMemberResponse> inviteUser(
            @PathVariable Long workspaceId,
            @Valid @RequestBody InviteUserRequest request) {
        User currentUser = getCurrentUser();
        WorkspaceMemberResponse response = workspaceService.inviteUser(workspaceId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all members of a workspace.
     */
    @GetMapping("/{workspaceId}/members")
    public ResponseEntity<List<WorkspaceMemberResponse>> getMembers(@PathVariable Long workspaceId) {
        List<WorkspaceMemberResponse> members = workspaceService.getMembers(workspaceId);
        return ResponseEntity.ok(members);
    }

    /**
     * Update a member's role.
     * Requires ADMIN role in workspace.
     *
     * Possible error responses:
     * - 403 ADMIN_REQUIRED: Only admins can change roles
     * - 422 MUST_HAVE_ADMIN: Cannot demote last admin
     */
    @PatchMapping("/{workspaceId}/members/{memberId}/role")
    public ResponseEntity<WorkspaceMemberResponse> updateMemberRole(
            @PathVariable Long workspaceId,
            @PathVariable Long memberId,
            @Valid @RequestBody UpdateMemberRoleRequest request) {
        User currentUser = getCurrentUser();
        WorkspaceMemberResponse response = workspaceService.updateMemberRole(
                workspaceId, memberId, request, currentUser);
        return ResponseEntity.ok(response);
    }

    /**
     * Remove a member from workspace.
     * Requires ADMIN role (unless removing self).
     *
     * Possible error responses:
     * - 403 ADMIN_REQUIRED: Only admins can remove others
     * - 422 MUST_HAVE_ADMIN: Cannot remove last admin
     */
    @DeleteMapping("/{workspaceId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable Long workspaceId,
            @PathVariable Long memberId) {
        User currentUser = getCurrentUser();
        workspaceService.removeMember(workspaceId, memberId, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * Check if current user can invite more members.
     */
    @GetMapping("/{workspaceId}/can-invite")
    public ResponseEntity<CanInviteResponse> canInvite(@PathVariable Long workspaceId) {
        User currentUser = getCurrentUser();
        boolean isAdmin = workspaceService.isAdmin(workspaceId, currentUser.getId());
        boolean hasCapacity = workspaceService.canInviteMore(workspaceId);

        CanInviteResponse response = new CanInviteResponse();
        response.setCanInvite(isAdmin && hasCapacity);
        response.setIsAdmin(isAdmin);
        response.setHasCapacity(hasCapacity);
        if (!isAdmin) {
            response.setReason("Only admins can invite users.");
        } else if (!hasCapacity) {
            response.setReason("Pro plan supports up to 5 users. Upgrade to add more users.");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Get current authenticated user.
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // Inner DTOs

    public static class CreateWorkspaceRequest {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class CanInviteResponse {
        private boolean canInvite;
        private boolean isAdmin;
        private boolean hasCapacity;
        private String reason;

        public boolean isCanInvite() {
            return canInvite;
        }

        public void setCanInvite(boolean canInvite) {
            this.canInvite = canInvite;
        }

        public boolean isIsAdmin() {
            return isAdmin;
        }

        public void setIsAdmin(boolean isAdmin) {
            this.isAdmin = isAdmin;
        }

        public boolean isHasCapacity() {
            return hasCapacity;
        }

        public void setHasCapacity(boolean hasCapacity) {
            this.hasCapacity = hasCapacity;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
