package org.example.fitfetch.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The candidate profile the app runs with, if there is one.
 *
 * <p>Everything but matching works without a profile, so its absence is not an
 * error: no path configured, or no file at the path, leaves matching off. A
 * file that is there but invalid is an error, since it means a profile was
 * meant and would otherwise be silently ignored.
 */
public final class ProfileSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileSource.class);

    private final LoadedProfile profile;

    private ProfileSource(LoadedProfile profile) {
        this.profile = profile;
    }

    /**
     * @param path   {@code app.profile.path}; blank for none
     * @param loader reads and checks the file
     * @return the source, holding the profile if one was found
     * @throws InvalidProfileException if the file is there but invalid
     */
    public static ProfileSource load(String path, ProfileLoader loader) {
        Objects.requireNonNull(loader, "loader");
        if (path == null || path.isBlank()) {
            LOGGER.info("No candidate profile configured (app.profile.path); matching is off");
            return new ProfileSource(null);
        }
        Path file = Path.of(path.strip());
        if (!Files.isRegularFile(file)) {
            LOGGER.warn("No candidate profile at {}; matching is off", file);
            return new ProfileSource(null);
        }
        LoadedProfile loaded = loader.load(file);
        loaded.warnings().forEach(warning -> LOGGER.warn("Candidate profile: {}", warning));
        LOGGER.info("Loaded candidate profile {} ({} roles, {} skills, version {})", file,
                loaded.profile().roles().size(), loaded.profile().skills().size(),
                loaded.sha256().substring(0, 12));
        return new ProfileSource(loaded);
    }

    /** @return the profile, or empty if none is configured or found */
    public Optional<LoadedProfile> current() {
        return Optional.ofNullable(profile);
    }
}
