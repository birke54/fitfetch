package org.example.fitfetch.utilities;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Keyword-based gate deciding whether a job title is relevant enough to ingest
 * into {@code fetched_jobs}.
 *
 * <p>A title is first {@linkplain #normalize(String) normalized} (lower-cased,
 * seniority words and non-letters stripped, whitespace collapsed) and then
 * matched against a deny list followed by an allow list: any deny-list hit
 * rejects the title outright, otherwise it is kept only if it contains at least
 * one allow-list phrase. The lists target backend / infrastructure / platform
 * engineering roles and exclude front-end, mobile, management and internship
 * postings.
 *
 * <p>This is a stateless utility; all members are static.
 *
 * @see org.example.fitfetch.ats.GreenhouseAts
 */
public class TitleFilter {
    // Basic allow/deny filters gating the insert of a new job into `fetched_jobs`
    private static final List<String> ALLOWED_FILTERS = Arrays.asList(
            "backend", "back end", "swe", "software engineer", "software developer",
            "developer", "sde", "development engineer", "member of technical staff",
            "mts", "programmer", "computer programmer", "application developer",
            "application engineer", "full stack", "full-stack", "systems engineer",
            "distributed systems", "platform engineer", "infrastructure engineer", "cloud",
            "sre", "site reliability engineer", "devops", "platform reliability engineer",
            "automation engineer", "forward-deployed engineer", "forward deployed engineer",
            "support engineer", "integration engineer", "dev engineer", "server-side engineer",
            "server side engineer"
    );
    private static final List<String> DENY_FILTERS = Arrays.asList(
            "front end", "front-end", "frontend", "mobile", "ios", "android",
            "manager", "recruiter", "intern"
    );

    // precompile the regexes
    private static final Pattern SENIORITY =
            Pattern.compile("\\b(sr|senior|staff|principal|lead|jr|junior|i{1,3}|iv|v)\\b");
    private static final Pattern NON_ALPHA = Pattern.compile("[^a-z ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Reduces a raw job title to a comparable form: lower-cased, with seniority
     * qualifiers ({@code sr}, {@code senior}, {@code staff}, roman numerals,
     * etc.) removed, every non-{@code [a-z ]} character replaced by a space, and
     * runs of whitespace collapsed and trimmed.
     *
     * @param title the raw title; must not be {@code null}
     * @return the normalized title
     */
    private static String normalize(String title) {
        String t = title.toLowerCase();
        t = SENIORITY.matcher(t).replaceAll("");
        t = NON_ALPHA.matcher(t).replaceAll(" ");
        t = WHITESPACE.matcher(t).replaceAll(" ").trim();
        return t;
    }

    /**
     * Decides whether a job with the given title should be kept.
     *
     * @param title the raw job title; must not be {@code null}
     * @return {@code true} if the normalized title contains no deny-list term
     *         and at least one allow-list term; {@code false} otherwise
     */
    public static boolean keep(String title) {
        String titleNormalized = normalize(title);
        if (DENY_FILTERS.stream().anyMatch(titleNormalized::contains)) {
            return false;
        }
        return ALLOWED_FILTERS.stream().anyMatch(titleNormalized::contains);
    }
}
