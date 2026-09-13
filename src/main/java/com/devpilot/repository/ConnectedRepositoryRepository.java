package com.devpilot.repository;
import com.devpilot.model.ConnectedRepository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectedRepositoryRepository extends JpaRepository<ConnectedRepository, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from ConnectedRepository r where r.id = :id and r.userId = :userId")
    Optional<ConnectedRepository> findOwnedForUpdate(@org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("userId") Long userId);
    List<ConnectedRepository> findAllByUserIdOrderByIdAsc(Long userId);
    Optional<ConnectedRepository> findByIdAndUserId(Long id, Long userId);
    Optional<ConnectedRepository> findByUserIdAndGithubRepositoryId(Long userId, Long githubRepositoryId);
}
