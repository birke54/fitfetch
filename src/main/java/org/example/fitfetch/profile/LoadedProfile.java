package org.example.fitfetch.profile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A profile read from disk and checked, with what identifies this version of it.
 *
 * @param profile  the profile, with skills in canonical names
 * @param sha256   hex SHA-256 of the file. Matches and embeddings record it, so
 *                 they can tell when the profile has changed since
 * @param source   the file it was read from
 * @param warnings problems worth fixing that do not stop it being used
 */
public record LoadedProfile(CandidateProfile profile, String sha256, Path source, List<String> warnings) {

    public LoadedProfile {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(sha256, "sha256");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
