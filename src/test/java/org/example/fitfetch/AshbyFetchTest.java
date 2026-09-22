package org.example.fitfetch;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.example.fitfetch.ats.AtsName;
import org.example.fitfetch.fetching.AshbyFetch;
import org.example.fitfetch.fetching.Fetch;
import org.example.fitfetch.fetching.records.AshbyJobEntry;
import org.example.fitfetch.fetching.records.AshbySubRecords.Address;
import org.example.fitfetch.fetching.records.AshbySubRecords.Compensation;
import org.example.fitfetch.fetching.records.AshbySubRecords.CompensationComponent;
import org.example.fitfetch.fetching.records.AshbySubRecords.PostalAddress;
import org.example.fitfetch.fetching.records.AshbySubRecords.SecondaryLocation;
import org.example.fitfetch.fetching.records.AtsJobEntry;
import org.example.fitfetch.fetching.records.AtsResponse;
import org.example.fitfetch.fetching.records.GreenhouseJobEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Transport and record tests for the Ashby integration.
 *
 * <p>The fixtures are verbatim captures of live Ashby boards, pretty-printed
 * but otherwise untouched, so a shape change at the provider shows up here
 * rather than in production.
 */
public class AshbyFetchTest {

    private static final String BOARD_URI =
            "https://api.ashbyhq.com/posting-api/job-board/%s?includeCompensation=true";

