package org.example.fitfetch;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.LeverFetch;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.fetching.records.LeverJobEntry;
import org.example.fitfetch.fetching.records.LeverSubRecords.Categories;
import org.example.fitfetch.fetching.records.LeverSubRecords.ListItem;
import org.example.fitfetch.fetching.records.LeverSubRecords.SalaryRange;
import org.example.fitfetch.utilities.JdPreProcess;
import org.example.fitfetch.utilities.TitleFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidTypeIdException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Transport and record tests for the Lever integration.
 *
 * <p>The fixtures are verbatim captures of live Lever boards, pretty-printed
 * but otherwise untouched (two of them trimmed to fewer postings, and nothing
 * else changed), so a shape change at the provider shows up here rather than in
 * production.
 */
public class LeverFetchTest {

    private static final String BOARD_URI = "https://api.lever.co/v0/postings/%s";

    private Fetch<LeverJobEntry> fetcher;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        fetcher = new LeverFetch(builder.build());
    }

    /**
     * Reads a fixture as UTF-8 explicitly: the captured payloads carry French
     * accents and typographic quotes, which the platform default charset on
     * Windows would mangle.
     */
    private static String fixture(String name) throws IOException {
        try (InputStream in = LeverFetchTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "Could not find " + name + " in test resources");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private AtsResponse<LeverJobEntry> fetchFixture(String board, String fixture) throws IOException {
        mockServer.expect(requestTo(BOARD_URI.formatted(board)))
                .andRespond(withSuccess(fixture(fixture), MediaType.APPLICATION_JSON));
        AtsResponse<LeverJobEntry> result = fetcher.fetchJobs(board);
        mockServer.verify();
        return result;
    }

    // ---------------------------------------------------------------- transport

    @Test
    @DisplayName("Valid board returns its single posting, fully mapped")
    void testHappyPath() throws IOException {
        AtsResponse<LeverJobEntry> result = fetchFixture("lsa-hr", "lever_single_valid_response.json");

        assertNotNull(result);
        assertEquals(1, result.jobs().size());

        LeverJobEntry job = result.jobs().getFirst();
        assertEquals("0f4b1c8c-080d-46f9-8f4e-16b739750580", job.id());
        assertEquals("Software Developer", job.text());
        assertEquals("Software Developer", job.title(), "Lever calls the title `text`");
        assertTrue(TitleFilter.keep(job.title()));
        assertEquals("BR", job.country());
        assertEquals("remote", job.workplaceType());
        assertTrue(job.hostedUrl().endsWith(job.id()), "hostedUrl is the id permalink");
        assertEquals(job.hostedUrl() + "/apply", job.applyUrl());

        Categories categories = job.categories();
        assertEquals(List.of("Brazil"), categories.allLocations());
        assertEquals("Brazil", categories.location());
        assertEquals("Information Technology", categories.team());
        assertEquals("Full-Time", categories.commitment());
        assertNull(categories.department(), "this board sets no department, and the key is simply absent");
        assertNull(categories.level(), "level is the rarest key in the payload: 34 of 12,231 postings");

        // Absence is the empty string, not null: the opposite of Ashby.
        assertEquals("", job.opening());
        assertEquals("", job.openingPlain());
        assertNotNull(job.description());
        assertNotNull(job.descriptionPlain());
        assertNotNull(job.additional());
        assertEquals(2, job.lists().size());
        assertEquals("Responsibilities", job.lists().getFirst().text());

        // salaryRange is one of the three keys that really can be absent.
        assertNull(job.salaryRange());
        assertNull(job.salaryDescription());

        // Trap guard: Lever says remote on a posting whose only label is a place.
        assertEquals("Brazil", job.locationName(), "neither workplaceType nor country may leak into the label");
    }

    @Test
    @DisplayName("Valid board returns 2 postings, one of which the title filter rejects")
    void testHappyPathWithMultipleJobs() throws IOException {
        AtsResponse<LeverJobEntry> result = fetchFixture("Termius", "lever_two_valid_responses.json");

        assertNotNull(result);
        assertEquals(2, result.jobs().size());

        LeverJobEntry product = result.jobs().get(0);
        LeverJobEntry rust = result.jobs().get(1);
        assertEquals("1bbdc24d-d912-4c9d-ac6f-4df21a62c8c9", product.id());
        assertEquals("bc2c9a2c-1242-4a31-b97b-3bf70ad54848", rust.id());
        assertEquals("Remote first/Office after relocation", product.locationName());
        assertEquals("Auckland", rust.locationName());

        assertFalse(TitleFilter.keep(product.title()), product.title());
        assertTrue(TitleFilter.keep(rust.title()), rust.title());
    }

    @Test
    @DisplayName("Postings with no location, commitment or lists deserialize and stay usable")
    void testNullFieldsPayload() throws IOException {
        // The categories keys are absent rather than null here, which is the
        // inverse of the Ashby convention and is what these two postings pin.
        AtsResponse<LeverJobEntry> result = fetchFixture("ippon", "lever_null_fields_response.json");

        assertEquals(2, result.jobs().size());
        for (LeverJobEntry job : result.jobs()) {
            Categories categories = job.categories();
            assertNotNull(categories, job.title());
            assertEquals(List.of(), categories.allLocations(), job.title());
            assertNull(categories.location(), job.title());
            assertNull(categories.commitment(), job.title());
            assertEquals(List.of(), job.lists(), job.title());

            // No location anywhere is the one case that yields no label.
            assertNull(job.locationName(), job.title());

            // Everything else the pipeline reads is still there.
            assertNotNull(job.jobId());
            assertNotNull(job.title());
            assertNotNull(job.content());
            assertNotNull(job.postedAt());
        }

        LeverJobEntry kotlin = result.jobs().getFirst();
        assertEquals("Senior Software Engineer (Kotlin) - F/H", kotlin.text());
        assertEquals("Software Engineering Practice", kotlin.categories().department());
        assertEquals("hybrid", kotlin.workplaceType());
        assertEquals("FR", kotlin.country());
        // Nothing to append: no lists and a blank `additional` leave the
        // description exactly as it arrived.
        assertEquals("", kotlin.additional());
        assertEquals(kotlin.description(), kotlin.content());

        // Non-ASCII survived the read, which is what the explicit UTF-8 above
        // and the converter's own charset handling are between them for.
        assertTrue(kotlin.description().contains("é"), "French accents must survive the round trip");
    }

    @Test
    @DisplayName("Empty board returns a non-null response with an empty job list")
    void testEmptyBoard() throws IOException {
        // Lever answers an empty board with the two-byte body `[]`.
        AtsResponse<LeverJobEntry> result = fetchFixture("tecton", "lever_empty_response.json");

        assertNotNull(result, "an empty board is a success, not a missing response");
        assertNotNull(result.jobs(), "Lever sends [], never null");
        assertTrue(result.jobs().isEmpty());
    }

    @Test
    @DisplayName("Dead slug 404s as HttpClientErrorException.NotFound")
    void testUnknownBoard() {
        // Live behaviour: 404 with the JSON body
        // {"ok":false,"error":"Document not found"} -- JSON where Ashby's is
        // plain text, but the status is handled before the body is read, so it
        // still surfaces as a status exception and not a parse failure. Roughly
        // 42% of harvested Lever slugs are dead, so this is the common case.
        mockServer.expect(requestTo(BOARD_URI.formatted("anthropic")))
                .andRespond(withResourceNotFound()
                        .body("{\"ok\":false,\"error\":\"Document not found\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(HttpClientErrorException.NotFound.class, () -> fetcher.fetchJobs("anthropic"));
        mockServer.verify();
    }

    @Test
    @DisplayName("A null body yields an empty job list rather than a null one")
    void testNullBodyBecomesEmptyList() {
        mockServer.expect(requestTo(BOARD_URI.formatted("tecton")))
                .andRespond(withSuccess().contentType(MediaType.APPLICATION_JSON));

        AtsResponse<LeverJobEntry> result = fetcher.fetchJobs("tecton");

        assertNotNull(result);
        assertEquals(List.of(), result.jobs(), "SlugSweep counts a null job list as a failed slug");
    }

    @Test
    @DisplayName("Requests for different boards share one http.client.requests series, tagged by template")
    void testRequestMetricNotTaggedByBoard() {
        // Concatenating the board name into the URI would make the uri tag one
        // series per board; the template keeps it to one. This also pins that
        // mutate() carries the observation registry onto the client LeverFetch
        // builds for itself.
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        RestClient.Builder builder = RestClient.builder().observationRegistry(observations);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Fetch<LeverJobEntry> observed = new LeverFetch(builder.build());
        for (String board : List.of("lsa-hr", "Termius")) {
            server.expect(requestTo(BOARD_URI.formatted(board)))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        observed.fetchJobs("lsa-hr");
        observed.fetchJobs("Termius");

        server.verify();
        Collection<Timer> timers = meters.get("http.client.requests").timers();
        assertEquals(1, timers.size(), "one series whatever the board");
        Timer timer = timers.iterator().next();
        assertEquals(2, timer.count());
        String uri = timer.getId().getTag("uri");
        assertTrue(uri.contains("{slug}"), "tagged with the template, but was " + uri);
    }

    // ------------------------------------------------------------ derived methods

    @Test
    @DisplayName("atsName matches the persisted AtsName string exactly")
    void testAtsNameMatchesEnum() {
        // A mismatch would make every stored row unresolvable by
        // AtsName.fromStringValue.
        assertEquals(AtsName.LEVER.stringValue(), entry("id", "Backend Engineer", List.of("Berlin")).atsName());
    }

    @Test
    @DisplayName("jobId is the raw id, and null when the payload carried none")
    void testJobId() {
        assertEquals("0f4b1c8c", entry("0f4b1c8c", "Backend Engineer", List.of()).jobId());
        assertNull(entry(null, "Backend Engineer", List.of()).jobId());
    }

    // ------------------------------------------------------------------ postedAt

    @Test
    @DisplayName("createdAt is read as epoch MILLIS, not seconds")
    void testPostedAtIsEpochMillis() {
        // THE trap. Declaring the component as an OffsetDateTime makes Jackson
        // read this very number as epoch SECONDS and answer +51164-11-04T05:20Z
        // -- with no exception and no warning. Since postedAt feeds the
        // freshness signal, every Lever job would then read as maximally fresh
        // forever. This assertion is the guard against the field being
        // "simplified" back to a date type.
        LeverJobEntry job = dated(1552439280000L);

        assertEquals(OffsetDateTime.parse("2019-03-13T01:08:00Z"), job.postedAt());
        assertEquals(2019, job.postedAt().getYear(), "seconds-as-millis would put this in year 51164");
        assertTrue(job.postedAt().getYear() < 10000, "a 13-digit value read as seconds lands five figures out");

        // Millisecond precision survives, and the offset is UTC.
        assertEquals(OffsetDateTime.parse("2026-08-06T01:51:09.633Z"), dated(1785981069633L).postedAt());
        assertEquals("Z", dated(1785981069633L).postedAt().getOffset().getId());
    }

    @Test
    @DisplayName("postedAt on a real fixture is the date Lever put in createdAt")
    void testPostedAtFromFixture() throws IOException {
        LeverJobEntry job = fetchFixture("lsa-hr", "lever_single_valid_response.json").jobs().getFirst();

        assertEquals(1785981069633L, job.createdAt());
        assertEquals(OffsetDateTime.parse("2026-08-06T01:51:09.633Z"), job.postedAt());
    }

    @Test
    @DisplayName("A posting with no createdAt dates to the epoch, which fails safe")
    void testPostedAtDefaultsToEpoch() {
        // The primitive cannot express "absent", so a payload that omitted the
        // key would read as 1970 and be aged out as too old -- silently skipped
        // rather than silently always fresh. The key was present on all 5,445
        // postings sampled, so this is the theoretical case, not the live one.
        assertEquals(OffsetDateTime.parse("1970-01-01T00:00:00Z"), dated(0L).postedAt());
    }

    // ------------------------------------------------------------------- content

    @Test
    @DisplayName("content is description, then the rendered lists, then additional")
    void testContentAssemblesAllThreeBlocks() throws IOException {
        LeverJobEntry job = fetchFixture("lsa-hr", "lever_single_valid_response.json").jobs().getFirst();

        String content = job.content();
        assertTrue(content.startsWith(job.description()), "the description leads");
        assertTrue(content.endsWith(job.additional()), "additional trails");
        assertTrue(content.contains("<h3>Responsibilities</h3><ul>"), content.substring(0, 200));
        assertTrue(content.contains("<h3>Requirements</h3><ul>"));
        for (ListItem list : job.lists()) {
            assertTrue(content.contains(list.content()), "every bullet run is carried over: " + list.text());
        }

        // Taking the description alone would have discarded the overwhelming
        // majority of what this posting publishes.
        assertTrue(content.length() > job.description().length() * 20,
                "description is " + job.description().length() + " of " + content.length() + " chars");
    }

    @Test
    @DisplayName("The <ul> wrapper is what makes each bullet its own line for the model")
    void testRenderedListsSurviveJdPreProcess() throws IOException {
        LeverJobEntry job = fetchFixture("lsa-hr", "lever_single_valid_response.json").jobs().getFirst();

        String plain = JdPreProcess.toPlainText(job.content());

        assertTrue(plain.contains("Responsibilities"), "the heading is kept as a line of its own");
        assertTrue(plain.contains("Requirements"));
        // li and h3 are both in JdPreProcess.BLOCK_SELECTOR, so one bullet is
        // one line -- one requirement -- rather than a run-on paragraph.
        assertTrue(plain.lines().count() > job.lists().size() + 2,
                "expected one line per bullet, got " + plain.lines().count());
        assertFalse(plain.contains("<li>"), "the markup itself is stripped");
    }

    @Test
    @DisplayName("content excludes salaryDescription, which is pay boilerplate rather than the job")
    void testContentExcludesSalaryDescription() throws IOException {
        LeverJobEntry job = fetchFixture("alimentiv-2", "lever_multi_location_response.json").jobs().getFirst();

        assertEquals("<div>+ bonus</div>", job.salaryDescription(), "kept as a component");
        assertFalse(job.content().contains("+ bonus"), "but never in the text the model reads");
        assertFalse(job.content().toLowerCase().contains("bonus"));
    }

    @Test
    @DisplayName("content is HTML, not Lever's plain rendering")
    void testContentIsHtmlNotPlain() {
        LeverJobEntry job = new LeverJobEntry(
                "id", "Backend Engineer", 0L, categories(List.of("Paris")), "FR", "onsite",
                "<p>Apply <a href=\"https://example.com/apply\">here</a></p>", "Apply here",
                "<p>Apply</p>", "Apply", "", "", "", "",
                List.of(new ListItem("Requirements", "<li>Java</li>")),
                "https://jobs.lever.co/acme/id", "https://jobs.lever.co/acme/id/apply",
                null, "", "", null);

        assertTrue(job.content().startsWith("<p>Apply <a href=\"https://example.com/apply\">here</a></p>"));
        // The bullets have no plain-text variant at all, which is half the
        // reason this path is the HTML one.
        assertTrue(job.content().contains("<h3>Requirements</h3><ul><li>Java</li></ul>"));
    }

    @Test
    @DisplayName("content skips blank blocks, and is null only when all three are blank")
    void testContentSkipsBlanks() {
        // 43 sampled postings have an empty description and non-empty lists or
        // additional; taking the description alone would yield nothing for them.
        LeverJobEntry listsOnly = body("", List.of(new ListItem("Requirements", "<li>Java</li>")), "");
        assertEquals("<h3>Requirements</h3><ul><li>Java</li></ul>", listsOnly.content());

        LeverJobEntry additionalOnly = body("", List.of(), "<p>EEO</p>");
        assertEquals("<p>EEO</p>", additionalOnly.content());

        // A list with a heading but no bullets renders as nothing, rather than
        // as a dangling <h3>.
        assertNull(body("", List.of(new ListItem("Requirements", "")), "").content());
        assertNull(body("", List.of(), "").content());
        assertNull(body("", null, "").content());

        // A bullet run with no heading keeps its bullets.
        assertEquals("<ul><li>Java</li></ul>", body("", List.of(new ListItem("", "<li>Java</li>")), "").content());
    }

    // -------------------------------------------------------------- locationName

    @Test
    @DisplayName("locationName joins the 15-location worst case into one 307-char label")
    void testLocationNameWorstCase() throws IOException {
        LeverJobEntry job = fetchFixture("alimentiv-2", "lever_multi_location_response.json").jobs().getFirst();

        assertEquals("048513fc-3daa-4b57-bc3f-901f497c3370", job.id());
        assertEquals(15, job.categories().allLocations().size());

        String label = job.locationName();
        assertEquals("London, Ontario; Calgary, Alberta; Edmonton, Alberta; Halifax, Nova Scotia; "
                        + "Kingston, Ontario; Kitchener, Ontario; Montreal, Quebec; Ottawa, Ontario; "
                        + "Quebec City, Quebec; Sherbrooke, Quebec; St. Catherines, Ontario; Toronto, Ontario; "
                        + "Victoria, British Columbia; Vancouver, British Columbia; Windsor, Ontario",
                label);
        assertEquals(15, label.split("; ").length);
        assertTrue(label.startsWith(job.categories().location()), "allLocations[0] is categories.location");
        // Longer than the 261-character Datadog label location_interpretation
        // was sized for, which costs nothing: that column is TEXT.
        assertEquals(307, label.length());

        // The country is published and stays out of the label: "…; CA" would
        // read as a sixteenth location the size of a country.
        assertEquals("CA", job.country());
        assertFalse(label.endsWith("CA"));
        // So does the remote flag, for the reason documented on locationName().
        assertEquals("remote", job.workplaceType());
        assertFalse(label.toLowerCase().contains("remote"));
    }

    @Test
    @DisplayName("locationName is null when allLocations is empty or absent")
    void testLocationNameEmpty() {
        assertNull(entry("id", "Backend Engineer", List.of()).locationName());
        assertNull(entry("id", "Backend Engineer", null).locationName());
        assertNull(entry("id", "Backend Engineer", Arrays.asList(null, "", "  ")).locationName());

        LeverJobEntry noCategories = new LeverJobEntry(
                "id", "Backend Engineer", 0L, null, "FR", "onsite", "<p>JD</p>", "JD", "<p>JD</p>", "JD",
                "", "", "", "", List.of(), "url", "url/apply", null, "", "", null);
        assertNull(noCategories.locationName());
    }

    @Test
    @DisplayName("locationName de-duplicates and skips blanks, preserving order")
    void testLocationNameDistinct() {
        // Without .distinct() this reads "Berlin; Berlin; Munich", which the
        // extractor would resolve as two separate Berlins.
        assertEquals("Berlin; Munich",
                entry("id", "T", List.of("Berlin", "Berlin", "Munich")).locationName());
        assertEquals("Berlin; Munich",
                entry("id", "T", Arrays.asList("Berlin", null, "  ", "Munich")).locationName());
        assertEquals("Munich; Berlin",
                entry("id", "T", List.of("Munich", "Berlin")).locationName(), "Lever's order is kept");
    }

    // -------------------------------------------------------------- salaryRange

    @Test
    @DisplayName("salaryRange binds to BigDecimal, so a float range cannot truncate")
    void testSalaryRangeIsBigDecimal() throws IOException {
        LeverJobEntry job = fetchFixture("alimentiv-2", "lever_multi_location_response.json").jobs().getFirst();

        SalaryRange salary = job.salaryRange();
        assertNotNull(salary);
        assertEquals(0, new BigDecimal("89000").compareTo(salary.min()));
        assertEquals(0, new BigDecimal("148000").compareTo(salary.max()));
        assertEquals("CAD", salary.currency());
        assertEquals("per-year-salary", salary.interval());

        // Lever sends these as integers on most postings and as floats on some;
        // a Long component would either fail or round the fractional ones away.
        ObjectMapper mapper = new ObjectMapper();
        SalaryRange fractional = mapper.readValue(
                "{\"min\":89000.5,\"max\":148000.25,\"currency\":\"CAD\",\"interval\":\"per-year-salary\"}",
                SalaryRange.class);
        assertEquals(new BigDecimal("89000.5"), fractional.min());
        assertEquals(new BigDecimal("148000.25"), fractional.max());
    }

    // ------------------------------------------------------------------ withSlug

    @Test
    @DisplayName("withSlug preserves every other component")
    void testWithSlugPreservesAllOtherComponents() {
        LeverJobEntry original = full("lsa-hr");

        LeverJobEntry tagged = original.withSlug("other");

        assertEquals("other", tagged.slug());
        // Round-trip: putting the slug back must reproduce the original
        // exactly, which fails if withSlug drops a component.
        assertEquals(original, tagged.withSlug("lsa-hr"));
    }

    // ------------------------------------------------------------- polymorphism

    @Test
    @DisplayName("A LeverJobEntry round-trips through the polymorphic AtsJobEntry type")
    void testPolymorphicRoundTrip() {
        // Guards the trap documented on LeverFetch: the read path needs the
        // type discriminator switched off, but switching it off on the record
        // itself would strip it on WRITE too, so every job_data row would be
        // persisted with no "type", fall through to
        // defaultImpl = GreenhouseJobEntry on read, and throw. Nothing would
        // fail at fetch time to reveal it.
        ObjectMapper mapper = new ObjectMapper();
        AtsJobEntry original = full("lsa-hr");

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"LEVER\""),
                "the discriminator must be written, or this reads back as Greenhouse: " + json);

        AtsJobEntry back = mapper.readValue(json, AtsJobEntry.class);
        assertInstanceOf(LeverJobEntry.class, back);
        assertEquals(original, back);
        assertEquals("Lever", back.atsName());
        assertEquals("Brazil; Sao Paulo", back.locationName());
        assertEquals(OffsetDateTime.parse("2019-03-13T01:08:00Z"), back.postedAt());
    }

    @Test
    @DisplayName("An entry read off the wire still persists and reads back as a LeverJobEntry")
    void testFetchedEntryRoundTripsThroughPersistence() throws IOException {
        // The pairing is the point, and neither half proves it alone: the mixin
        // has to be confined to LeverFetch's own mapper, so that the SAME object
        // the HTTP read produced still carries "type":"LEVER" when the default
        // mapper writes it into job_data.
        LeverJobEntry fetched = fetchFixture("lsa-hr", "lever_single_valid_response.json")
                .jobs().getFirst().withSlug("lsa-hr");

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString((AtsJobEntry) fetched);
        assertTrue(json.contains("\"type\":\"LEVER\""), "the default mapper is untouched by the read-side mixin");

        AtsJobEntry back = mapper.readValue(json, AtsJobEntry.class);
        assertInstanceOf(LeverJobEntry.class, back);
        assertEquals(fetched, back, "every component survives the column round trip");
        assertEquals(fetched.content(), back.content());
        assertEquals(fetched.postedAt(), back.postedAt());
        assertEquals("lsa-hr", back.slug());
    }

    @Test
    @DisplayName("A second fetcher shares no mapper state with the first, and neither leaks")
    void testMixinDoesNotEscapeTheFetcher() throws IOException {
        // The mixin lives on a JsonMapper built inside LeverFetch, so a plain
        // mapper must still refuse a LeverJobEntry with no discriminator.
        fetchFixture("lsa-hr", "lever_single_valid_response.json");

        ObjectMapper plain = new ObjectMapper();
        assertThrows(InvalidTypeIdException.class,
                () -> plain.readValue("[{\"id\":\"x\",\"text\":\"T\"}]", LeverJobEntry[].class),
                "without the mixin the inherited @JsonTypeInfo still demands a type id");
    }

    // -------------------------------------------------------------------- helpers

    private static Categories categories(List<String> allLocations) {
        return new Categories(allLocations, allLocations == null || allLocations.isEmpty() ? null
                : allLocations.getFirst(), "Platform", "Engineering", "Full-time", "Senior");
    }

    private static LeverJobEntry entry(String id, String title, List<String> allLocations) {
        return new LeverJobEntry(id, title, 0L, categories(allLocations), "FR", "onsite",
                "<p>JD</p>", "JD", "<p>JD</p>", "JD", "", "", "", "", List.of(),
                "https://jobs.lever.co/acme/" + id, "https://jobs.lever.co/acme/" + id + "/apply",
                null, "", "", null);
    }

    private static LeverJobEntry dated(long createdAt) {
        return new LeverJobEntry("id", "Backend Engineer", createdAt, categories(List.of("Paris")), "FR",
                "onsite", "<p>JD</p>", "JD", "<p>JD</p>", "JD", "", "", "", "", List.of(),
                "url", "url/apply", null, "", "", null);
    }

    private static LeverJobEntry body(String description, List<ListItem> lists, String additional) {
        return new LeverJobEntry("id", "Backend Engineer", 0L, categories(List.of("Paris")), "FR", "onsite",
                description, "", description, "", "", "", additional, "", lists,
                "url", "url/apply", null, "", "", null);
    }

    /** Every component populated, so a dropped one shows up as an inequality. */
    private static LeverJobEntry full(String slug) {
        return new LeverJobEntry(
                "0f4b1c8c-080d-46f9-8f4e-16b739750580", "Backend Engineer", 1552439280000L,
                new Categories(List.of("Brazil", "Sao Paulo"), "Brazil", "Information Technology",
                        "Engineering", "Full-Time", "Senior"),
                "BR", "remote",
                "<div>Opening</div><div>Body</div>", "Opening Body", "<div>Body</div>", "Body",
                "<div>Opening</div>", "Opening",
                "<div>EEO statement</div>", "EEO statement",
                List.of(new ListItem("Responsibilities", "<li>Ship</li>"),
                        new ListItem("Requirements", "<li>Java</li>")),
                "https://jobs.lever.co/lsa-hr/0f4b1c8c-080d-46f9-8f4e-16b739750580",
                "https://jobs.lever.co/lsa-hr/0f4b1c8c-080d-46f9-8f4e-16b739750580/apply",
                new SalaryRange(new BigDecimal("89000"), new BigDecimal("148000.50"), "CAD", "per-year-salary"),
                "<div>+ bonus</div>", "+ bonus",
                slug);
    }
}
