package com.jobassistant.repository;

import com.jobassistant.entity.SavedJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedJobRepository extends JpaRepository<SavedJob, Long> {

    List<SavedJob> findAllByOrderBySavedAtDesc();
}
