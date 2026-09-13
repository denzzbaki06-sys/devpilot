package com.devpilot.repository;
import com.devpilot.model.GitHubConnection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubConnectionRepository extends JpaRepository<GitHubConnection, Long> {
    Optional<GitHubConnection> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
