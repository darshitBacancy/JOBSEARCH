package com.jobassistant.controller;

import com.jobassistant.dto.JobCardDto;
import com.jobassistant.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Bookmarked jobs. PUT and DELETE are idempotent. */
@RestController
@RequestMapping("/api/saved-jobs")
public class SavedJobController {

    private final JobService jobService;

    public SavedJobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public List<JobCardDto> list() {
        return jobService.savedJobs();
    }

    @PutMapping("/{jobId}")
    public ResponseEntity<Void> save(@PathVariable Long jobId) {
        jobService.saveJob(jobId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> unsave(@PathVariable Long jobId) {
        jobService.unsaveJob(jobId);
        return ResponseEntity.noContent().build();
    }
}
