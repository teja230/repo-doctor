package dev.repodoctor.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobRepository extends JpaRepository<Job, String> {
    List<Job> findTop10ByOrderByCreatedAtDesc();

    List<Job> findTop10BySessionIdOrderByCreatedAtDesc(String sessionId);
}
