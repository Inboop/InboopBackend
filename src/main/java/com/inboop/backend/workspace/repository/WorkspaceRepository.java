package com.inboop.backend.workspace.repository;

import com.inboop.backend.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /**
     * Find workspace by ID that is not deleted.
     */
    @Query("SELECT w FROM Workspace w WHERE w.id = :id AND w.deletedAt IS NULL")
    Optional<Workspace> findActiveById(@Param("id") Long id);

    /**
     * Find all workspaces owned by a user.
     */
    @Query("SELECT w FROM Workspace w WHERE w.owner.id = :ownerId AND w.deletedAt IS NULL")
    List<Workspace> findByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * Find all workspaces where user is a member.
     */
    @Query("SELECT DISTINCT w FROM Workspace w " +
           "JOIN w.members m " +
           "WHERE m.user.id = :userId AND w.deletedAt IS NULL")
    List<Workspace> findByMemberUserId(@Param("userId") Long userId);
}
