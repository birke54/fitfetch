package org.example.fitfetch.match;

import org.example.fitfetch.normalize.SignalClassification;

import java.util.List;
import java.util.Objects;

/**
 * How well one job matches the candidate, and why.
 *
 * <p>The reasons are kept, not just the score: which gates failed, the parts
 * the score is made of, and for every signal the bullets that best meet it.
 * That alignment is also what a tailored resume picks its bullets from.
 *
 * @param score        0 to 100. Computed even for an ineligible job, so it can
 *                     be seen what a job would have scored
 * @param eligible     whether every gate passed
 * @param gateFailures why the job is excluded; empty if it is not
 * @param parts        the parts the score is made of
 * @param signals      each signal's coverage and its best bullets, in the
 *                     job's signal order
 */
public record MatchResult(int score, boolean eligible, List<String> gateFailures, ScoreParts parts,
                          List<SignalMatch> signals) {

    public MatchResult {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be 0 to 100, but was " + score);
        }
        gateFailures = List.copyOf(gateFailures);
        Objects.requireNonNull(parts, "parts");
        signals = List.copyOf(signals);
    }

    /**
     * The parts of a score, each 0 to 1 except the domain adjustment.
     *
     * @param requirementCoverage how much of the job's weighted signals the
     *                            profile covers
     * @param skillMatch          the share of the job's required skills the
     *                            profile has, or {@code null} where it names
     *                            none this side knows: the score is then its
     *                            other parts, weighed against each other
     * @param levelFit            how close the job's level and years are to the
     *                            profile's
     * @param domainAdjustment    points added for a preferred domain or taken
     *                            away for an avoided one
     * @param requiredSkillsNamed how many distinct skills the required signals
     *                            named that this side knows, which is what the
     *                            skill match was taken over. Kept because it is
     *                            what says whether a share of 1 stands on one
     *                            skill or on nine, and why a null was a null
     */
    public record ScoreParts(double requirementCoverage, Double skillMatch, double levelFit,
                             int domainAdjustment, int requiredSkillsNamed) {
    }

    /**
     * One signal of the job, and how well the profile meets it.
     *
     * @param index               its position in the job's signals
     * @param classification      its section
     * @param coverage            0 to 1: the better of what the best bullet's
     *                            similarity and the profile's skills say
     * @param bestBullets         the most similar bullets, most similar first
     * @param matchedSkills       its skills the profile has, with enough years,
     *                            alternatives included
     * @param missingSkills       its required skills the profile lacks, or has
     *                            too few years of
     * @param missingAlternatives its alternatives, when the profile has none of
     *                            them with enough years; empty if it has one or
     *                            the signal offers none
     * @param ignoredSkills       what it listed as skills that neither the skill
     *                            table nor the profile knows, which count
     *                            neither way. A growing list of real ones means
     *                            the table needs them
     */
    public record SignalMatch(int index, SignalClassification classification, double coverage,
                              List<BulletMatch> bestBullets, List<String> matchedSkills,
                              List<String> missingSkills, List<String> missingAlternatives,
                              List<String> ignoredSkills) {

        public SignalMatch {
            bestBullets = List.copyOf(bestBullets);
            matchedSkills = List.copyOf(matchedSkills);
            missingSkills = List.copyOf(missingSkills);
            missingAlternatives = missingAlternatives == null ? List.of() : List.copyOf(missingAlternatives);
            ignoredSkills = ignoredSkills == null ? List.of() : List.copyOf(ignoredSkills);
        }
    }

    /**
     * @param bulletId   the profile bullet
     * @param similarity cosine similarity of its vector and the signal's
     */
    public record BulletMatch(String bulletId, double similarity) {
    }
}
