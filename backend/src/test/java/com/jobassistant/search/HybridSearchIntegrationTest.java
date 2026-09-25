package com.jobassistant.search;

import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.entity.Job;
import com.jobassistant.rag.RagService;
import com.jobassistant.rag.Retriever;
import com.jobassistant.rag.ScoredChunk;
import com.jobassistant.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class HybridSearchIntegrationTest {

    @Autowired
    RagService rag;
    @Autowired
    JobRepository jobRepository;

    private static JobSearchCriteria criteria(List<String> skills, String location, Boolean remote,
                                              Integer expMin, Integer expMax, Long salaryMin) {
        return new JobSearchCriteria(null, skills, location, remote, expMin, expMax, salaryMin, null, null);
    }

    // ---------------------------------------------------------------- retrieval

    @Test
    void vectorRetrievalReturnsRelevantChunksSortedBySimilarity() {
        Retriever.Result r = rag.retrieveRelevantDocuments("Kubernetes Terraform infrastructure automation", 10, null);

        assertThat(r.mode()).isEqualTo("vector");
        assertThat(r.chunks()).hasSize(10);
        for (int i = 1; i < r.chunks().size(); i++) {
            assertThat(r.chunks().get(i - 1).score()).isGreaterThanOrEqualTo(r.chunks().get(i).score());
        }
        Job best = jobRepository.findById(r.chunks().get(0).record().jobId()).orElseThrow();
        assertThat(best.getSkills()).containsAnyOf("Kubernetes", "Terraform");
    }

    @Test
    void retrievalCanBeRestrictedToCandidateJobs() {
        Retriever.Result r = rag.retrieveRelevantDocuments("benefits and insurance", 5, Set.of(109L));
        assertThat(r.chunks()).isNotEmpty().extracting(c -> c.record().jobId()).containsOnly(109L);
        assertThat(r.chunks().get(0).record().section()).isEqualTo("BENEFITS");
    }

    @Test
    void emptyRetrievalIsHandled() {
        assertThat(rag.retrieveRelevantDocuments("java", 5, Set.of()).chunks()).isEmpty();
        assertThat(rag.retrieveRelevantDocuments("  ", 5, null).mode()).isEqualTo("none");
    }

    // ---------------------------------------------------------------- hybrid search

    @Test
    void skillMatchingKeepsOnlyJobsWithTheRequestedSkillsAndRanksFullMatchesFirst() {
        var outcome = rag.search(criteria(List.of("Java", "Spring Boot", "AWS"), null, null, null, null, null),
                "Java Spring Boot AWS developer", null);

        assertThat(outcome.ranked()).isNotEmpty().allSatisfy(r ->
                assertThat(r.job().getSkills()).containsAnyOf("Java", "Spring Boot", "AWS"));
        RankedJob top = outcome.ranked().get(0);
        assertThat(top.job().getSkills()).contains("Java", "Spring Boot", "AWS");
        assertThat(top.breakdown().get("skills")).isEqualTo(1.0);
        assertThat(top.reasons()).anyMatch(s -> s.contains("Java, Spring Boot and AWS match"));
    }

    @Test
    void salaryFilteringUsesTheJobsRange() {
        var outcome = rag.search(criteria(List.of("Java"), null, null, null, null, 2_000_000L), "Java", null);
        assertThat(outcome.ranked()).isNotEmpty()
                .allSatisfy(r -> assertThat(r.job().getSalaryMax()).isGreaterThanOrEqualTo(2_000_000L));
        // jobs whose whole range meets the minimum rank above those that only reach it at the top end
        RankedJob first = outcome.ranked().get(0);
        assertThat(first.breakdown().get("salary")).isEqualTo(1.0);
    }

    @Test
    void experienceFilteringRequiresOverlapWithTheCandidatesYears() {
        var outcome = rag.search(criteria(List.of("React"), null, null, 4, 4, null), "React developer", null);
        assertThat(outcome.ranked()).isNotEmpty().allSatisfy(r -> {
            assertThat(r.job().getExperienceMin()).isLessThanOrEqualTo(4);
            assertThat(r.job().getExperienceMax()).isGreaterThanOrEqualTo(4);
        });

        var lessThanFive = rag.search(criteria(List.of(), null, null, null, 4, null), "", null);
        assertThat(lessThanFive.ranked()).allSatisfy(r -> assertThat(r.job().getExperienceMin()).isLessThanOrEqualTo(4));
    }

    @Test
    void remoteFilteringReturnsOnlyRemoteJobs() {
        var outcome = rag.search(criteria(List.of("AWS"), null, true, null, null, null), "remote AWS jobs", null);
        assertThat(outcome.totalMatches()).isGreaterThan(5);
        assertThat(outcome.ranked()).allSatisfy(r -> assertThat(r.job().isRemote()).isTrue());
    }

    @Test
    void locationFilteringMatchesTheCity() {
        var outcome = rag.search(criteria(List.of("React"), "Bangalore", null, null, null, null), "React jobs in Bangalore", null);
        assertThat(outcome.ranked()).isNotEmpty()
                .allSatisfy(r -> assertThat(r.job().getLocation()).contains("Bangalore"));
    }

    @Test
    void unknownTopicYieldsAnHonestEmptyResult() {
        var outcome = rag.search(new JobSearchCriteria(List.of("cobol", "mainframe"), null, null, null, null, null,
                null, null, null), "COBOL mainframe jobs", null);
        assertThat(outcome.ranked()).isEmpty();
        assertThat(outcome.totalMatches()).isZero();
    }

    @Test
    void impossibleFiltersAreRelaxedAndReported() {
        var outcome = rag.search(criteria(List.of("React"), null, null, null, null, 90_000_000L), "React", null);
        assertThat(outcome.relaxedFilters()).containsExactly("salary");
        assertThat(outcome.ranked()).isNotEmpty();
        assertThat(outcome.ranked().get(0).gaps()).anyMatch(g -> g.contains("below your ₹900 LPA minimum"));
    }

    @Test
    void refinementIsRestrictedToThePreviousCandidates() {
        List<Long> previous = List.of(104L, 108L, 110L, 112L);
        var outcome = rag.search(criteria(List.of("Java"), null, null, null, null, 1_500_000L), "Java", previous);
        assertThat(outcome.ranked()).extracting(r -> r.job().getId()).isSubsetOf(previous);
    }

    @Test
    void semanticScoresAreRelativeToTheBestRetrievedJob() {
        var outcome = rag.search(criteria(List.of("Python"), null, null, null, null, null), "Python FastAPI backend", null);
        double maxSemantic = outcome.ranked().stream().map(r -> r.breakdown().get("semantic"))
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
        assertThat(maxSemantic).isEqualTo(1.0);
        assertThat(outcome.ranked().get(0).chunks()).extracting(ScoredChunk::score).isNotEmpty();
    }
}
