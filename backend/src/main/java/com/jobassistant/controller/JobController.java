package com.jobassistant.controller;

import com.jobassistant.dto.CompareRequest;
import com.jobassistant.dto.ComparisonDto;
import com.jobassistant.dto.JobCardDto;
import com.jobassistant.dto.JobCardsRequest;
import com.jobassistant.dto.JobDetailDto;
import com.jobassistant.dto.JobSearchRequest;
import com.jobassistant.dto.JobSearchResponse;
import com.jobassistant.dto.PageResponse;
import com.jobassistant.service.JobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public PageResponse<JobCardDto> list(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return jobService.list(page, size);
    }

    @GetMapping("/{id}")
    public JobDetailDto get(@PathVariable Long id) {
        return jobService.get(id);
    }

    /** Deterministic hybrid search (structured filters + vector retrieval), no LLM involved. */
    @PostMapping("/search")
    public JobSearchResponse search(@Valid @RequestBody JobSearchRequest request) {
        return jobService.search(request);
    }

    /** Cards for the given ids in request order; unknown ids are skipped (used to restore chat history). */
    @PostMapping("/cards")
    public List<JobCardDto> cards(@Valid @RequestBody JobCardsRequest request) {
        return jobService.cards(request.jobIds());
    }

    @PostMapping("/compare")
    public ComparisonDto compare(@Valid @RequestBody CompareRequest request) {
        return jobService.compare(request.jobIds());
    }
}
