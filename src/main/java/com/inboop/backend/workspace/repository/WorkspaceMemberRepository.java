package com.inboop.backend.workspace.repository;

import com.inboop.backend.workspace.entity.WorkspaceMember;
import com.inboop.backend.workspace.enums.WorkspaceRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    /**
     * Find membership by workspace and user.
     */
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    /**
     * Check if user is a member of workspace.
     */
    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    /**
     * Count active members in a workspace (joined users).
     */
    @Query("SELECT COUNT(m) FROM WorkspaceMember m " +
           "WHERE m.workspace.id = :workspaceId " +
           "AND m.workspace.deletedAt IS NULL")
    long countByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /**
     * Count admins in a workspace.
     */
    @Query("SELECT COUNT(m) FROM WorkspaceMember m " +
           "WHERE m.workspace.id = :workspaceId " +
           "AND m.role = :role " +
           "AND m.workspace.deletedAt IS NULL")
    long countByWorkspaceIdAndRole(@Param("workspaceId") Long workspaceId, @Param("role") WorkspaceRole role);

    /**
     * Find all members of a workspace.
     */
    @Query("SELECT m FROM WorkspaceMember m " +
           "WHERE m.workspace.id = :workspaceId " +
           "AND m.workspace.deletedAt IS NULL " +
           "ORDER BY m.role, m.joinedAt")
    List<WorkspaceMember> findByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /**
     * Find all workspaces a user is a member of.
     */
    List<WorkspaceMember> findByUserId(Long userId);

    /**
     * Find all admins in a workspace.
     */
    @Query("SELECT m FROM WorkspaceMember m " +
           "WHERE m.workspace.id = :workspaceId " +
           "AND m.role = 'ADMIN' " +
           "AND m.workspace.deletedAt IS NULL")
    List<WorkspaceMember> findAdminsByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /**
     * Check if user is admin in workspace.
     */
    @Query("SELECT COUNT(m) > 0 FROM WorkspaceMember m " +
           "WHERE m.workspace.id = :workspaceId " +
           "AND m.user.id = :userId " +
           "AND m.role = 'ADMIN'")
    boolean isUserAdminInWorkspace(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);
}