    private Fetch<AshbyJobEntry> fetcher;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        fetcher = new AshbyFetch(builder.build());
    }

    /**
     * Reads a fixture as UTF-8 explicitly: the captured payloads carry
     * {@code São Paulo} and emoji, which the platform default charset on
     * Windows would mangle.
     */
    private static String fixture(String name) throws IOException {
        try (InputStream in = AshbyFetchTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "Could not find " + name + " in test resources");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private AtsResponse<AshbyJobEntry> fetchFixture(String board, String fixture) throws IOException {
        mockServer.expect(requestTo(BOARD_URI.formatted(board)))
                .andRespond(withSuccess(fixture(fixture), MediaType.APPLICATION_JSON));
        AtsResponse<AshbyJobEntry> result = fetcher.fetchJobs(board);
        mockServer.verify();
        return result;
    }

    // ---------------------------------------------------------------- transport

    @Test
    @DisplayName("Valid board returns its single posting, fully mapped")
    void testHappyPath() throws IOException {
        AtsResponse<AshbyJobEntry> result = fetchFixture("Ankorstore", "ashby_single_valid_response.json");

        assertNotNull(result);
        assertEquals(1, result.jobs().size());

        AshbyJobEntry job = result.jobs().getFirst();
        assertEquals("91a9f25c-1b73-47fd-8915-fdf8f28557ca", job.id());
        assertEquals("People Operations Partner H/F", job.title());
        assertEquals("People", job.department());
        assertEquals("People", job.team());
        assertEquals("FullTime", job.employmentType());
        assertEquals("Paris", job.location());
        assertEquals(Boolean.TRUE, job.isListed());
        assertEquals(Boolean.FALSE, job.shouldDisplayCompensationOnJobPostings());
        assertNotNull(job.descriptionHtml());
        assertNotNull(job.descriptionPlain());
        assertTrue(job.jobUrl().contains(job.id()), "jobUrl is the id permalink");

        // publishedAt arrives as +00:00 with millisecond precision and parses
        // straight into OffsetDateTime, with no custom format needed.
        assertEquals(OffsetDateTime.parse("2026-08-26T12:29:34.896Z"), job.publishedAt());

        // The structured address describes the office, not the work location.
        PostalAddress postal = job.address().postalAddress();
        assertEquals("Paris", postal.addressLocality());
        assertEquals("France", postal.addressCountry());

        // Trap guard: Ashby says isRemote on a posting it also calls Hybrid.
        assertEquals(Boolean.TRUE, job.isRemote());
        assertEquals("Hybrid", job.workplaceType());
        assertEquals("Paris", job.locationName(), "neither flag may leak into the label");
    }

    @Test
    @DisplayName("Valid board returns 2 postings with full compensation")
    void testHappyPathWithMultipleJobs() throws IOException {
        AtsResponse<AshbyJobEntry> result = fetchFixture("subsets", "ashby_two_valid_responses.json");

        assertNotNull(result);
        assertEquals(2, result.jobs().size());
        assertEquals("9ed929b8-3089-452b-88e1-5910f53d82c9", result.jobs().get(0).id());
        assertEquals("2a9bc526-0ff1-4136-8642-2c066800e4df", result.jobs().get(1).id());
        assertEquals("Copenhagen", result.jobs().get(0).locationName());
        assertEquals("New York City", result.jobs().get(1).locationName());
    }

    @Test
    @DisplayName("Compensation tiers and summary components survive deserialization")
    void testCompensationIsFullyModelled() throws IOException {
        AshbyJobEntry job = fetchFixture("subsets", "ashby_two_valid_responses.json").jobs().getFirst();

        Compensation compensation = job.compensation();
        assertNotNull(compensation);
        assertNotNull(compensation.compensationTierSummary());
        assertEquals("$115K - $160K", compensation.scrapeableCompensationSalarySummary());
        assertEquals(1, compensation.compensationTiers().size());

        List<CompensationComponent> components = compensation.compensationTiers().getFirst().components();
        assertEquals(2, components.size());
        CompensationComponent salary = components.getFirst();
        assertEquals("Salary", salary.compensationType());
        assertEquals("1 YEAR", salary.interval());
        assertEquals("USD", salary.currencyCode());
        assertEquals(0, new BigDecimal("115000").compareTo(salary.minValue()));
        assertEquals(0, new BigDecimal("160000").compareTo(salary.maxValue()));
        assertNotNull(salary.id());

        // Equity carries no currency and a fractional value: BigDecimal, not
        // double, is what keeps 0.05 exactly 0.05 in the job_data column.
        CompensationComponent equity = components.get(1);
        assertEquals("EquityPercentage", equity.compensationType());
        assertNull(equity.currencyCode());
        assertEquals(0, new BigDecimal("0.05").compareTo(equity.minValue()));

        // summaryComponents reuse the component shape without id/summary.
        assertEquals(2, compensation.summaryComponents().size());
        assertNull(compensation.summaryComponents().getFirst().id());
        assertNull(compensation.summaryComponents().getFirst().summary());
        assertEquals("Salary", compensation.summaryComponents().getFirst().compensationType());
    }

    @Test
    @DisplayName("Postings with null workplaceType, isRemote and address deserialize and stay usable")
    void testNullFieldsPayload() throws IOException {
        // 22.3% of live postings look like this; boxed Booleans are what keep it
        // from blowing up on unboxing.
        AtsResponse<AshbyJobEntry> result = fetchFixture("stargate-foundation", "ashby_null_fields_response.json");

        assertEquals(3, result.jobs().size());
        for (AshbyJobEntry job : result.jobs()) {
            assertNull(job.workplaceType(), job.title());
            assertNull(job.isRemote(), job.title());
            assertNull(job.address(), job.title());

            // Everything the pipeline actually reads is still there.
            assertNotNull(job.jobId());
            assertNotNull(job.title());
            assertNotNull(job.content());
            assertNotNull(job.postedAt());
            assertNotNull(job.locationName());
        }
        assertEquals("London, UK", result.jobs().getFirst().locationName());
    }

    @Test
    @DisplayName("Empty board returns a non-null response with an empty job list")
    void testEmptyBoard() throws IOException {
        AtsResponse<AshbyJobEntry> result = fetchFixture("Deel", "ashby_empty_response.json");

        assertNotNull(result, "an empty board is a success, not a missing response");
        assertNotNull(result.jobs(), "Ashby sends [], never null");
        assertTrue(result.jobs().isEmpty());
    }

    @Test
    @DisplayName("Unknown board 404s as HttpClientErrorException.NotFound")
    void testUnknownBoard() {
        // Live behaviour: 404 with the plain-text body `Not Found`, not JSON.
        mockServer.expect(requestTo(BOARD_URI.formatted("Anthropic")))
                .andRespond(withResourceNotFound().body("Not Found"));

        assertThrows(HttpClientErrorException.NotFound.class, () -> fetcher.fetchJobs("Anthropic"));
        mockServer.verify();
    }

    @Test
    @DisplayName("Requests for different boards share one http.client.requests series, tagged by template")
    void testRequestMetricNotTaggedByBoard() {
        // Concatenating the board name into the URI would make the uri tag one
        // series per board; the template keeps it to one.
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        RestClient.Builder builder = RestClient.builder().observationRegistry(observations);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Fetch<AshbyJobEntry> observed = new AshbyFetch(builder.build());
        for (String board : List.of("Ankorstore", "subsets")) {
            server.expect(requestTo(BOARD_URI.formatted(board)))
                    .andRespond(withSuccess("{\"jobs\":[],\"apiVersion\":\"1\"}", MediaType.APPLICATION_JSON));
        }

        observed.fetchJobs("Ankorstore");
        observed.fetchJobs("subsets");

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
        assertEquals(AtsName.ASHBY.stringValue(), entry("id", "Paris", List.of()).atsName());
    }

    @Test
    @DisplayName("content is the HTML body, not Ashby's plain rendering")
    void testContentIsHtmlNotPlain() {
        AshbyJobEntry job = new AshbyJobEntry(
                "id", "Backend Engineer", null, null, null, "Paris", List.of(), null, null, null,
                "<p>Apply <a href=\"https://example.com/apply\">here</a></p>",
                "Apply here (https://example.com/apply)",
                null, null, null, null, null, null, null);

        // The plain rendering inlines the href; the HTML one does not, and
        // JdPreProcess strips the tag but keeps the anchor text.
        assertEquals("<p>Apply <a href=\"https://example.com/apply\">here</a></p>", job.content());
        assertFalse(job.content().contains("(https://example.com/apply)"));
    }

    @Test
    @DisplayName("locationName joins the 19-secondary worst case into one 199-char label")
    void testLocationNameWorstCase() throws IOException {
        AshbyJobEntry job = fetchFixture("Ashby", "ashby_multi_location_response.json").jobs().getFirst();

        assertEquals("7458d4e9-da2e-47bd-98cb-adfda43d42b2", job.id());
        assertEquals("Engineering Manager - EU", job.title());
        assertEquals(19, job.secondaryLocations().size());

        String label = job.locationName();
        assertEquals("Remote - European Union; Spain; Italy; Germany; Switzerland; Denmark; Norway; Croatia; "
                        + "Ireland; Stockholm; Romania; Austria; Barcelona; Netherlands; Portugal; France; Berlin; "
                        + "Sweden; Hungary; Estonia",
                label);
        assertTrue(label.startsWith(job.location()), "the primary location comes first");
        assertEquals(20, label.split("; ").length);
        // Comfortably inside the 261-char LocationInterpretation column.
        assertEquals(199, label.length());
        assertTrue(label.length() <= 261);
    }

    @Test
    @DisplayName("locationName is the primary alone when there are no secondaries")
    void testLocationNameWithoutSecondaries() {
        assertEquals("Paris", entry("id", "Paris", List.of()).locationName());
        assertEquals("Paris", entry("id", "Paris", null).locationName());
    }

    @Test
    @DisplayName("locationName is null only when the primary location is null")
    void testLocationNameNullPrimary() {
        // Nothing named anywhere is the only case that yields no label.
        assertNull(entry("id", null, List.of()).locationName());
        // Secondaries alone still make a label. Dropping them because the
        // primary happened to be absent would strand the job with nothing for
        // the location pass to resolve, which is what this method exists to
        // prevent.
        assertEquals("Spain", entry("id", null, List.of(secondary("Spain"))).locationName());
        assertEquals("Spain; Italy",
                entry("id", null, List.of(secondary("Spain"), secondary("Italy"))).locationName());
    }

    @Test
    @DisplayName("locationName de-duplicates a secondary that repeats the primary")
    void testLocationNameDistinct() {
        // Without .distinct() this reads "Berlin; Berlin; Munich", which the
        // extractor would resolve as two separate Berlins.
        AshbyJobEntry job = entry("id", "Berlin", List.of(secondary("Berlin"), secondary("Munich")));
        assertEquals("Berlin; Munich", job.locationName());

        // Duplicates among the secondaries themselves collapse too.
        assertEquals("Berlin; Munich",
                entry("id", "Berlin", List.of(secondary("Munich"), secondary("Munich"))).locationName());
    }

    @Test
    @DisplayName("locationName skips null secondary entries and null secondary labels")
    void testLocationNameNullSecondaries() {
        AshbyJobEntry job = entry("id", "Berlin",
                java.util.Arrays.asList(null, secondary(null), secondary("Munich")));
        assertEquals("Berlin; Munich", job.locationName());
    }

    @Test
    @DisplayName("jobId is the raw id, and null when the payload carried none")
    void testJobId() {
        assertEquals("91a9f25c", entry("91a9f25c", "Paris", List.of()).jobId());
        assertNull(entry(null, "Paris", List.of()).jobId());
    }

    @Test
    @DisplayName("postedAt is publishedAt with no fallback")
    void testPostedAt() {
        OffsetDateTime published = OffsetDateTime.parse("2024-03-04T14:29:08.532Z");
        AshbyJobEntry job = new AshbyJobEntry(
                "id", "T", null, null, null, "Paris", List.of(), null, null, null, null, null,
                published, null, null, null, null, null, null);
        assertEquals(published, job.postedAt());

        // The payload has no second date to fall back to, so a missing
        // publishedAt means the caller supplies the fetch time instead.
        AshbyJobEntry undated = new AshbyJobEntry(
                "id", "T", null, null, null, "Paris", List.of(), null, null, null, null, null,
                null, null, null, null, null, null, null);
        assertNull(undated.postedAt());
    }

    @Test
    @DisplayName("withSlug preserves every other component")
    void testWithSlugPreservesAllOtherComponents() {
        AshbyJobEntry original = new AshbyJobEntry(
                "91a9f25c", "Backend Engineer", "Engineering", "Platform", "FullTime", "Paris",
                List.of(secondary("Berlin")),
                new Address(new PostalAddress("Paris", "Ile-de-France", "France", "75002")),
                Boolean.TRUE, "Hybrid", "<p>JD</p>", "JD",
                OffsetDateTime.parse("2026-08-26T12:29:34.896Z"),
                "https://jobs.ashbyhq.com/Ankorstore/91a9f25c", "https://example.com/apply",
                Boolean.TRUE,
                new Compensation(null, null, List.of(), List.of()),
                Boolean.FALSE, null);

        AshbyJobEntry tagged = original.withSlug("Ankorstore");

        assertEquals("Ankorstore", tagged.slug());
        // Round-trip: clearing the slug again must reproduce the original
        // exactly, which fails if withSlug drops a component.
        assertEquals(original, tagged.withSlug(null));
    }

    // ------------------------------------------------------------- polymorphism

    @Test
    @DisplayName("An AshbyJobEntry round-trips through the polymorphic AtsJobEntry type")
    void testPolymorphicRoundTrip() {
        // Guards a real trap: @JsonTypeInfo on AtsJobEntry sets
        // defaultImpl = GreenhouseJobEntry.class, so any job_data payload
        // written without the discriminator silently reads back as a Greenhouse
        // entry with every field null rather than failing loudly.
        ObjectMapper mapper = new ObjectMapper();
        AtsJobEntry original = new AshbyJobEntry(
                "91a9f25c", "Backend Engineer", "Engineering", "Platform", "FullTime", "Paris",
                List.of(secondary("Berlin")),
                new Address(new PostalAddress("Paris", "Ile-de-France", "France", "75002")),
                Boolean.TRUE, "Hybrid", "<p>JD</p>", "JD",
                OffsetDateTime.parse("2026-08-26T12:29:34.896Z"),
                "https://jobs.ashbyhq.com/Ankorstore/91a9f25c", "https://example.com/apply",
                Boolean.TRUE, new Compensation(null, null, List.of(), List.of()),
                Boolean.FALSE, "Ankorstore");

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"ASHBY\""),
                "the discriminator must be written, or this reads back as Greenhouse: " + json);

        AtsJobEntry back = mapper.readValue(json, AtsJobEntry.class);
        assertInstanceOf(AshbyJobEntry.class, back);
        assertEquals(original, back);
        assertEquals("Ashby", back.atsName());
        assertEquals("Paris; Berlin", back.locationName());
    }

    @Test
    @DisplayName("A payload missing the discriminator falls back to Greenhouse, which is why ASHBY must be written")
    void testMissingDiscriminatorFallsBackToGreenhouse() {
        ObjectMapper mapper = new ObjectMapper();

        AtsJobEntry back = mapper.readValue("{\"title\":\"Backend Engineer\",\"descriptionHtml\":\"<p>JD</p>\"}",
                AtsJobEntry.class);

        // Documenting the trap rather than endorsing it: nothing here throws,
        // the Ashby-shaped fields are simply dropped on the floor.
        assertInstanceOf(GreenhouseJobEntry.class, back);
        assertNull(back.jobId());
        assertNull(back.content());
    }

    // -------------------------------------------------------------------- helpers

    private static SecondaryLocation secondary(String location) {
        return new SecondaryLocation(location, null);
    }

    private static AshbyJobEntry entry(String id, String location, List<SecondaryLocation> secondaries) {
        return new AshbyJobEntry(id, "Backend Engineer", null, null, null, location, secondaries, null,
                null, null, "<p>JD</p>", "JD", null, null, null, null, null, null, null);
    }
}
