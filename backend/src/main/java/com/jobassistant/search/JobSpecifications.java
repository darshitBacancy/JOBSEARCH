package com.jobassistant.search;

import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.entity.Job;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Translates structured criteria into SQL predicates (the "structured filtering" half of hybrid search).
 */
public final class JobSpecifications {

    private JobSpecifications() {
    }

    public static Specification<Job> matching(JobSearchCriteria c, Collection<Long> restrictToIds) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (restrictToIds != null) {
                p.add(restrictToIds.isEmpty() ? cb.disjunction() : root.get("id").in(restrictToIds));
            }
            if (c.remote() != null) {
                p.add(cb.equal(root.get("remote"), c.remote()));
            }
            // When the user wants remote work, a remote job in another city is still a fit,
            // so location becomes a ranking signal instead of a hard filter.
            if (c.location() != null && !Boolean.TRUE.equals(c.remote())) {
                p.add(cb.like(cb.lower(root.get("location")), "%" + c.location().toLowerCase(Locale.ROOT) + "%"));
            }
            // Salary: the job's range must reach the requested minimum / start below the requested maximum.
            if (c.salaryMin() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("salaryMax"), c.salaryMin()));
            }
            if (c.salaryMax() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("salaryMin"), c.salaryMax()));
            }
            // Experience: the job's required range must overlap the candidate's range.
            if (c.experienceMax() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("experienceMin"), c.experienceMax()));
            }
            if (c.experienceMin() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("experienceMax"), c.experienceMin()));
            }
            if (c.employmentType() != null) {
                p.add(cb.equal(root.get("employmentType"), c.employmentType()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
