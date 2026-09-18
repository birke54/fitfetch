package org.example.fitfetch.match;

import org.example.fitfetch.embedding.Vectors;
import org.example.fitfetch.match.MatchResult.BulletMatch;
import org.example.fitfetch.match.MatchResult.ScoreParts;
import org.example.fitfetch.match.MatchResult.SignalMatch;
import org.example.fitfetch.normalize.NormalizedData;
import org.example.fitfetch.normalize.Signal;
import org.example.fitfetch.normalize.SignalClassification;
import org.example.fitfetch.normalize.SkillAlternatives;
import org.example.fitfetch.profile.CandidateProfile;
import org.example.fitfetch.skills.SkillCanonicalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
 *       counts more than a nice-to-have. A signal's alternatives ("one of AWS,
 *       Azure, or GCP") count as one skill, met by any of them.</li>
 *   <li><strong>Skill match</strong> ({@value #SKILL_WEIGHT}): the share of
 *       the skills named in required signals that the profile has, each set
 *       of alternatives again counting as one. A job naming none this side
 *       knows is scored on the other two parts instead, weighed against each
 *       other: it cannot be told apart by a skill, and handing every candidate
 *       the full share would only lift it above jobs that can.</li>
 *   <li><strong>Level fit</strong> ({@value #LEVEL_WEIGHT}): how close the
 *       job's seniority band is to the profile's, and the profile's years
 *       against the job's minimum.</li>
 * </ul>
 *
 * <p>Only skills the table knows, or the profile lists, count in either part.
 * The model fills a signal's skills with phrases from its own text ("bounded
 * suppression", "exit codes", "written and verbal communication"), and counting
 * those, which no profile ever matches, would lower every job's skill match and
 * push jobs with wordier answers down the ranking. Each one is kept in the
 * match's reasons, so real skills the table lacks can be added to it. Skills
 * also go through the table as they are read, so an alias added later reaches
 * jobs normalized before it.
 *
 * <p>Every number here is a first guess, to be tuned against jobs labeled good
 * or bad by hand. {@link #VERSION} goes up with any change, so every job is
 * scored again under the new rules.
 *
 * <p>No I/O, and the table is immutable. Instances are safe to share.
 */
public class MatchScorer {

    /** Version of the scoring rules; increment on any change to them. */
    public static final int VERSION = 8;

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

    private final SkillCanonicalizer skills;

    /**
     * @param skills the skill table: what it knows, plus what the profile
     *               lists, is what counts as a skill here
     */
    public MatchScorer(SkillCanonicalizer skills) {
        this.skills = Objects.requireNonNull(skills, "skills");
    }

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
        profile.skills().forEach(skill -> yearsBySkill.put(key(skills.canonical(skill.name())), skill.years()));

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
        Double skillMatch = skillMatch(job, yearsBySkill);
        double levelFit = levelFit(job, profile.summary());
        int domainAdjustment = domainAdjustment(job, profile.preferences());

        // A job naming no skill this side knows cannot be told apart by one, so
        // its share goes to the parts that can, rather than to every candidate.
        double scored = COVERAGE_WEIGHT * requirementCoverage + LEVEL_WEIGHT * levelFit
                + (skillMatch == null ? 0 : SKILL_WEIGHT * skillMatch);
        double weightInPlay = COVERAGE_WEIGHT + LEVEL_WEIGHT + (skillMatch == null ? 0 : SKILL_WEIGHT);
        double raw = 100 * scored / weightInPlay + domainAdjustment;
        int score = (int) Math.round(Math.max(0, Math.min(100, raw)));
        List<String> gateFailures = Gates.failures(job, profile);
        return new MatchResult(score, gateFailures.isEmpty(), gateFailures,
                new ScoreParts(requirementCoverage, skillMatch, levelFit, domainAdjustment), signals);
    }

    private SignalMatch matchSignal(int index, Signal signal, float[] vector,
                                    Map<String, float[]> bulletVectors, Map<String, Integer> yearsBySkill) {
        List<BulletMatch> ranked = new ArrayList<>(bulletVectors.size());
        bulletVectors.forEach((id, bullet) -> ranked.add(new BulletMatch(id, Vectors.cosine(vector, bullet))));
        ranked.sort(Comparator.comparingDouble(BulletMatch::similarity).reversed()
                .thenComparing(BulletMatch::bulletId));
        List<BulletMatch> best = ranked.subList(0, Math.min(BEST_BULLETS, ranked.size()));
        double semantic = best.isEmpty() ? 0 : ramp(best.getFirst().similarity());

        Demanded demanded = demanded(signal);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (String skill : demanded.skills()) {
            if (!recognized(skill, yearsBySkill)) {
                ignored.add(skill);
            } else if (heldLongEnough(skill, signal, yearsBySkill)) {
                matched.add(skill);
            } else {
                missing.add(skill);
            }
        }
        int needed = matched.size() + missing.size();
        int met = matched.size();

        // The alternatives are one skill between them: any one held meets it.
        List<String> alternatives = new ArrayList<>();
        for (String skill : demanded.alternatives()) {
            (recognized(skill, yearsBySkill) ? alternatives : ignored).add(skill);
        }
        List<String> missingAlternatives = List.of();
        if (!alternatives.isEmpty()) {
            List<String> held = alternatives.stream()
                    .filter(skill -> heldLongEnough(skill, signal, yearsBySkill)).toList();
            matched.addAll(held);
            needed++;
            if (held.isEmpty()) {
                missingAlternatives = alternatives;
            } else {
                met++;
            }
        }
        double bySkills = needed == 0 ? 0 : (double) met / needed;
        return new SignalMatch(index, signal.classification(), Math.max(semantic, bySkills),
                new ArrayList<>(best), matched, missing, missingAlternatives, ignored);
    }

    /**
     * What a signal asks for: the skills it requires, and the ones it offers a
     * choice between.
     *
     * @param skills       every skill required, the model's and the sentence's
     * @param alternatives the choice it offers, empty if it offers none
     */
    private record Demanded(List<String> skills, List<String> alternatives) {
    }

    /**
     * Reads what a signal asks for from its own sentence as well as from the
     * model's lists.
     *
     * <p>The model is an unreliable witness to its own answer: across versions
     * 4 to 9 of the prompt it has named sixty phrases for one posting and none
     * at all for the next, while the sentence it wrote said the same thing
     * either way. The sentence is read with the same table scoring uses, so
     * what it yields is exactly what could be counted, and an alias added later
     * reaches every job without normalizing it again.
     *
     * <p>The model's own lists are kept: they carry skills the table does not
     * know, which the profile may still list, and a choice it marked.
     *
     * <p>Where it marked no choice, its skills go through
     * {@link SkillAlternatives} alongside the ones read from the sentence, so
     * "Go, Python, or C#" is a choice however the model filed it. Reading only
     * the sentence's own skills left the splitter blind to the case it exists
     * for: every model tried files a choice under {@code skills}, and each
     * option was then required outright, marking a candidate down for the ones
     * they lack.
     */
    private Demanded demanded(Signal signal) {
        List<String> listed = named(signal.skills());
        List<String> alternatives = named(signal.anyOfSkills());
        List<String> known = new ArrayList<>(listed);
        known.addAll(alternatives);
        List<String> fromText = skills.skillsNamedIn(signal.text()).stream()
                .filter(skill -> known.stream().noneMatch(skill::equalsIgnoreCase))
                .toList();

        if (!alternatives.isEmpty()) {
            // The model marked the choice itself, so its skills list stands as
            // written; only what it left out of both lists is still to be read.
            List<String> required = new ArrayList<>(listed);
            required.addAll(SkillAlternatives.of(signal.text(), fromText, skills).skills());
            return new Demanded(skills.canonicalAll(required), alternatives);
        }
        // It marked none, so the sentence is the only witness to a choice, and
        // the skills it listed are as likely to hold one as the ones it missed.
        List<String> candidates = new ArrayList<>(listed);
        candidates.addAll(fromText);
        SkillAlternatives.Split split = SkillAlternatives.of(signal.text(), candidates, skills);
        return new Demanded(skills.canonicalAll(split.skills()), split.anyOfSkills());
    }

    /**
     * Reads a job's skills as the table spells them, and reads the skills out
     * of a phrase it does not know as a whole: the model writes "MCP
     * integrations", "CI workflows" and "JavaScript/TypeScript", each of which
     * names a skill that would otherwise go unrecognized. A phrase naming none
     * is kept as written, so the profile can still match it and the match's
     * reasons can report it.
     *
     * @param listed the skills as the job stored them
     * @return their canonical names, without repeats
     */
    private List<String> named(List<String> listed) {
        List<String> read = new ArrayList<>(listed.size());
        for (String skill : skills.canonicalAll(listed)) {
            List<String> inside = skills.isKnown(skill) ? List.of() : skills.skillsNamedIn(skill);
            read.addAll(inside.isEmpty() ? List.of(skill) : inside);
        }
        return skills.canonicalAll(read);
    }

    /**
     * @return whether this is a skill at all: one the table knows, or one the
     *         profile lists. The model fills signals with phrases from their
     *         text ("bounded suppression", "exit codes"), which no profile ever
     *         matches, and counting them would lower every job's skill match
     */
    private boolean recognized(String skill, Map<String, Integer> yearsBySkill) {
        return skills.isKnown(skill) || yearsBySkill.containsKey(key(skill));
    }

    private static boolean heldLongEnough(String skill, Signal signal, Map<String, Integer> yearsBySkill) {
        Integer years = yearsBySkill.get(key(skill));
        return years != null && years >= signal.minYears();
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
     *         has at all, or {@code null} if they name none this side knows. A
     *         signal's alternatives count as one skill, held if any of them is.
     *         Years are judged per signal, in coverage
     */
    private Double skillMatch(NormalizedData job, Map<String, Integer> yearsBySkill) {
        Set<String> required = new LinkedHashSet<>();
        Set<Set<String>> alternatives = new LinkedHashSet<>();
        for (Signal signal : job.signals()) {
            if (signal.classification() == SignalClassification.REQUIRED_SKILL
                    || signal.classification() == SignalClassification.REQUIRED_QUALIFICATION) {
                Demanded demanded = demanded(signal);
                recognizedKeys(demanded.skills(), yearsBySkill).forEach(required::add);
                Set<String> group = recognizedKeys(demanded.alternatives(), yearsBySkill);
                if (!group.isEmpty()) {
                    alternatives.add(group);
                }
            }
        }
        int total = required.size() + alternatives.size();
        if (total == 0) {
            return null;
        }
        long held = required.stream().filter(yearsBySkill::containsKey).count()
                + alternatives.stream().filter(group -> group.stream().anyMatch(yearsBySkill::containsKey)).count();
        return (double) held / total;
    }

    /** @return the keys of the skills among these that count as skills at all */
    private Set<String> recognizedKeys(List<String> listed, Map<String, Integer> yearsBySkill) {
        return named(listed).stream()
                .filter(skill -> recognized(skill, yearsBySkill))
                .map(MatchScorer::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
