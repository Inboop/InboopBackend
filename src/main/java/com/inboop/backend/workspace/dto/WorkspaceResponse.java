package com.inboop.backend.workspace.dto;

import com.inboop.backend.workspace.entity.Workspace;
import com.inboop.backend.workspace.enums.PlanType;

import java.time.LocalDateTime;

/**
 * Response representing a workspace.
 */
public class WorkspaceResponse {

    private Long id;
    private String name;
    private PlanType plan;
    private int maxUsers;
    private int currentUserCount;
    private Long ownerId;
    private String ownerName;
    private LocalDateTime createdAt;

    public WorkspaceResponse() {}

    public static WorkspaceResponse fromEntity(Workspace workspace, int memberCount) {
        WorkspaceResponse response = new WorkspaceResponse();
        response.setId(workspace.getId());
        response.setName(workspace.getName());
        response.setPlan(workspace.getPlan());
        response.setMaxUsers(workspace.getMaxUsers());
        response.setCurrentUserCount(memberCount);
        response.setOwnerId(workspace.getOwner().getId());
        response.setOwnerName(workspace.getOwner().getName());
        response.setCreatedAt(workspace.getCreatedAt());
        return response;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PlanType getPlan() {
        return plan;
    }

    public void setPlan(PlanType plan) {
        this.plan = plan;
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(int maxUsers) {
        this.maxUsers = maxUsers;
    }

    public int getCurrentUserCount() {
        return currentUserCount;
    }

    public void setCurrentUserCount(int currentUserCount) {
        this.currentUserCount = currentUserCount;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean canAddMoreUsers() {
        return currentUserCount < maxUsers;
    }
}
