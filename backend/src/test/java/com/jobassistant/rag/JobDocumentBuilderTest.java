package com.jobassistant.rag;

import com.jobassistant.config.RagProperties;
import com.jobassistant.entity.Job;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobDocumentBuilderTest {

    private final JobDocumentBuilder builder = new JobDocumentBuilder(
            new RagProperties(null, null, null, null, 300, null, null, null, null, null, null, null));

    static Job sampleJob() {
        Job j = new Job();
        j.setId(101L);
        j.setTitle("Senior Java Developer");
        j.setCompany("Example Technologies");
        j.setLocation("Bangalore, India");
        j.setRemote(true);
        j.setEmploymentType("FULL_TIME");
        j.setExperienceMin(3);
        j.setExperienceMax(5);
        j.setSalaryMin(1_200_000);
        j.setSalaryMax(1_800_000);
        j.setCurrency("INR");
        j.setSkills(List.of("Java", "Spring Boot", "AWS", "PostgreSQL"));
        j.setDescription("Build payment services. Own the reconciliation pipeline. Work with a friendly team.");
        j.setRequirements(List.of("4+ years of Java", "Spring Boot in production", "AWS experience"));
        j.setResponsibilities(List.of("Design REST APIs", "Review code"));
        j.setBenefits(List.of("Health insurance", "Learning budget"));
        j.setPostedDate(LocalDate.of(2026, 9, 1));
        j.setApplicationUrl("https://example.com/demo-jobs/101");
        return j;
    }

    @Test
    void documentContainsAllSectionsInReadableFormat() {
        String doc = builder.toDocument(sampleJob());
        assertThat(doc).contains("Job Title: Senior Java Developer", "Company: Example Technologies",
                "Remote: Yes", "Experience: 3-5 years", "Salary: ₹12-18 LPA", "Java, Spring Boot, AWS, PostgreSQL",
                "Requirements:", "Responsibilities:", "- Design REST APIs");
    }

    @Test
    void jobIsSplitIntoSectionChunksWithMetadataLinkingBackToTheJob() {
        List<JobDocumentBuilder.Chunk> chunks = builder.chunk(sampleJob());

        assertThat(chunks).extracting(c -> c.section().name())
                .containsExactly("OVERVIEW", "SKILLS", "REQUIREMENTS", "RESPONSIBILITIES", "BENEFITS");
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.jobId()).isEqualTo(101L);
            assertThat(c.metadata()).containsEntry("jobId", "101").containsEntry("company", "Example Technologies")
                    .containsEntry("location", "Bangalore, India").containsEntry("remote", "true")
                    .containsKeys("title", "skills", "section");
            assertThat(c.documentId()).startsWith("job-101-");
        });
        // non-overview chunks carry a contextual header so each vector is self-describing
        assertThat(chunks.get(2).text()).startsWith("Job: Senior Java Developer at Example Technologies");
    }

    @Test
    void longSectionsAreSplitRespectingTheLimit() {
        Job job = sampleJob();
        job.setDescription("This sentence describes the role in some detail. ".repeat(30));
        List<JobDocumentBuilder.Chunk> overview = builder.chunk(job).stream()
                .filter(c -> c.section() == JobDocumentBuilder.Section.OVERVIEW).toList();

        assertThat(overview).hasSizeGreaterThan(1);
        assertThat(overview).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(300));
        assertThat(overview).extracting(JobDocumentBuilder.Chunk::documentId).doesNotHaveDuplicates();
    }

    @Test
    void textChunkerKeepsShortTextIntactAndOverlapsLongText() {
        assertThat(TextChunker.split("Short text.", 100)).containsExactly("Short text.");
        assertThat(TextChunker.split("  ", 100)).isEmpty();

        List<String> parts = TextChunker.split("Alpha one. Beta two. Gamma three. Delta four.", 25);
        assertThat(parts).hasSizeGreaterThan(1).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(25));
        assertThat(parts.get(1)).startsWith("Beta two."); // one sentence of overlap
    }
}
