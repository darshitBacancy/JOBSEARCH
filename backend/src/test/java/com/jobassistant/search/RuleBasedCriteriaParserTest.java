package com.jobassistant.search;

import com.jobassistant.conversation.ChatIntent;
import com.jobassistant.dto.JobSearchCriteria;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedCriteriaParserTest {

    private final RuleBasedCriteriaParser parser = new RuleBasedCriteriaParser(new SkillCatalog(), new LocationNormalizer());

    @Test
    void extractsSkillsExperienceRemoteLocationAndSalary() {
        QueryAnalysis a = parser.parse("I'm a Java developer with 4 years of experience. Find me remote jobs in India "
                + "with Spring Boot and AWS and salary above ₹10 LPA.", false);
        JobSearchCriteria c = a.criteria();

        assertThat(a.intent()).isEqualTo(ChatIntent.NEW_SEARCH);
        assertThat(c.skills()).containsExactly("Java", "Spring Boot", "AWS");
        assertThat(c.experienceMin()).isEqualTo(4);
        assertThat(c.experienceMax()).isEqualTo(4);
        assertThat(c.remote()).isTrue();
        assertThat(c.location()).isEqualTo("India");
        assertThat(c.salaryMin()).isEqualTo(1_000_000L);
    }

    @Test
    void understandsRangesAndUpperBounds() {
        JobSearchCriteria range = parser.parse("Find React jobs for 3-5 years experience", false).criteria();
        assertThat(range.skills()).containsExactly("React");
        assertThat(range.experienceMin()).isEqualTo(3);
        assertThat(range.experienceMax()).isEqualTo(5);

        JobSearchCriteria less = parser.parse("Only show jobs with less than 5 years experience", true).criteria();
        assertThat(less.experienceMin()).isNull();
        assertThat(less.experienceMax()).isEqualTo(4);

        JobSearchCriteria salary = parser.parse("jobs between 12-18 LPA", false).criteria();
        assertThat(salary.salaryMin()).isEqualTo(1_200_000L);
        assertThat(salary.salaryMax()).isEqualTo(1_800_000L);
    }

    @Test
    void doesNotConfuseSimilarSkillNames() {
        assertThat(parser.parse("JavaScript and React Native jobs", false).criteria().skills())
                .containsExactly("JavaScript", "React Native");
        assertThat(parser.parse("k8s and golang in Bengaluru", false).criteria())
                .satisfies(c -> {
                    assertThat(c.skills()).containsExactly("Kubernetes", "Go");
                    assertThat(c.location()).isEqualTo("Bangalore");
                });
        assertThat(parser.parse("I want to go remote", false).criteria().skills()).isEmpty();
    }

    @Test
    void classifiesIntentsAndReferences() {
        QueryAnalysis compare = parser.parse("Compare the first three jobs", true);
        assertThat(compare.intent()).isEqualTo(ChatIntent.COMPARE);
        assertThat(compare.ordinals()).containsExactly(1, 2, 3);

        QueryAnalysis question = parser.parse("What skills does this job require?", true);
        assertThat(question.intent()).isEqualTo(ChatIntent.JOB_QUESTION);

        QueryAnalysis byId = parser.parse("What are the benefits of job #118?", true);
        assertThat(byId.intent()).isEqualTo(ChatIntent.JOB_QUESTION);
        assertThat(byId.jobIds()).containsExactly(118L);

        assertThat(parser.parse("Only those above ₹15 LPA", true).intent()).isEqualTo(ChatIntent.REFINE);
        assertThat(parser.parse("Only those above ₹15 LPA", false).intent()).isEqualTo(ChatIntent.NEW_SEARCH);
        assertThat(parser.parse("hello there", false).intent()).isEqualTo(ChatIntent.GENERAL);
    }

    @Test
    void keepsTopicalKeywordsAndDropsFiller() {
        JobSearchCriteria c = parser.parse("Find me backend development jobs in fintech", false).criteria();
        assertThat(c.keywords()).contains("backend", "fintech").doesNotContain("find", "job", "jobs");
    }
}
