package com.jobassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A chat session. Besides the id it keeps the conversational search state that makes
 * follow-ups work ("only those above 15 LPA", "compare the first three").
 */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @Column(length = 36)
    private String id;

    private Instant createdAt;
    private Instant updatedAt;

    /** Sidebar label; set from the first user message and renamable. */
    @Column(length = 120)
    private String title;

    /** JSON of the JobSearchCriteria used by the most recent search. */
    @Column(columnDefinition = "CLOB")
    private String lastCriteriaJson;

    /** Standalone semantic query of the most recent search. */
    @Column(length = 4000)
    private String lastSemanticQuery;

    /** JSON array: every job id that matched the last search (ranked), used to refine "those". */
    @Column(columnDefinition = "CLOB")
    private String lastCandidateJobIds;

    /** JSON array: the job ids that were displayed, in order ("the first three"). */
    @Column(length = 2000)
    private String lastShownJobIds;

    /** The job the user is currently asking about ("this job"). */
    private Long focusJobId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getLastCriteriaJson() { return lastCriteriaJson; }
    public void setLastCriteriaJson(String lastCriteriaJson) { this.lastCriteriaJson = lastCriteriaJson; }
    public String getLastSemanticQuery() { return lastSemanticQuery; }
    public void setLastSemanticQuery(String lastSemanticQuery) { this.lastSemanticQuery = lastSemanticQuery; }
    public String getLastCandidateJobIds() { return lastCandidateJobIds; }
    public void setLastCandidateJobIds(String lastCandidateJobIds) { this.lastCandidateJobIds = lastCandidateJobIds; }
    public String getLastShownJobIds() { return lastShownJobIds; }
    public void setLastShownJobIds(String lastShownJobIds) { this.lastShownJobIds = lastShownJobIds; }
    public Long getFocusJobId() { return focusJobId; }
    public void setFocusJobId(Long focusJobId) { this.focusJobId = focusJobId; }
}
