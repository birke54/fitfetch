package org.example.fitfetch.fetching.records;

import org.example.fitfetch.fetching.records.LeverSubRecords.Categories;
import org.example.fitfetch.fetching.records.LeverSubRecords.ListItem;
import org.example.fitfetch.fetching.records.LeverSubRecords.SalaryRange;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link AtsJobEntry} implementation mapping a single posting from the Lever
 * postings API (the {@code /v0/postings/{slug}} response).
 *
 * <p>Lever publishes camelCase keys, so &mdash; as with {@link AshbyJobEntry}
 * and unlike {@link GreenhouseJobEntry} &mdash; no {@code @JsonProperty}
 * bridging is needed and the component names are the payload's keys verbatim.
 * All twenty keys the API returns are modelled here, down to the ones nothing
 * reads; nothing is dropped, because this record is what gets written into the
 * {@code job_data} column and an unmodelled field is gone the moment the
 * posting is taken down.
 *
 * <p>Every figure below was measured over <strong>12,231 postings from 375 live
 * boards</strong>, sampled at random from {@code slugs.json}.
 *
 * <p><strong>Absence is spelled {@code ""}, not {@code null}.</strong> This is
 * the opposite of Ashby. Across that sample, all seventeen top-level keys that
 * are not about pay were present on every posting, and an unset field arrives
 * as the empty string; the sole nullable value is {@link #country()}
 * ({@code null} on 2.1%). Only {@code salaryRange}, {@code salaryDescription}
 * and {@code salaryDescriptionPlain} can be absent outright.
 *
 * <p>The derived methods below still null-guard, because a provider that has
 * never sent a null is not a provider that cannot, but a blank string is the
 * case actually worth handling. Inside {@link Categories}
 * the convention flips again &mdash; there, keys are absent rather than empty;
 * see that record.
 *
 * <p><strong>Deserialization needs help.</strong> The payload is a bare JSON
 * array of these objects with no {@code type} discriminator, and the
 * {@code @JsonTypeInfo} this record inherits from {@link AtsJobEntry} demands
 * one. {@link org.example.fitfetch.fetching.LeverFetch} solves that with a
 * mixin on the mapper it reads with; the discriminator must <em>not</em> be
 * switched off on this record itself, which would also strip it on write and
 * corrupt every stored row. That trap is documented in full on that class.
 *
 * @param id                     the posting's identifier, a UUID string.
 *                               Present and distinct on all 12,231 sampled
 *                               postings, and the permalink segment in
 *                               {@link #hostedUrl()}
 *                               ({@code https://jobs.lever.co/<slug>/<id>}). It
 *                               is the only identifier the payload carries, so
 *                               {@link #jobId()} answers with it
 * @param text                   the job title &mdash; Lever's name for it, not
 *                               {@code title}. Present and non-blank on all
 *                               12,231 sampled postings; what {@link #title()}
 *                               answers with
 * @param createdAt              epoch <strong>milliseconds</strong>. See
 *                               {@link #postedAt()} for why this is a
 *                               {@code long} and must stay one
 * @param categories             Lever's classification of the posting, and the
 *                               only place a location appears; always present
 * @param country                ISO 3166-1 alpha-2 code, e.g. {@code "BR"}. The
 *                               one genuinely nullable value in the payload
 *                               ({@code null} on 2.1% of sampled postings).
 *                               Deliberately <em>not</em> folded into
 *                               {@link #locationName()} &mdash; see there
 * @param workplaceType          one of {@code "onsite"}, {@code "hybrid"},
 *                               {@code "remote"} or {@code "unspecified"};
 *                               never {@code null}. Retained as raw payload
 *                               only; nothing derives from it, for the measured
 *                               reason given on {@link #locationName()}
 * @param description            the posting body as HTML: {@code opening}
 *                               followed by {@code descriptionBody}. The first
 *                               and largest block {@link #content()} assembles
 * @param descriptionPlain       the same body flattened to text by Lever. Kept
 *                               as a fallback, but deliberately not what
 *                               {@link #content()} returns &mdash; see that
 *                               method
 * @param descriptionBody        {@code description} without the opening
 *                               paragraph. Not used: dropping the opening loses
 *                               a further 12.8% of the description
 * @param descriptionBodyPlain   the plain rendering of {@code descriptionBody}
 * @param opening                the lead paragraph, already included in
 *                               {@code description}
 * @param openingPlain           the plain rendering of {@code opening}
 * @param additional             free-text HTML block published below the lists
 *                               &mdash; EEO statements, benefits, application
 *                               notes. The third block {@link #content()}
 *                               assembles; non-empty on 7,561 sampled postings,
 *                               and contained in {@code description} on 1 of
 *                               them
 * @param additionalPlain        the plain rendering of {@code additional}
 * @param lists                  titled bullet blocks (responsibilities,
 *                               requirements, benefits). Always present, empty
 *                               on 4,880 of 12,231 sampled postings. The second
 *                               block {@link #content()} assembles
 * @param hostedUrl              public URL of the posting on Lever's hosted
 *                               board
 * @param applyUrl               URL of the application form, normally
 *                               {@code hostedUrl} plus {@code /apply}
 * @param salaryRange            the structured pay range; {@code null} on the
 *                               63.6% of postings that publish none
 * @param salaryDescription      the employer's prose about pay, as HTML;
 *                               present on 12.0% of postings. A component, but
 *                               <strong>deliberately excluded</strong> from
 *                               {@link #content()} &mdash; see there
 * @param salaryDescriptionPlain the plain rendering of
 *                               {@code salaryDescription}
 * @param slug                   the ATS board slug this entry was fetched from;
 *                               {@code null} until tagged via
 *                               {@link #withSlug(String)}, since it is not part
 *                               of the Lever JSON payload
 * @see org.example.fitfetch.fetching.LeverFetch
 */
public record LeverJobEntry(
        String id,
        String text,
        long createdAt,
        Categories categories,
        String country,
        String workplaceType,
        String description,
        String descriptionPlain,
        String descriptionBody,
        String descriptionBodyPlain,
        String opening,
        String openingPlain,
        String additional,
        String additionalPlain,
        List<ListItem> lists,
        String hostedUrl,
        String applyUrl,
        SalaryRange salaryRange,
        String salaryDescription,
        String salaryDescriptionPlain,
        String slug
) implements AtsJobEntry {

    /**
     * Separator between the places in {@link #locationName()}.
     *
     * <p>A semicolon and not a comma, for the reason
     * {@link AshbyJobEntry#locationName()} documents: a comma is already the
     * separator <em>inside</em> a single Lever label
     * ({@code "London, Ontario"}), so joining on one would fuse two places into
     * an unparseable third.
     */
    private static final String LOCATION_SEPARATOR = "; ";

    /** Separator between the HTML blocks {@link #content()} assembles. */
    private static final String BLOCK_SEPARATOR = "\n";

    /**
     * @return always the literal {@code "Lever"}, equal to
     *         {@code AtsName.LEVER.stringValue()}
     */
    @Override
    public String atsName() {
        return "Lever";
    }

    /**
     * Returns {@link #id()}, Lever's UUID for the posting.
     *
     * <p>Combined with {@link #atsName()} this is the key dedup checks use. A
     * {@code null} result means the payload carried no {@code id}, which makes
     * the entry unusable; callers drop such an entry rather than store it.
     *
     * <p>It was present, a well-formed UUID and distinct on all 12,231
     * postings sampled across 375 live boards, and it is the segment Lever uses
     * as the posting permalink in {@link #hostedUrl()}. It is also the
     * <em>only</em> identifier in the payload, so there is nothing to fall back
     * to should Lever ever withdraw it.
     *
     * @return the posting UUID, or {@code null} if the payload carried none
     */
    @Override
    public String jobId() {
        return id;
    }

    /**
     * @return {@link #text()}, which is what Lever calls the job title; the
     *         payload has no {@code title} key
     */
    @Override
    public String title() {
        return text;
    }

    /**
     * Returns the posting body as HTML: {@link #description()}, then each
     * {@link #lists()} block rendered by {@link ListItem#toHtml()}, then
     * {@link #additional()}.
     *
     * <p><strong>Why three blocks and not just the description.</strong> Lever
     * splits a posting across fields its own {@code description} does not
     * re-include, and the split is not marginal. Across the sampled boards,
     * {@code description} contained all of the list content on
     * <strong>0 of 7,351</strong> postings that have lists, and contained
     * {@code additional} on <strong>1 of 7,561</strong>. Taking
     * {@code description} alone discards <strong>50.0%</strong> of published
     * text &mdash; 38.2M of 76.4M characters &mdash; and
     * <strong>71.8%</strong> of postings carry a non-empty {@code lists} or
     * {@code additional}. A further 103 postings have an empty
     * {@code description} and non-empty lists or additional, so for those a
     * description-only reading yields nothing at all.
     *
     * <p><strong>Why {@code description} and not
     * {@code descriptionBody}.</strong> {@code description} is
     * {@code opening} + {@code descriptionBody}; taking the body alone loses a
     * further 12.8% of the description, and the opening paragraph is where a
     * posting usually says what the company does.
     *
     * <p><strong>Why HTML and not Lever's plain renderings.</strong> Two
     * measured reasons, and note that neither is Ashby's. Ashby's bare-URL
     * argument does <em>not</em> reproduce here: Lever's plain renderer already
     * drops hyperlink targets, so once {@code JdPreProcess} has stripped the
     * tags the HTML and plain paths carry 2,949 and 2,690 bare URLs
     * respectively &mdash; a wash. What decides it is that
     * {@code descriptionPlain} is empty on 436 postings whose HTML body is not
     * (and never the reverse), and that {@code lists[].content} has no plain
     * variant at all, so a plain-text assembly could not include the bullets
     * that are the whole reason for assembling.
     *
     * <p><strong>Why {@code salaryDescription} is excluded.</strong> It is a
     * fourth block, and it is pay boilerplate ({@code "+ bonus"}, range
     * caveats) rather than anything describing the work. It stays a component
     * so the text is not lost from {@code job_data}, but it is not what the
     * model is asked to read.
     *
     * <p>Blocks are separated by a newline, and each is skipped when blank, so
     * a posting with no lists reads exactly as it would have without them.
     *
     * @return the assembled HTML body, or {@code null} if the posting published
     *         no text in any of the three blocks
     */
    @Override
    public String content() {
        StringBuilder html = new StringBuilder();
        appendBlock(html, description);
        if (lists != null) {
            for (ListItem list : lists) {
                if (list != null) {
                    appendBlock(html, list.toHtml());
                }
            }
        }
        appendBlock(html, additional);
        return html.isEmpty() ? null : html.toString();
    }

    private static void appendBlock(StringBuilder html, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        if (!html.isEmpty()) {
            html.append(BLOCK_SEPARATOR);
        }
        html.append(block);
    }

    /**
     * Returns every place this posting is open in as one label:
     * {@code categories.allLocations} in Lever's own order, de-duplicated and
     * joined with {@code "; "}.
     *
     * <p><strong>Why {@code allLocations} alone.</strong> Simpler than Ashby,
     * because Lever has already done the merging: {@code allLocations} is a
     * strict superset of {@code categories.location}, which equalled
     * {@code allLocations[0]} on all 12,223 sampled postings that carry it. So
     * there is no primary to lead with and no secondaries to fold in &mdash;
     * reading {@code location} as well would only risk duplicating the first
     * element. Keeping every entry matters for the reason
     * {@link org.example.fitfetch.location.LocationInput} warns about: the
     * worst case sampled is open in fifteen Canadian cities, and keeping only
     * the first would filter the job out for a user near any of the other
     * fourteen.
     *
     * <p><strong>Why a semicolon.</strong> It is the one separator that
     * {@code OllamaPrompt},
     * {@link org.example.fitfetch.location.ExtractedLocation} and the extractor
     * all already treat as an unambiguous list delimiter, so this composite
     * reproduces the exact multi-location shape the whole downstream pipeline
     * was tuned on. The longest composite observed is 307 characters &mdash; a
     * posting open in fifteen Canadian cities &mdash; longer than the
     * 261-character Datadog label that
     * {@code location_interpretation.location_key} was sized for &mdash; which
     * costs nothing, since that column is {@code TEXT}.
     *
     * <p><strong>Trap: {@code country} is not appended, and that is
     * deliberate.</strong> Lever publishes it separately from the label, and
     * the label already names the country whenever the recruiter meant it to:
     * on 99.3% of postings the country code does not appear in the label at
     * all.
     * Appending it would turn {@code "Austin, Texas"} into
     * {@code "Austin, Texas; US"}, which the extractor reads as a city
     * <em>and</em> a country &mdash; two locations where the posting names one,
     * and the second of them the size of a country.
     *
     * <p><strong>Trap: {@code workplaceType} is not folded in either.</strong>
     * Of the 1,690 sampled postings whose label already contains a remote
     * marker, only 527 are {@code workplaceType: remote}; <strong>1,002 are
     * {@code hybrid}</strong> and 159 {@code onsite}. So wherever the two can
     * be compared, the field contradicts the label about two times in three,
     * and appending a marker on its strength would relabel a great many hybrid
     * office roles as "matches any origin" &mdash; a worse failure than the one
     * it fixes. The known cost of leaving it out: <strong>1,907 postings
     * (15.6%)</strong> are flagged remote but carry a place-only label, and
     * will resolve as on-site there. That is a larger cost than Ashby pays and
     * the flag is better evidenced than Ashby's {@code isRemote}, so this is
     * worth revisiting &mdash; but not on this signal alone.
     *
     * @return the composite location label, or {@code null} when
     *         {@code allLocations} is empty or names nothing usable (8 of
     *         12,231 sampled postings)
     */
    @Override
    public String locationName() {
        if (categories == null || categories.allLocations() == null) {
            return null;
        }
        String label = categories.allLocations().stream()
                .filter(Objects::nonNull)
                .filter(location -> !location.isBlank())
                .distinct()
                .collect(Collectors.joining(LOCATION_SEPARATOR));
        return label.isEmpty() ? null : label;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link #createdAt()} read as epoch milliseconds, at UTC.
     *
     * <p><strong>Trap: the naive binding corrupts this silently.</strong>
     * {@code createdAt} is a 13-digit epoch-millisecond integer. Declaring the
     * component as an {@link OffsetDateTime} and letting Jackson bind it looks
     * like it works and does not: Jackson reads a bare number as epoch
     * <em>seconds</em>, so {@code 1552439280000} becomes
     * {@code +51164-11-04T05:20Z}. Nothing throws and nothing warns. Because
     * this method feeds the freshness signal, every Lever job would then read
     * as maximally fresh forever, with no failure anywhere to notice it. Hence
     * the {@code long} component and the explicit conversion here; the same
     * value read this way is {@code 2019-03-13T01:08Z}. A test pins that, and
     * it exists to stop the field being "simplified" back.
     *
     * <p>There is no fallback, because the payload exposes no second date:
     * Lever publishes no {@code updatedAt} and no last-published equivalent
     * anywhere &mdash; the same situation as {@link AshbyJobEntry#postedAt()},
     * with nothing to prefer.
     *
     * <p>Unlike Ashby's {@code publishedAt}, which Ashby documents as when the
     * posting was <em>last</em> published, Lever's field is named
     * {@code createdAt} and values as old as February 2016 survive on live
     * boards &mdash; 34 sampled postings predate 2019 &mdash; which suggests it
     * is a true first-published date and so does not reset when a posting is
     * unpublished and republished. <strong>That is an inference, not
     * a documented fact</strong>: {@code createdAt} appears in no published
     * Lever field list, so the behaviour is observed rather than promised.
     *
     * <p>A posting carrying no {@code createdAt} at all would leave the
     * primitive at {@code 0} and date to 1970, which the normalization pass
     * ages out as too old. That fails safe &mdash; a job silently skipped,
     * rather than one silently always fresh &mdash; and the key was present,
     * and exactly thirteen digits, on every one of the 12,231 postings
     * sampled.
     *
     * @return the creation timestamp at UTC; never {@code null}
     */
    @Override
    public OffsetDateTime postedAt() {
        return Instant.ofEpochMilli(createdAt).atOffset(ZoneOffset.UTC);
    }

    @Override
    public LeverJobEntry withSlug(String slug) {
        return new LeverJobEntry(id, text, createdAt, categories, country, workplaceType, description,
                descriptionPlain, descriptionBody, descriptionBodyPlain, opening, openingPlain, additional,
                additionalPlain, lists, hostedUrl, applyUrl, salaryRange, salaryDescription,
                salaryDescriptionPlain, slug);
    }
}
