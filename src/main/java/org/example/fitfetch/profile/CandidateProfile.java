package org.example.fitfetch.profile;

import org.example.fitfetch.normalize.Degree;
import org.example.fitfetch.normalize.EmploymentType;
import org.example.fitfetch.normalize.Seniority;
import org.example.fitfetch.normalize.Track;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * The candidate's side of a match, as written by hand in {@code profile.yaml}.
 *
 * <p>It mirrors what normalization extracts from a job, so the two compare field
 * for field: {@link Summary} against a job's seniority, track and hard
 * requirements, the skills against its signals' skills, and the bullets against
 * its signals.
 *
 * <p>The roles hold every bullet worth using, more than fit on one resume. A
 * tailored resume picks among them, so each bullet has a stable id and is
 * written exactly as it should appear.
 *
 * <p>YAML keys are the snake_case of the component names. Lists left out are
 * empty. {@link ProfileLoader} checks everything else.
 *
 * @param summary     the facts a job's hard requirements are checked against
 * @param preferences what to filter jobs by; permissive where left out
 * @param skills      skills with years of experience, by canonical name
 * @param roles       jobs held, each with its bullets
 */
public record CandidateProfile(Summary summary, Preferences preferences, List<Skill> skills, List<Role> roles) {

    public CandidateProfile {
        preferences = preferences == null ? Preferences.ANY : preferences;
        skills = skills == null ? List.of() : List.copyOf(skills);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /**
     * Facts about the candidate. Every field is required; they are boxed so a
     * missing one can be reported rather than read as 0 or false.
     *
     * @param totalYears       years of professional experience
     * @param level            the level currently held, in normalization's bands
     * @param track            individual contributor or people manager
     * @param degree           the highest degree held
     * @param certifications   certifications held, by common name
     * @param clearance        whether a security clearance is held
     * @param needsSponsorship whether a work visa must be sponsored
     */
    public record Summary(Integer totalYears, Seniority level, Track track, Degree degree,
                          List<String> certifications, Boolean clearance, Boolean needsSponsorship) {

        public Summary {
            certifications = certifications == null ? List.of() : List.copyOf(certifications);
        }
    }

    /**
     * What to filter jobs by. Each field left out places no restriction.
     *
     * @param employmentTypes  terms to accept; empty accepts any
     * @param travelOk         whether roles requiring travel are acceptable
     * @param onCallOk         whether roles with an on-call rotation are
     *                         acceptable
     * @param preferredDomains domains to favor, lower case
     * @param avoidedDomains   domains to rank down, lower case
     */
    public record Preferences(List<EmploymentType> employmentTypes, Boolean travelOk, Boolean onCallOk,
                              List<String> preferredDomains, List<String> avoidedDomains) {

        /** No restriction at all. */
        public static final Preferences ANY = new Preferences(List.of(), true, true, List.of(), List.of());

        public Preferences {
            employmentTypes = employmentTypes == null ? List.of() : List.copyOf(employmentTypes);
            travelOk = travelOk == null || travelOk;
            onCallOk = onCallOk == null || onCallOk;
            preferredDomains = preferredDomains == null ? List.of() : List.copyOf(preferredDomains);
            avoidedDomains = avoidedDomains == null ? List.of() : List.copyOf(avoidedDomains);
        }
    }

    /**
     * @param name  the skill, by canonical name
     * @param years years of experience with it
     */
    public record Skill(String name, Integer years) {
    }

    /**
     * A job held. Bullets belong to their role and never move to another.
     *
     * @param id      stable id, lower case with hyphens
     * @param company the employer
     * @param title   the title held
     * @param start   the first month, as {@code 2021-03}
     * @param end     the last month as {@code 2023-05}, or {@code present}
     * @param bullets what was done there
     */
    public record Role(String id, String company, String title, YearMonth start, String end, List<Bullet> bullets) {

        /** The {@link #end()} of a role still held. */
        public static final String PRESENT = "present";

        public Role {
            bullets = bullets == null ? List.of() : List.copyOf(bullets);
        }

        /** @return the last month, or empty for a role still held */
        public Optional<YearMonth> endMonth() {
            return PRESENT.equalsIgnoreCase(end) ? Optional.empty() : Optional.of(YearMonth.parse(end));
        }
    }

    /**
     * One thing done in a role, written exactly as it should appear on a resume.
     *
     * @param id     stable id, unique across the profile, lower case with
     *               hyphens. Matches and embeddings refer to it, so the text can
     *               be reworded without losing them
     * @param text   one self-contained sentence
     * @param skills skills it demonstrates, by canonical name
     * @param pinned whether every tailored resume includes it
     */
    public record Bullet(String id, String text, List<String> skills, boolean pinned) {

        public Bullet {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }
}
