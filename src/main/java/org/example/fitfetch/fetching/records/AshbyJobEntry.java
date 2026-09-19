package org.example.fitfetch.fetching.records;

import org.example.fitfetch.fetching.records.AshbySubRecords.Address;
import org.example.fitfetch.fetching.records.AshbySubRecords.Compensation;
import org.example.fitfetch.fetching.records.AshbySubRecords.SecondaryLocation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link AtsJobEntry} implementation mapping a single job object from the Ashby
 * posting API (the
 * {@code /posting-api/job-board/{board}?includeCompensation=true} response).
 *
 * <p>Ashby already publishes camelCase keys, so &mdash; unlike
 * {@link GreenhouseJobEntry} &mdash; no {@code @JsonProperty} bridging is
 * needed and the component names are the payload's keys verbatim. Every key the
 * API returned on the sampled boards is modelled here; nothing is dropped,
 * because this record is what gets written into the {@code job_data} column and
 * an unmodelled field is gone the moment the posting is taken down.
 *
 * <p><strong>Every String component is nullable.</strong> Ashby's reference
 * marks no field as required and states plainly that missing data is simply
 * absent from the response rather than sent as an empty value, so the derived
 * methods below all null-guard even where the sample never showed a null.
 *
 * @param id                 the posting's identifier, a UUID string.
 *                           Undocumented &mdash; it appears in no published
 *                           field list &mdash; but present on all 3,805
 *                           postings sampled across live boards, and it is the
 *                           permalink segment in {@link #jobUrl()}
 *                           ({@code https://jobs.ashbyhq.com/<board>/<id>}). It
 *                           is the only identifier the payload carries, so
 *                           {@link #jobId()} answers with it
 * @param title              job title
 * @param department         the department the posting sits under, e.g.
 *                           {@code "Engineering"}
 * @param team               the team within that department; often equal to
 *                           {@code department} on boards that do not subdivide
 * @param employmentType     e.g. {@code "FullTime"}, {@code "Contract"},
 *                           {@code "Intern"}
 * @param location           the primary free-text location label. The
 *                           recruiter's own answer to where the job is, and the
 *                           first element of the composite
 *                           {@link #locationName()} builds
 * @param secondaryLocations additional places the posting is open in; may be
 *                           {@code null} or empty. These are further locations,
 *                           not annotations on {@code location}, and
 *                           {@link #locationName()} folds them in
 * @param address            structured office address for {@code location}; may
 *                           be {@code null}, and is on roughly a fifth of live
 *                           postings. Describes an office, not where work may be
 *                           performed, so nothing resolves geography from it
 * @param isRemote           Ashby's remote flag. <strong>Does not mean
 *                           remote</strong> &mdash; see the trap documented on
 *                           {@link #locationName()}. Retained as raw payload
 *                           only; nothing derives from it. Boxed because it is
 *                           absent on 22.3% of live postings
 * @param workplaceType      {@code "Remote"}, {@code "Hybrid"} or
 *                           {@code "OnSite"}; {@code null} on 22.3% of live
 *                           postings, which is why it is not load-bearing
 *                           anywhere
 * @param descriptionHtml    the job description body as HTML; what
 *                           {@link #content()} answers with
 * @param descriptionPlain   the same body flattened to text by Ashby. Kept as a
 *                           fallback, but deliberately <em>not</em> what
 *                           {@link #content()} returns &mdash; see that method
 * @param publishedAt        when Ashby says the posting was last published; what
 *                           {@link #postedAt()} answers with, with the fidelity
 *                           caveat documented there. Always arrives as a
 *                           {@code +00:00} offset with millisecond precision
 * @param jobUrl             public URL of the posting on Ashby's hosted board
 * @param applyUrl           URL of the application form, which may point at the
 *                           employer's own site rather than Ashby's
 * @param isListed           whether the posting is shown on the public board;
 *                           boxed, on the same nullability terms as the rest
 * @param compensation       published pay data; present only because the request
 *                           sends {@code includeCompensation=true}, and hollow
 *                           on boards that publish none
 * @param shouldDisplayCompensationOnJobPostings the employer's own answer to
 *                           whether {@code compensation} may be displayed. It is
 *                           {@code false} on postings that still carry full pay
 *                           tiers, so check it before showing any figure
 * @param slug               the ATS board slug this entry was fetched from;
 *                           {@code null} until tagged via
 *                           {@link #withSlug(String)}, since it is not part of
 *                           the Ashby JSON payload
 * @see org.example.fitfetch.fetching.AshbyFetch
 */
public record AshbyJobEntry(
        String id,
        String title,
        String department,
        String team,
        String employmentType,
        String location,
        List<SecondaryLocation> secondaryLocations,
        Address address,
        Boolean isRemote,
        String workplaceType,
        String descriptionHtml,
        String descriptionPlain,
        OffsetDateTime publishedAt,
        String jobUrl,
        String applyUrl,
        Boolean isListed,
        Compensation compensation,
        Boolean shouldDisplayCompensationOnJobPostings,
        String slug
) implements AtsJobEntry {

    /**
     * Separator between the primary and secondary labels in
     * {@link #locationName()}.
     *
     * <p>A semicolon and not a comma: a comma is already the separator
     * <em>inside</em> a single label ({@code "Austin, TX"}), so joining on one
     * would fuse two places into an unparseable third.
     */
    private static final String LOCATION_SEPARATOR = "; ";

    /**
     * @return always the literal {@code "Ashby"}, equal to
     *         {@code AtsName.ASHBY.stringValue()}
     */
    @Override
    public String atsName() {
        return "Ashby";
    }

    /**
     * Returns {@link #id()}, Ashby's UUID for the posting.
     *
     * <p>Combined with {@link #atsName()} this is the key dedup checks use. A
     * {@code null} result means the payload carried no {@code id}, which makes
     * the entry unusable; callers drop such an entry rather than store it.
     *
     * <p>{@code id} is undocumented &mdash; Ashby's published field list for the
     * posting API omits it &mdash; but it was present and a well-formed UUID on
     * all 3,805 postings sampled across live boards, and it is the segment Ashby
     * itself uses as the posting permalink in {@link #jobUrl()}. It is also the
     * <em>only</em> identifier in the payload, so there is nothing to fall back
     * to should Ashby ever withdraw it.
     *
     * @return the posting UUID, or {@code null} if the payload carried none
     */
    @Override
    public String jobId() {
        return id;
    }

    /**
     * Returns {@link #descriptionHtml()}, the HTML body.
     *
     * <p>Not {@link #descriptionPlain()}, and the choice was measured rather
     * than assumed. Ashby's plain rendering inlines every hyperlink target as
     * literal text, so across the sampled boards it put <strong>4,575</strong>
     * bare URLs into what becomes the LLM and embedding input, against
     * <strong>43</strong> by the HTML path: jsoup's {@code wholeText()} in
     * {@code JdPreProcess} keeps the anchor text and discards the {@code href}.
     * Bare URLs are pure token cost and they crowd out real description text.
     *
     * <p>{@link #descriptionPlain()} stays a component so the alternative
     * rendering is not lost, and so a posting with an empty HTML body can still
     * be recovered from stored {@code job_data}.
     *
     * @return the HTML description body, or {@code null} if absent
     */
    @Override
    public String content() {
        return descriptionHtml;
    }

    /**
     * Returns every place this posting is open in as one label: the primary
     * {@link #location()} first, then each distinct
     * {@link SecondaryLocation#location()}, joined with {@code "; "}.
     *
     * <p><strong>Why join rather than pick one.</strong> Ashby's secondary
     * locations are further places the job is open in, not decorations on the
     * primary; one sampled posting is open in twenty European countries with
     * {@code "Remote - European Union"} as its primary. Keeping only the primary
     * would strand nineteen of them, which is exactly the correctness bug
     * {@link org.example.fitfetch.location.LocationInput} warns about: a role
     * with an office near the user gets filtered out because a different office
     * happened to be listed first.
     *
     * <p><strong>Why a semicolon.</strong> It is the one separator that
     * {@code OllamaPrompt}, {@link org.example.fitfetch.location.ExtractedLocation}
     * and the extractor all already treat as an unambiguous list delimiter, so
     * this composite reproduces the exact multi-location shape the whole
     * downstream pipeline was tuned on for Greenhouse &mdash; no prompt change,
     * no new parsing rule. The longest composite observed across the sample is
     * 199 characters, comfortably inside the 261 the
     * {@code LocationInterpretation} text column was sized for.
     *
     * <p><strong>Trap: {@code isRemote} contributes nothing here, and that is
     * deliberate.</strong> Despite its name and Ashby's own documentation, live
     * data shows {@code isRemote: true} on 1,828 postings whose
     * {@link #workplaceType()} is {@code "Hybrid"} as well as on 623 whose type
     * is {@code "Remote"}; in practice it means "not strictly on-site". Folding
     * it into this label would relabel 1,828 hybrid office roles as remote,
     * which is a far worse error than the one it would fix.
     * {@link #workplaceType()} is no help either, being {@code null} on 22.3% of
     * postings. Both are kept as components and neither is read here.
     *
     * <p>The known cost of that decision: 261 sampled postings are genuinely
     * remote but carry a location label with no remote marker anywhere in its
     * text, and will resolve as on-site at that location. That is accepted until
     * a signal more reliable than {@code isRemote} exists.
     *
     * <p>A {@code null} {@link #location()} does not discard the secondaries. The
     * primary is only the one to lead with; a posting that named nineteen
     * countries and happened to omit the primary still names nineteen places,
     * and returning {@code null} for it would strand the job with no label at
     * all &mdash; the very failure this method exists to prevent. {@code null}
     * is returned only when no location survives anywhere in the payload.
     *
     * @return the composite location label, the surviving part of it when
     *         either half is absent, or {@code null} only if the payload named
     *         no location at all
     */
    @Override
    public String locationName() {
        String label = Stream.concat(
                        Stream.ofNullable(location),
                        secondaryLocations == null ? Stream.<String>empty()
                                : secondaryLocations.stream()
                                        .filter(Objects::nonNull)
                                        .map(SecondaryLocation::location)
                                        .filter(Objects::nonNull))
                .distinct()
                .collect(Collectors.joining(LOCATION_SEPARATOR));
        return label.isEmpty() ? null : label;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link #publishedAt()} with no fallback, because the public
     * posting API exposes no second date: there is no {@code updatedAt} and no
     * first-published equivalent anywhere in the payload.
     *
     * <p><strong>Known fidelity loss.</strong> Ashby defines {@code publishedAt}
     * as when the posting was <em>last</em> published, so an unpublish and
     * republish resets it and a long-open role reads as new. This is precisely
     * the "a year-old job looks fresh" failure that
     * {@link GreenhouseJobEntry#postedAt()} avoids by preferring
     * {@code first_published} over {@code updated_at} &mdash; and here there is
     * nothing to prefer, so the date is taken as given and the normalization
     * pass ages such a job from the republish rather than from the original
     * posting.
     *
     * <p>Ordinary editing does not appear to move it: postings whose
     * descriptions had plainly been revised still carried dates two and a half
     * years old.
     *
     * @return the publication timestamp, or {@code null} if the payload carried
     *         none
     */
    @Override
    public OffsetDateTime postedAt() {
        return publishedAt;
    }

    @Override
    public AshbyJobEntry withSlug(String slug) {
        return new AshbyJobEntry(id, title, department, team, employmentType, location, secondaryLocations, address,
                isRemote, workplaceType, descriptionHtml, descriptionPlain, publishedAt, jobUrl, applyUrl, isListed,
                compensation, shouldDisplayCompensationOnJobPostings, slug);
    }
}
