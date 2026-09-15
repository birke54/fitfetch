package org.example.fitfetch.match;

import org.example.fitfetch.embedding.Vectors;
import org.example.fitfetch.match.MatchResult.BulletMatch;
import org.example.fitfetch.match.MatchResult.ScoreParts;
import org.example.fitfetch.match.MatchResult.SignalMatch;
import org.example.fitfetch.normalize.NormalizedData;
import org.example.fitfetch.normalize.Signal;
import org.example.fitfetch.normalize.SignalClassification;
import org.example.fitfetch.profile.CandidateProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scores how well a job matches the candidate, from what normalization
 * extracted, the signals' vectors and the profile's bullet vectors.
 *
 * <p>The score, 0 to 100, is a weighted sum of three parts, plus a small
 * adjustment for the job's domain:
 *
 * <ul>
 *   <li><strong>Requirement coverage</strong> ({@value #COVERAGE_WEIGHT}):
 *       for each signal, the better of how similar the best bullet is and how
 *       many of the signal's skills the profile has, weighted by the signal's
 *       section. A required skill counts more than a responsibility, which
 *       counts more than a nice-to-have.</li>
 *   <li><strong>Skill match</strong> ({@value #SKILL_WEIGHT}): the share of
 *       the skills named in required signals that the profile has.</li>
 *   <li><strong>Level fit</strong> ({@value #LEVEL_WEIGHT}): how close the
 *       job's seniority band is to the profile's, and the profile's years
 *       against the job's minimum.</li>
 * </ul>
 *
 * <p>Every number here is a first guess, to be tuned against jobs labeled good
 * or bad by hand. {@link #VERSION} goes up with any change, so every job is
 * scored again under the new rules.
 *
 * <p>Pure: no state, no I/O. Instances are safe to share.
 */
public class MatchScorer {

    /** Version of the scoring rules; increment on any change to them. */
    public static final int VERSION = 1;

    /**
     * Below this cosine similarity a bullet says nothing about a signal; at
     * {@link #SIMILARITY_FULL} and above it covers it. Between the two,
     * coverage rises linearly. Set for {@code nomic-embed-text}, whose related
     * sentences typically score 0.6 to 0.8.
     */
    static final double SIMILARITY_FLOOR = 0.55;
    static final double SIMILARITY_FULL = 0.80;

    static final double COVERAGE_WEIGHT = 0.6;
    static final double SKILL_WEIGHT = 0.2;
    static final double LEVEL_WEIGHT = 0.2;

    /** Points for a job in a domain the profile prefers, and against one it avoids. */
    static final int PREFERRED_DOMAIN_POINTS = 5;
    static final int AVOIDED_DOMAIN_POINTS = -15;

    /** Bullets kept per signal: enough to tailor a resume from, few enough to store. */
    static final int BEST_BULLETS = 3;

    /**
     * @param job           what normalization extracted from the job
     * @param signalVectors one vector per signal, in the job's signal order
     * @param profile       the candidate
     * @param bulletVectors each bullet's vector, by bullet id
     * @return the score and its reasons
     */
    public MatchResult score(NormalizedData job, List<float[]> signalVectors, CandidateProfile profile,
                             Map<String, float[]> bulletVectors) {
        if (signalVectors.size() != job.signals().size()) {
            throw new IllegalArgumentException(signalVectors.size() + " vectors for " + job.signals().size()
                    + " signals");
        }
        Map<String, Integer> yearsBySkill = new HashMap<>();
        profile.skills().forEach(skill -> yearsBySkill.put(key(skill.name()), skill.years()));

        List<SignalMatch> signals = new ArrayList<>(job.signals().size());
        double weighted = 0;
        double totalWeight = 0;
        for (int i = 0; i < job.signals().size(); i++) {
            Signal signal = job.signals().get(i);
            SignalMatch match = matchSignal(i, signal, signalVectors.get(i), bulletVectors, yearsBySkill);
            signals.add(match);
            double weight = weight(signal.classification());
            weighted += weight * match.coverage();
            totalWeight += weight;
        }
        double requirementCoverage = totalWeight == 0 ? 0 : weighted / totalWeight;
        double skillMatch = skillMatch(job, yearsBySkill);
        double levelFit = levelFit(job, profile.summary());
        int domainAdjustment = domainAdjustment(job, profile.preferences());

        double raw = 100 * (COVERAGE_WEIGHT * requirementCoverage + SKILL_WEIGHT * skillMatch
                + LEVEL_WEIGHT * levelFit) + domainAdjustment;
        int score = (int) Math.round(Math.max(0, Math.min(100, raw)));
        List<String> gateFailures = Gates.failures(job, profile);
        return new MatchResult(score, gateFailures.isEmpty(), gateFailures,
                new ScoreParts(requirementCoverage, skillMatch, levelFit, domainAdjustment), signals);
    }

    private static SignalMatch matchSignal(int index, Signal signal, float[] vector,
                                           Map<String, float[]> bulletVectors, Map<String, Integer> yearsBySkill) {
        List<BulletMatch> ranked = new ArrayList<>(bulletVectors.size());
        bulletVectors.forEach((id, bullet) -> ranked.add(new BulletMatch(id, Vectors.cosine(vector, bullet))));
        ranked.sort(Comparator.comparingDouble(BulletMatch::similarity).reversed()
                .thenComparing(BulletMatch::bulletId));
        List<BulletMatch> best = ranked.subList(0, Math.min(BEST_BULLETS, ranked.size()));
        double semantic = best.isEmpty() ? 0 : ramp(best.getFirst().similarity());

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String skill : signal.skills()) {
            Integer years = yearsBySkill.get(key(skill));
            if (years != null && years >= signal.minYears()) {
                matched.add(skill);
            } else {
                missing.add(skill);
            }
        }
        double bySkills = signal.skills().isEmpty() ? 0 : (double) matched.size() / signal.skills().size();
        return new SignalMatch(index, signal.classification(), Math.max(semantic, bySkills),
                new ArrayList<>(best), matched, missing);
    }

    /** @return 0 below the floor, 1 at or above full, linear between */
    static double ramp(double similarity) {
        if (similarity <= SIMILARITY_FLOOR) {
            return 0;
        }
        if (similarity >= SIMILARITY_FULL) {
            return 1;
        }
        return (similarity - SIMILARITY_FLOOR) / (SIMILARITY_FULL - SIMILARITY_FLOOR);
    }

    /** @return how much a signal of this section counts towards coverage */
    static double weight(SignalClassification classification) {
        return switch (classification) {
            case REQUIRED_SKILL, REQUIRED_QUALIFICATION -> 1.0;
            case CORE_RESPONSIBILITY -> 0.7;
            case PREFERRED_SKILL, PREFERRED_QUALIFICATION -> 0.4;
            case OTHER -> 0.3;
        };
    }

    /**
     * @return the share of distinct skills in required signals that the profile
     *         has at all; 1 if they name none. Years are judged per signal, in
     *         coverage
     */
    private static double skillMatch(NormalizedData job, Map<String, Integer> yearsBySkill) {
        Set<String> required = new LinkedHashSet<>();
        for (Signal signal : job.signals()) {
            if (signal.classification() == SignalClassification.REQUIRED_SKILL
                    || signal.classification() == SignalClassification.REQUIRED_QUALIFICATION) {
                signal.skills().forEach(skill -> required.add(key(skill)));
            }
        }
        if (required.isEmpty()) {
            return 1;
        }
        long held = required.stream().filter(yearsBySkill::containsKey).count();
        return (double) held / required.size();
    }

    /** @return the average of seniority fit and years fit */
    private static double levelFit(NormalizedData job, CandidateProfile.Summary summary) {
        int bands = Math.abs(job.seniority().ordinal() - summary.level().ordinal());
        double seniorityFit = switch (bands) {
            case 0 -> 1.0;
            case 1 -> 0.7;
            case 2 -> 0.3;
            default -> 0.0;
        };
        double yearsFit = job.minYearsExperience() == 0 || summary.totalYears() >= job.minYearsExperience()
                ? 1.0
                : (double) summary.totalYears() / job.minYearsExperience();
        return (seniorityFit + yearsFit) / 2;
    }

    /**
     * @return {@link #AVOIDED_DOMAIN_POINTS} if any of the job's domains is one
     *         the profile avoids, else {@link #PREFERRED_DOMAIN_POINTS} if any is
     *         one it prefers, else 0. A domain matches when either phrase
     *         contains the other, so "payments" matches "b2b payments"
     */
    private static int domainAdjustment(NormalizedData job, CandidateProfile.Preferences preferences) {
        if (overlaps(job.domains(), preferences.avoidedDomains())) {
            return AVOIDED_DOMAIN_POINTS;
        }
        if (overlaps(job.domains(), preferences.preferredDomains())) {
            return PREFERRED_DOMAIN_POINTS;
        }
        return 0;
    }

    private static boolean overlaps(List<String> jobDomains, List<String> profileDomains) {
        for (String jobDomain : jobDomains) {
            for (String profileDomain : profileDomains) {
                String a = key(jobDomain);
                String b = key(profileDomain);
                if (a.contains(b) || b.contains(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String key(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
