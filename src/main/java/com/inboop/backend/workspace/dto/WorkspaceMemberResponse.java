package com.inboop.backend.workspace.dto;

import com.inboop.backend.workspace.entity.WorkspaceMember;
import com.inboop.backend.workspace.enums.WorkspaceRole;

import java.time.LocalDateTime;

/**
 * Response representing a workspace member.
 */
public class WorkspaceMemberResponse {

    private Long id;
    private Long userId;
    private String userName;
    private String userEmail;
    private WorkspaceRole role;
    private LocalDateTime invitedAt;
    private LocalDateTime joinedAt;
    private String invitedByName;

    public WorkspaceMemberResponse() {}

    public static WorkspaceMemberResponse fromEntity(WorkspaceMember member) {
        WorkspaceMemberResponse response = new WorkspaceMemberResponse();
        response.setId(member.getId());
        response.setUserId(member.getUser().getId());
        response.setUserName(member.getUser().getName());
        response.setUserEmail(member.getUser().getEmail());
        response.setRole(member.getRole());
        response.setInvitedAt(member.getInvitedAt());
        response.setJoinedAt(member.getJoinedAt());
        if (member.getInvitedBy() != null) {
            response.setInvitedByName(member.getInvitedBy().getName());
        }
        return response;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public void setRole(WorkspaceRole role) {
        this.role = role;
    }

    public LocalDateTime getInvitedAt() {
        return invitedAt;
    }

    public void setInvitedAt(LocalDateTime invitedAt) {
        this.invitedAt = invitedAt;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public String getInvitedByName() {
        return invitedByName;
    }

    public void setInvitedByName(String invitedByName) {
        this.invitedByName = invitedByName;
    }
}
