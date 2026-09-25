package com.jobassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A job posting. This table is the source of truth for every fact the assistant shows:
 * the LLM only ever sees (and may only repeat) data that originates here.
 */
@Entity
@Table(name = "job", indexes = {
        @Index(name = "idx_job_remote", columnList = "remote"),
        @Index(name = "idx_job_salary", columnList = "salaryMin,salaryMax"),
        @Index(name = "idx_job_experience", columnList = "experienceMin,experienceMax")
})
public class Job {

    @Id
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String company;

    @Column(nullable = false)
    private String location;

    private boolean remote;

    @Column(nullable = false)
    private String employmentType;

    private int experienceMin;
    private int experienceMax;
    private long salaryMin;
    private long salaryMax;

    @Column(length = 8)
    private String currency;

    @Convert(converter = StringListConverter.class)
    @Column(length = 2000)
    private List<String> skills = new ArrayList<>();

    @Lob
    @Column(columnDefinition = "CLOB")
    private String description;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<String> requirements = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<String> responsibilities = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<String> benefits = new ArrayList<>();

    private LocalDate postedDate;

    private String applicationUrl;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public boolean isRemote() { return remote; }
    public void setRemote(boolean remote) { this.remote = remote; }
    public String getEmploymentType() { return employmentType; }
    public void setEmploymentType(String employmentType) { this.employmentType = employmentType; }
    public int getExperienceMin() { return experienceMin; }
    public void setExperienceMin(int experienceMin) { this.experienceMin = experienceMin; }
    public int getExperienceMax() { return experienceMax; }
    public void setExperienceMax(int experienceMax) { this.experienceMax = experienceMax; }
    public long getSalaryMin() { return salaryMin; }
    public void setSalaryMin(long salaryMin) { this.salaryMin = salaryMin; }
    public long getSalaryMax() { return salaryMax; }
    public void setSalaryMax(long salaryMax) { this.salaryMax = salaryMax; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getRequirements() { return requirements; }
    public void setRequirements(List<String> requirements) { this.requirements = requirements; }
    public List<String> getResponsibilities() { return responsibilities; }
    public void setResponsibilities(List<String> responsibilities) { this.responsibilities = responsibilities; }
    public List<String> getBenefits() { return benefits; }
    public void setBenefits(List<String> benefits) { this.benefits = benefits; }
    public LocalDate getPostedDate() { return postedDate; }
    public void setPostedDate(LocalDate postedDate) { this.postedDate = postedDate; }
    public String getApplicationUrl() { return applicationUrl; }
    public void setApplicationUrl(String applicationUrl) { this.applicationUrl = applicationUrl; }
}
