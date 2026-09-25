package com.jobassistant.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.config.RagProperties;
import com.jobassistant.entity.Job;
import com.jobassistant.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Document loader: reads the job knowledge base (data/jobs.json) and synchronises it into
 * the relational {@code job} table, which is the source of truth for structured filtering.
 */
@Component
public class JobDataLoader {

    private static final Logger log = LoggerFactory.getLogger(JobDataLoader.class);

    private final ResourceLoader resourceLoader;
    private final ObjectMapper mapper;
    private final JobRepository jobRepository;
    private final RagProperties props;

    public JobDataLoader(ResourceLoader resourceLoader, ObjectMapper mapper, JobRepository jobRepository,
                         RagProperties props) {
        this.resourceLoader = resourceLoader;
        this.mapper = mapper;
        this.jobRepository = jobRepository;
        this.props = props;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Dataset(String dataset, String disclaimer, List<JobJson> jobs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JobJson(Long id, String title, String company, String location, boolean remote, String employmentType,
                   int experienceMin, int experienceMax, long salaryMin, long salaryMax, String currency,
                   List<String> skills, String description, List<String> requirements,
                   List<String> responsibilities, List<String> benefits, String postedDate, String applicationUrl) {
    }

    public record LoadResult(int jobCount, String datasetHash) {
    }

    /** Upsert every job from the dataset and remove jobs that no longer exist in it. */
    @Transactional
    public LoadResult load() {
        byte[] raw = readDataset();
        Dataset dataset;
        try {
            dataset = mapper.readValue(raw, Dataset.class);
        } catch (IOException e) {
            throw new IllegalStateException("Job dataset " + props.jobsData() + " is not valid JSON", e);
        }
        if (dataset.jobs() == null || dataset.jobs().isEmpty()) {
            throw new IllegalStateException("Job dataset " + props.jobsData() + " contains no jobs");
        }
        List<Job> jobs = dataset.jobs().stream().map(JobDataLoader::toEntity).toList();
        jobRepository.saveAll(jobs);
        Set<Long> ids = jobs.stream().map(Job::getId).collect(Collectors.toSet());
        List<Job> stale = jobRepository.findAll().stream().filter(j -> !ids.contains(j.getId())).toList();
        if (!stale.isEmpty()) jobRepository.deleteAll(stale);
        String hash = sha256(raw);
        log.info("Loaded {} jobs from {} (dataset hash {})", jobs.size(), props.jobsData(), hash.substring(0, 12));
        return new LoadResult(jobs.size(), hash);
    }

    private byte[] readDataset() {
        Resource resource = resourceLoader.getResource(props.jobsData());
        if (!resource.exists()) {
            // allow a plain filesystem path such as ../data/jobs.json
            resource = resourceLoader.getResource("file:" + props.jobsData());
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read job dataset " + props.jobsData(), e);
        }
    }

    private static Job toEntity(JobJson j) {
        if (j.id() == null || j.title() == null || j.company() == null) {
            throw new IllegalStateException("Job entry is missing id/title/company: " + j);
        }
        Job job = new Job();
        job.setId(j.id());
        job.setTitle(j.title());
        job.setCompany(j.company());
        job.setLocation(j.location() == null ? "Not specified" : j.location());
        job.setRemote(j.remote());
        job.setEmploymentType(j.employmentType() == null ? "FULL_TIME" : j.employmentType());
        job.setExperienceMin(j.experienceMin());
        job.setExperienceMax(Math.max(j.experienceMin(), j.experienceMax()));
        job.setSalaryMin(j.salaryMin());
        job.setSalaryMax(Math.max(j.salaryMin(), j.salaryMax()));
        job.setCurrency(j.currency() == null ? "INR" : j.currency());
        job.setSkills(j.skills() == null ? List.of() : j.skills());
        job.setDescription(j.description() == null ? "" : j.description());
        job.setRequirements(j.requirements() == null ? List.of() : j.requirements());
        job.setResponsibilities(j.responsibilities() == null ? List.of() : j.responsibilities());
        job.setBenefits(j.benefits() == null ? List.of() : j.benefits());
        job.setPostedDate(j.postedDate() == null ? null : LocalDate.parse(j.postedDate()));
        job.setApplicationUrl(j.applicationUrl());
        return job;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data);
            md.update(JobDocumentBuilder.CHUNKING_VERSION.getBytes());
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
