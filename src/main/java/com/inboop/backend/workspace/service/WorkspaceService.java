package com.inboop.backend.workspace.service;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.auth.repository.UserRepository;
import com.inboop.backend.workspace.dto.*;
import com.inboop.backend.workspace.entity.Workspace;
import com.inboop.backend.workspace.entity.WorkspaceMember;
import com.inboop.backend.workspace.enums.PlanType;
import com.inboop.backend.workspace.enums.WorkspaceRole;
import com.inboop.backend.workspace.exception.WorkspaceException;
import com.inboop.backend.workspace.repository.WorkspaceMemberRepository;
import com.inboop.backend.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for workspace management with plan constraints.
 */
@Service
@Transactional
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository userRepository;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository memberRepository,
            UserRepository userRepository) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Create a new workspace. The creator becomes the owner and first admin.
     */
    public WorkspaceResponse createWorkspace(String name, User owner) {
        Workspace workspace = new Workspace();
        workspace.setName(name);
        workspace.setOwner(owner);
        workspace.setPlan(PlanType.PRO); // Default to Pro plan
        workspace = workspaceRepository.save(workspace);

        // Add owner as first member with ADMIN role
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUser(owner);
        member.setRole(WorkspaceRole.ADMIN);
        member.setJoinedAt(LocalDateTime.now());
        memberRepository.save(member);

        return WorkspaceResponse.fromEntity(workspace, 1);
    }

    /**
     * Get workspace by ID.
     */
    @Transactional(readOnly = true)
    public WorkspaceResponse getWorkspace(Long workspaceId) {
        Workspace workspace = workspaceRepository.findActiveById(workspaceId)
                .orElseThrow(() -> WorkspaceException.workspaceNotFound(workspaceId));

        int memberCount = (int) memberRepository.countByWorkspaceId(workspaceId);
        return WorkspaceResponse.fromEntity(workspace, memberCount);
    }

    /**
     * Get all workspaces for a user (as owner or member).
     */
    @Transactional(readOnly = true)
    public List<WorkspaceResponse> getWorkspacesForUser(Long userId) {
        List<Workspace> workspaces = workspaceRepository.findByMemberUserId(userId);
        return workspaces.stream()
                .map(w -> WorkspaceResponse.fromEntity(w, (int) memberRepository.countByWorkspaceId(w.getId())))
                .collect(Collectors.toList());
    }

    /**
     * Invite a user to the workspace.
     * Enforces:
     * - Admin-only invites
     * - Plan user limit (5 for Pro)
     * - No duplicate members
     */
    public WorkspaceMemberResponse inviteUser(Long workspaceId, InviteUserRequest request, User inviter) {
        Workspace workspace = workspaceRepository.findActiveById(workspaceId)
                .orElseThrow(() -> WorkspaceException.workspaceNotFound(workspaceId));

        // Check if inviter is admin
        if (!memberRepository.isUserAdminInWorkspace(workspaceId, inviter.getId())) {
            throw WorkspaceException.adminRequired();
        }

        // Check plan user limit
        long currentMemberCount = memberRepository.countByWorkspaceId(workspaceId);
        if (currentMemberCount >= workspace.getMaxUsers()) {
            throw WorkspaceException.planUserLimitReached();
        }

        // Find user by email
        User invitee = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> WorkspaceException.userNotFound(request.getEmail()));

        // Check if already a member
        if (memberRepository.existsByWorkspaceIdAndUserId(workspaceId, invitee.getId())) {
            throw WorkspaceException.userAlreadyMember(request.getEmail());
        }

        // Create membership
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUser(invitee);
        member.setRole(request.getRole() != null ? request.getRole() : WorkspaceRole.MEMBER);
        member.setInvitedBy(inviter);
        member.setInvitedAt(LocalDateTime.now());
        member.setJoinedAt(LocalDateTime.now()); // Auto-join for now (no email verification)
        member = memberRepository.save(member);

        return WorkspaceMemberResponse.fromEntity(member);
    }

    /**
     * Get all members of a workspace.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getMembers(Long workspaceId) {
        if (!workspaceRepository.findActiveById(workspaceId).isPresent()) {
            throw WorkspaceException.workspaceNotFound(workspaceId);
        }

        return memberRepository.findByWorkspaceId(workspaceId).stream()
                .map(WorkspaceMemberResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Update a member's role.
     * Enforces:
     * - Admin-only operation
     * - Cannot demote last admin
     */
    public WorkspaceMemberResponse updateMemberRole(
            Long workspaceId,
            Long memberId,
            UpdateMemberRoleRequest request,
            User requester) {

        if (!workspaceRepository.findActiveById(workspaceId).isPresent()) {
            throw WorkspaceException.workspaceNotFound(workspaceId);
        }

        // Check if requester is admin
        if (!memberRepository.isUserAdminInWorkspace(workspaceId, requester.getId())) {
            throw WorkspaceException.adminRequired();
        }

        WorkspaceMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> WorkspaceException.memberNotFound(memberId));

        // Verify member belongs to this workspace
        if (!member.getWorkspace().getId().equals(workspaceId)) {
            throw WorkspaceException.memberNotFound(memberId);
        }

        // If demoting from ADMIN to MEMBER, check we're not removing the last admin
        if (member.getRole() == WorkspaceRole.ADMIN && request.getRole() == WorkspaceRole.MEMBER) {
            long adminCount = memberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN);
            if (adminCount <= 1) {
                throw WorkspaceException.mustHaveAdmin();
            }
        }

        member.setRole(request.getRole());
        member = memberRepository.save(member);

        return WorkspaceMemberResponse.fromEntity(member);
    }

    /**
     * Remove a member from workspace.
     * Enforces:
     * - Admin-only operation (unless removing self)
     * - Cannot remove last admin
     */
    public void removeMember(Long workspaceId, Long memberId, User requester) {
        if (!workspaceRepository.findActiveById(workspaceId).isPresent()) {
            throw WorkspaceException.workspaceNotFound(workspaceId);
        }

        WorkspaceMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> WorkspaceException.memberNotFound(memberId));

        // Verify member belongs to this workspace
        if (!member.getWorkspace().getId().equals(workspaceId)) {
            throw WorkspaceException.memberNotFound(memberId);
        }

        // Check permissions: must be admin OR removing self
        boolean isAdmin = memberRepository.isUserAdminInWorkspace(workspaceId, requester.getId());
        boolean isRemovingSelf = member.getUser().getId().equals(requester.getId());

        if (!isAdmin && !isRemovingSelf) {
            throw WorkspaceException.adminRequired();
        }

        // If removing an admin, check we're not removing the last one
        if (member.getRole() == WorkspaceRole.ADMIN) {
            long adminCount = memberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN);
            if (adminCount <= 1) {
                throw WorkspaceException.mustHaveAdmin();
            }
        }

        memberRepository.delete(member);
    }

    /**
     * Check if user is admin in workspace.
     */
    @Transactional(readOnly = true)
    public boolean isAdmin(Long workspaceId, Long userId) {
        return memberRepository.isUserAdminInWorkspace(workspaceId, userId);
    }

    /**
     * Check if user can invite more users to workspace.
     */
    @Transactional(readOnly = true)
    public boolean canInviteMore(Long workspaceId) {
        Workspace workspace = workspaceRepository.findActiveById(workspaceId)
                .orElseThrow(() -> WorkspaceException.workspaceNotFound(workspaceId));

        long currentMemberCount = memberRepository.countByWorkspaceId(workspaceId);
        return currentMemberCount < workspace.getMaxUsers();
    }
}
