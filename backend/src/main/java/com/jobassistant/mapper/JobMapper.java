package com.jobassistant.mapper;

import com.jobassistant.dto.JobCardDto;
import com.jobassistant.dto.JobDetailDto;
import com.jobassistant.entity.Job;
import com.jobassistant.search.RankedJob;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class JobMapper {

    public static final String DATA_NOTICE =
            "Demo data: this is a fictional sample listing created for demonstration purposes, not a real vacancy.";

    public JobCardDto toCard(Job job) {
        return toCard(job, null);
    }

    public JobCardDto toCard(Job job, RankedJob ranked) {
        return new JobCardDto(job.getId(), job.getTitle(), job.getCompany(), job.getLocation(), job.isRemote(),
                job.getEmploymentType(), employmentTypeLabel(job.getEmploymentType()),
                formatExperience(job.getExperienceMin(), job.getExperienceMax()),
                job.getExperienceMin(), job.getExperienceMax(),
                formatSalary(job.getSalaryMin(), job.getSalaryMax(), job.getCurrency()),
                job.getSalaryMin(), job.getSalaryMax(), job.getCurrency(), List.copyOf(job.getSkills()),
                job.getPostedDate() == null ? null : job.getPostedDate().toString(),
                ranked == null ? null : ranked.matchPercent(),
                ranked == null ? null : ranked.matchLabel(),
                ranked == null ? List.of() : ranked.reasons(),
                ranked == null ? List.of() : ranked.gaps(),
                ranked == null ? null : ranked.breakdown());
    }

    public JobDetailDto toDetail(Job job) {
        JobCardDto c = toCard(job);
        return new JobDetailDto(c.id(), c.title(), c.company(), c.location(), c.remote(), c.employmentType(),
                c.employmentTypeLabel(), c.experience(), c.experienceMin(), c.experienceMax(), c.salary(),
                c.salaryMin(), c.salaryMax(), c.currency(), c.skills(), c.postedDate(), null, null, List.of(), List.of(),
                (Map<String, Double>) null, job.getDescription(), List.copyOf(job.getRequirements()),
                List.copyOf(job.getResponsibilities()), List.copyOf(job.getBenefits()), job.getApplicationUrl(),
                DATA_NOTICE);
    }

    /** 1200000..1800000 INR -> "₹12-18 LPA". */
    public static String formatSalary(long min, long max, String currency) {
        if (currency == null || currency.equalsIgnoreCase("INR")) {
            String lo = lakhs(min);
            String hi = lakhs(max);
            return "₹" + (lo.equals(hi) ? lo : lo + "-" + hi) + " LPA";
        }
        return currency + " " + String.format(Locale.ROOT, "%,d-%,d", min, max);
    }

    public static String lakhs(long amount) {
        return new BigDecimal(amount).divide(BigDecimal.valueOf(100_000), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    public static String formatExperience(int min, int max) {
        if (min == max) return min + (min == 1 ? " year" : " years");
        return min + "-" + max + " years";
    }

    public static String employmentTypeLabel(String type) {
        if (type == null) return "Not specified";
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "FULL_TIME" -> "Full-time";
            case "PART_TIME" -> "Part-time";
            case "CONTRACT" -> "Contract";
            case "INTERNSHIP" -> "Internship";
            default -> type;
        };
    }
}
