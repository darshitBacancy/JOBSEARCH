package com.jobassistant.service;

import com.jobassistant.dto.ComparisonDto;
import com.jobassistant.dto.JobCardDto;
import com.jobassistant.dto.JobDetailDto;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.dto.JobSearchRequest;
import com.jobassistant.dto.JobSearchResponse;
import com.jobassistant.dto.PageResponse;
import com.jobassistant.entity.Job;
import com.jobassistant.exception.JobNotFoundException;
import com.jobassistant.mapper.JobMapper;
import com.jobassistant.entity.SavedJob;
import com.jobassistant.repository.JobRepository;
import com.jobassistant.repository.SavedJobRepository;
import com.jobassistant.search.HybridSearchService;
import com.jobassistant.search.LocationNormalizer;
import com.jobassistant.search.QueryAnalysis;
import com.jobassistant.search.RuleBasedCriteriaParser;
import com.jobassistant.search.SkillCatalog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final JobMapper mapper;
    private final HybridSearchService hybridSearch;
    private final RuleBasedCriteriaParser parser;
    private final SkillCatalog skillCatalog;
    private final LocationNormalizer locations;
    private final ComparisonService comparisonService;
    private final SavedJobRepository savedJobs;

    public JobService(JobRepository jobRepository, JobMapper mapper, HybridSearchService hybridSearch,
                      RuleBasedCriteriaParser parser, SkillCatalog skillCatalog, LocationNormalizer locations,
                      ComparisonService comparisonService, SavedJobRepository savedJobs) {
        this.savedJobs = savedJobs;
        this.jobRepository = jobRepository;
        this.mapper = mapper;
        this.hybridSearch = hybridSearch;
        this.parser = parser;
        this.skillCatalog = skillCatalog;
        this.locations = locations;
        this.comparisonService = comparisonService;
    }

    public PageResponse<JobCardDto> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<Job> p = jobRepository.findAll(PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Order.desc("postedDate"), Sort.Order.asc("id"))));
        return new PageResponse<>(p.getContent().stream().map(mapper::toCard).toList(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    public Job getEntity(Long id) {
        return jobRepository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    public JobDetailDto get(Long id) {
        return mapper.toDetail(getEntity(id));
    }

    /** Load jobs preserving the requested order; unknown ids raise 404. */
    public List<Job> getAll(List<Long> ids) {
        Map<Long, Job> found = jobRepository.findAllById(ids).stream().collect(Collectors.toMap(Job::getId, Function.identity()));
        List<Job> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            Job j = found.get(id);
            if (j == null) throw new JobNotFoundException(id);
            out.add(j);
        }
        return out;
    }

    /** Cards in request order; unknown ids are skipped. */
    public List<JobCardDto> cards(List<Long> ids) {
        Map<Long, Job> found = jobRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));
        List<JobCardDto> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            Job j = found.get(id);
            if (j != null) out.add(mapper.toCard(j));
        }
        return out;
    }

    /** Saved jobs, newest first. */
    public List<JobCardDto> savedJobs() {
        return cards(savedJobs.findAllByOrderBySavedAtDesc().stream().map(SavedJob::getJobId).toList());
    }

    @Transactional
    public void saveJob(Long jobId) {
        if (!jobRepository.existsById(jobId)) throw new JobNotFoundException(jobId);
        if (!savedJobs.existsById(jobId)) savedJobs.save(new SavedJob(jobId, Instant.now()));
    }

    @Transactional
    public void unsaveJob(Long jobId) {
        if (savedJobs.existsById(jobId)) savedJobs.deleteById(jobId);
    }

    public ComparisonDto compare(List<Long> ids) {
        List<Job> jobs = getAll(ids);
        if (jobs.size() < 2) throw new IllegalArgumentException("select at least two different jobs to compare");
        return comparisonService.compare(jobs).comparison();
    }

    /** Deterministic hybrid search (no LLM): explicit filters + rule-parsed free text. */
    public JobSearchResponse search(JobSearchRequest req) {
        JobSearchCriteria fromText = JobSearchCriteria.empty();
        String query = req.query() == null ? "" : req.query().trim();
        if (!query.isEmpty()) {
            QueryAnalysis a = parser.parse(query, false);
            fromText = a.criteria();
        }
        List<String> skills = new ArrayList<>();
        if (req.skills() != null) {
            for (String s : req.skills()) skills.add(skillCatalog.canonicalise(s).orElse(s));
        }
        JobSearchCriteria explicit = new JobSearchCriteria(null, skills, locations.canonicalise(req.location()),
                req.remote(), req.experienceMin(), req.experienceMax(), req.salaryMin(), null, req.employmentType());
        JobSearchCriteria criteria = fromText.mergedWith(explicit);
        String semantic = query.isEmpty() ? String.join(" ", criteria.skills()) : query;
        HybridSearchService.SearchOutcome outcome = hybridSearch.search(criteria, semantic, null);
        int limit = req.limit() == null ? 10 : req.limit();
        List<JobCardDto> cards = outcome.top(limit).stream().map(r -> mapper.toCard(r.job(), r)).toList();
        return new JobSearchResponse(cards, outcome.totalMatches(), criteria, outcome.relaxedFilters(),
                outcome.retrieval().mode());
    }
}
