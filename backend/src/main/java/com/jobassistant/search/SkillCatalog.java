package com.jobassistant.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical skill vocabulary with aliases ("k8s" -> Kubernetes, "springboot" -> Spring Boot).
 * Used to detect skills in free text and to canonicalise skills returned by the LLM.
 */
@Component
public class SkillCatalog {

    private static final List<String> CANONICAL = List.of(
            "Java", "Spring Boot", "Spring Cloud", "Hibernate", "Microservices", "Kafka", "AWS", "Azure", "GCP",
            "Docker", "Kubernetes", "Terraform", "Jenkins", "GitHub Actions", "PostgreSQL", "MySQL", "MongoDB",
            "Redis", "Elasticsearch", "REST APIs", "GraphQL", "JavaScript", "TypeScript", "React", "Next.js",
            "Redux", "Angular", "Vue.js", "HTML", "CSS", "Tailwind CSS", "Node.js", "Express", "NestJS", "Python",
            "Django", "FastAPI", "Flask", "Pandas", "NumPy", "Machine Learning", "Deep Learning", "PyTorch",
            "TensorFlow", "NLP", "LLMs", "Spark", "Airflow", "Snowflake", "SQL", "dbt", "Power BI", "Tableau", "Go",
            "Rust", "C#", ".NET", "Kotlin", "Swift", "Android", "iOS", "Flutter", "React Native", "Selenium",
            "Cypress", "Playwright", "JUnit", "Linux", "Prometheus", "Grafana", "Figma", "Scala", "Salesforce",
            "SAP", "Cybersecurity", "Networking", "Git", "CI/CD", "Agile");

    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("Spring Boot", List.of("springboot", "spring-boot", "spring")),
            Map.entry("Spring Cloud", List.of("spring-cloud")),
            Map.entry("Hibernate", List.of("jpa")),
            Map.entry("Microservices", List.of("microservice", "micro services", "micro-services")),
            Map.entry("Kafka", List.of("apache kafka")),
            Map.entry("AWS", List.of("amazon web services")),
            Map.entry("GCP", List.of("google cloud", "google cloud platform")),
            Map.entry("Kubernetes", List.of("k8s")),
            Map.entry("PostgreSQL", List.of("postgres", "postgre sql")),
            Map.entry("MongoDB", List.of("mongo")),
            Map.entry("REST APIs", List.of("rest api", "restful", "rest", "restful apis")),
            Map.entry("JavaScript", List.of("js", "ecmascript")),
            Map.entry("React", List.of("reactjs", "react.js")),
            Map.entry("Next.js", List.of("nextjs")),
            Map.entry("Vue.js", List.of("vue", "vuejs")),
            Map.entry("Tailwind CSS", List.of("tailwind")),
            Map.entry("Node.js", List.of("node", "nodejs", "node js")),
            Map.entry("Express", List.of("express.js", "expressjs")),
            Map.entry("NestJS", List.of("nest.js", "nest")),
            Map.entry("Machine Learning", List.of("ml")),
            Map.entry("Deep Learning", List.of("dl")),
            Map.entry("LLMs", List.of("llm", "genai", "gen ai", "generative ai", "large language models")),
            Map.entry("Spark", List.of("apache spark", "pyspark")),
            Map.entry("Airflow", List.of("apache airflow")),
            Map.entry("Power BI", List.of("powerbi")),
            Map.entry("Go", List.of("golang")),
            Map.entry("C#", List.of("csharp", "c sharp")),
            Map.entry(".NET", List.of("dotnet", "asp.net", ".net core")),
            Map.entry("React Native", List.of("react-native")),
            Map.entry("Cybersecurity", List.of("cyber security", "infosec", "security")),
            Map.entry("CI/CD", List.of("cicd", "ci cd", "continuous integration")),
            Map.entry("iOS", List.of("ios")));

    /** Aliases that are common English words: only matched with their exact canonical casing. */
    private static final Set<String> CASE_SENSITIVE = Set.of("Go", "Express", "Swift", "Rust", "Spark", "Flutter", "SAP", "Git");

    private record Entry(String canonical, Pattern pattern, int length) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, String> lookup = new LinkedHashMap<>();

    public SkillCatalog() {
        for (String skill : CANONICAL) {
            register(skill, skill, CASE_SENSITIVE.contains(skill));
            lookup.put(normalise(skill), skill);
            for (String alias : ALIASES.getOrDefault(skill, List.of())) {
                register(skill, alias, false);
                lookup.put(normalise(alias), skill);
            }
        }
        // longest surface forms first, so "React Native" wins over "React" and "GitHub Actions" over "Git"
        entries.sort(Comparator.comparingInt(Entry::length).reversed());
    }

    private void register(String canonical, String surface, boolean caseSensitive) {
        String regex = "(?<![A-Za-z0-9])" + Pattern.quote(surface) + "(?![A-Za-z0-9#])";
        Pattern p = caseSensitive ? Pattern.compile(regex) : Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        entries.add(new Entry(canonical, p, surface.length()));
    }

    /** Skills mentioned in free text, in canonical form, in order of first appearance. */
    public List<String> detect(String text) {
        if (text == null || text.isBlank()) return List.of();
        StringBuilder masked = new StringBuilder(text);
        Map<Integer, String> found = new java.util.TreeMap<>();
        for (Entry e : entries) {
            Matcher m = e.pattern.matcher(masked);
            while (m.find()) {
                found.putIfAbsent(m.start(), e.canonical);
                for (int i = m.start(); i < m.end(); i++) masked.setCharAt(i, ' '); // consume the match
                m = e.pattern.matcher(masked);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(found.values()));
    }

    /** Canonical form of a single skill name, if it is known. */
    public Optional<String> canonicalise(String skill) {
        if (skill == null) return Optional.empty();
        return Optional.ofNullable(lookup.get(normalise(skill)));
    }

    public List<String> all() {
        return CANONICAL;
    }

    private static String normalise(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
