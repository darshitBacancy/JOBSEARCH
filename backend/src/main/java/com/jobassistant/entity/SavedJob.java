package com.jobassistant.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A job the user bookmarked. Global, like conversations: the app has no user accounts. */
@Entity
@Table(name = "saved_job")
public class SavedJob {

    @Id
    private Long jobId;

    private Instant savedAt;

    public SavedJob() {
    }

    public SavedJob(Long jobId, Instant savedAt) {
        this.jobId = jobId;
        this.savedAt = savedAt;
    }

    public Long getJobId() { return jobId; }
    public Instant getSavedAt() { return savedAt; }
}
