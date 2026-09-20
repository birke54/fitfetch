package org.example.fitfetch.fetching;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.fetching.records.LeverJobEntry;
import org.example.fitfetch.fetching.records.LeverResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;

/**
 * {@link Fetch} implementation that calls the public Lever postings API.
 *
 * <p>Each request targets {@code https://api.lever.co/v0/postings/{slug}} and
 * is deserialized into a {@link LeverResponse}. No authentication is required.
 *
 * <p>Four things differ from {@link AshbyFetch} and {@link GreenhouseFetch} and
 * are worth knowing before changing anything here:
 *
 * <ul>
 *   <li><strong>The body is a bare array</strong>, not an envelope: the top
 *       level is {@code [ {...}, {...} ]} on all 375 live boards sampled, with
 *       no {@code jobs} key and no version marker, and {@code []} for an empty
 *       board. {@link LeverResponse} is built here around that array rather
 *       than mapped from it.</li>
 *   <li><strong>No pagination.</strong> Lever returns every posting on a board
 *       in one body. {@code skip} and {@code limit} exist but there is no
 *       default cap, so there is no loop to write. Bodies are correspondingly
 *       large: the biggest board sampled is 34.8&nbsp;MB and took 2.2&ndash;4.1
 *       seconds against the 5-second {@code app.http.read-timeout}, which
 *       bounds the whole exchange rather than just the wait for headers. That
 *       margin is thin and is tracked as issue #72; the timeout is deliberately
 *       not changed here.</li>
 *   <li><strong>Lever does not compress.</strong> It ignores
 *       {@code Accept-Encoding} and answers uncompressed whatever is asked, so
 *       &mdash; unlike Ashby, whose 14.3&nbsp;MB worst case crosses the wire at
 *       a fraction of that &mdash; the sizes above are also the wire sizes.
 *       Nothing here can change that.</li>
 *   <li><strong>The slug is case-sensitive</strong> and is passed through
 *       exactly as {@code slugs.json} stores it. An unknown, wrongly-cased or
 *       taken-down board answers 404 with the JSON body
 *       {@code {"ok":false,"error":"Document not found"}} &mdash; note that
 *       this is JSON where Ashby's 404 is plain text, but it surfaces the same
 *       way, as an {@code HttpClientErrorException.NotFound} rather than a
 *       deserialization failure, because the status is handled before the body
 *       is read. Dead slugs are the norm rather than the exception: probing 600
 *       slugs from {@code slugs.json} answered 375 boards and
 *       <strong>225 404s</strong> and nothing else at all, so
 *       {@link org.example.fitfetch.ats.SlugSweep} sees one on better than a
 *       third of its requests; it counts them separately and carries on.</li>
 * </ul>
 *
 * <p><strong>Trap: reading the array needs a mixin, and the obvious fixes are
 * all wrong.</strong> {@link AtsJobEntry} carries
 * {@code @JsonTypeInfo(defaultImpl = GreenhouseJobEntry.class)}, which its
 * subtypes inherit, so Jackson demands a {@code "type"} discriminator that
 * Lever's payload does not have. Reading a {@code LeverJobEntry[]}, a
 * {@code List<LeverJobEntry>} through a {@code TypeFactory}, a single
 * {@code LeverJobEntry}, a delegating {@code @JsonCreator} whose parameter is
 * annotated {@code @JsonTypeInfo(Id.NONE)}, and a hand-written
 * {@code ValueDeserializer} all fail alike with an
 * {@code InvalidTypeIdException}.
 *
 * <p>Exactly one thing that <em>appears</em> to work must never be used:
 * putting {@code @JsonTypeInfo(use = Id.NONE)} on {@link LeverJobEntry} itself.
 * It strips the discriminator on <strong>write</strong> as well, so every
 * {@code job_data} row would be persisted without a {@code "type"}, fall
 * through to {@code defaultImpl = GreenhouseJobEntry} on read and throw an
 * {@code InvalidFormatException} &mdash; corrupting every Lever row, with
 * nothing failing at fetch time to reveal it.
 *
 * <p>What works is confining the exemption to the read path: a mixin declaring
 * {@code Id.NONE}, applied to a {@link JsonMapper} that belongs to this class
 * alone and is installed as the JSON converter of a private copy of the shared
 * client. The application's default mapper is untouched, so an entry still
 * serializes as {@code {"type":"LEVER",...}} and still reads back as a
 * {@code LeverJobEntry}. {@code LeverFetchTest} pins both halves &mdash; the
 * HTTP read and the persistence round-trip &mdash; because it is the pairing,
 * not either one alone, that is correct.
 *
 * @see Fetch
 */
@Service
public class LeverFetch implements Fetch<LeverJobEntry> {

    private static final String BASEURL = "https://api.lever.co/v0/postings/";

    /**
     * Mixin that switches polymorphic handling off for {@link LeverJobEntry},
     * on the mapper it is registered with and nowhere else.
     *
     * <p>Annotating the record itself with this would do the same thing on the
     * write path too, which is the silent-corruption trap documented above. A
     * mixin is the whole point: it is scoped to one mapper, and this one reads
     * HTTP responses only.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
    private interface NoTypeInfo {
    }

    private final RestClient restClient;

    /**
     * @param restClients the per-ATS clients; Lever's is paced by the default
     *                    limits, which is all it needs &mdash; 40 requests per
     *                    second drew no 429 at all
     */
    @Autowired
    public LeverFetch(AtsRestClients restClients) {
        this(restClients.forAts(AtsName.LEVER));
    }

    /**
     * @param restClient the HTTP client used to call the Lever API. It is
     *                   copied through {@link RestClient#mutate()} and given the
     *                   mixin-aware JSON converter, so the caller's client is
     *                   left alone along with everything on it &mdash; the rate
     *                   limiter, the timeouts and the observation registry all
     *                   carry over to the copy
     */
    public LeverFetch(RestClient restClient) {
        this.restClient = restClient.mutate()
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder().addMixIn(LeverJobEntry.class, NoTypeInfo.class))))
                .build();
    }

    /**
     * Fetches every posting on a single Lever board.
     *
     * <p>{@code mode=json} is Lever's default and is left off. {@code group=} is
     * never sent: it restructures the top level into
     * {@code [{title, postings}]}, which is not what this reads. An
     * unrecognised {@code mode} answers 406.
     *
     * @param slug the Lever board name (the {@code {slug}} path segment);
     *             case-sensitive, and passed through verbatim
     * @return the board's postings; never {@code null}, and
     *         {@link AtsResponse#jobs()} is never {@code null} either &mdash; an
     *         empty list is substituted when the API returns no body
     * @throws org.springframework.web.client.HttpClientErrorException on a 4xx
     *         response, including the 404 a dead slug returns
     * @throws org.springframework.web.client.HttpServerErrorException on a 5xx
     *         response
     */
    @Override
    public AtsResponse<LeverJobEntry> fetchJobs(String slug) {
        // The slug stays a template variable: the template is what the request's
        // uri metric tag holds, and concatenating the slug in would make that one
        // series per board.
        LeverJobEntry[] postings = restClient.get()
                .uri(BASEURL + "{slug}", slug)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(LeverJobEntry[].class);

        return new LeverResponse(postings == null ? List.of() : Arrays.asList(postings));
    }
}
