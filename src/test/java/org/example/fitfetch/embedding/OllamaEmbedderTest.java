package org.example.fitfetch.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaEmbedderTest {

    private static final String BASE_URL = "http://localhost:11434";
    private static final String EMBED = BASE_URL + "/api/embed";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer server;
    private OllamaEmbedder embedder;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        embedder = new OllamaEmbedder(builder.build(), BASE_URL + "/", "nomic-embed-text");
    }

    private void respond(String body) {
        server.expect(requestTo(EMBED)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("A batch goes in one call, and the vectors come back in input order")
    void testEmbedsBatch() {
        server.expect(requestTo(EMBED))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals("nomic-embed-text", body.path("model").asString());
                    assertEquals("first", body.path("input").get(0).asString());
                    assertEquals("second", body.path("input").get(1).asString());
                })
                .andRespond(withSuccess("""
                        {"model":"nomic-embed-text","embeddings":[[0.1,0.2],[0.3,0.4]]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = embedder.embed(List.of("first", "second"));

        assertArrayEquals(new float[]{0.1f, 0.2f}, vectors.get(0));
        assertArrayEquals(new float[]{0.3f, 0.4f}, vectors.get(1));
        server.verify();
    }

    @Test
    @DisplayName("Nothing to embed makes no call")
    void testEmptyMakesNoCall() {
        assertEquals(List.of(), embedder.embed(List.of()));
        server.verify();
    }

    @Test
    @DisplayName("A server error is an outage, left for the next run")
    void testServerErrorIsOutage() {
        server.expect(requestTo(EMBED)).andRespond(withServerError());

        assertThrows(EmbeddingException.class, () -> embedder.embed(List.of("text")));
    }

    @Test
    @DisplayName("An answer with no embeddings is an outage too, as when the model is still loading")
    void testNoEmbeddingsIsOutage() {
        respond("""
                {"model":"nomic-embed-text","embeddings":[]}
                """);

        assertThrows(EmbeddingException.class, () -> embedder.embed(List.of("text")));
    }

    @Test
    @DisplayName("The wrong number of vectors is not an outage, since asking again would not fix it")
    void testCountMismatchIsNotOutage() {
        respond("""
                {"model":"nomic-embed-text","embeddings":[[0.1,0.2]]}
                """);

        assertThrows(IllegalStateException.class, () -> embedder.embed(List.of("one", "two")));
    }

    @Test
    @DisplayName("Blank configuration is rejected at construction")
    void testBlankConfigRejected() {
        RestClient client = RestClient.builder().build();

        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbedder(client, " ", "m"));
        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbedder(client, BASE_URL, ""));
    }
}
