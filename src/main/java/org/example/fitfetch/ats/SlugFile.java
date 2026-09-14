package org.example.fitfetch.ats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyException;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;

/**
 * Reads one provider's board identifiers ("slugs") from the slugs resource
 * ({@code app.slug-ids}), which holds every provider's under
 * {@code { "ats": { "<provider>": [ ... ] } }}.
 *
 * <p>This is a stateless holder; all members are static.
 */
public final class SlugFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlugFile.class);

    private SlugFile() {
    }

    /**
     * @param resource the slugs resource
     * @param ats      the provider; its slugs are under its name in lower case
     * @param parallel whether to stream the slug array in parallel while
     *                 parsing ({@code app.fetch.read-slugs-list-in-parallel})
     * @return the provider's slugs, in file order
     * @throws FileNotFoundException if the resource does not exist
     * @throws IOException           if the resource cannot be read
     * @throws KeyException          if the provider has no slug list
     */
    public static List<String> load(Resource resource, AtsName ats, boolean parallel)
            throws IOException, KeyException {
        if (!resource.exists()) {
            throw new FileNotFoundException("Slugs file not found: " + resource.getFilename());
        }
        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        }

        String key = ats.stringValue().toLowerCase(Locale.ROOT);
        JsonNode root = new ObjectMapper().readTree(bytes);
        if (root == null || !root.has("ats") || !root.get("ats").has(key)) {
            throw new KeyException("Invalid slugs JSON schema structure. Missing 'ats." + key + "' node.");
        }

        JsonNode slugs = root.get("ats").get(key);
        LOGGER.info("Loaded {} slugs for the {} ATS", slugs.size(), ats.stringValue());
        return StreamSupport.stream(slugs.spliterator(), parallel)
                .map(JsonNode::asString)
                .toList();
    }
}
