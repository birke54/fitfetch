package org.example.fitfetch.embedding;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The profile's bullet vectors, held in memory for scoring.
 *
 * <p>Tied to the profile version and the embedder that made them, so a changed
 * profile or model is noticed and the vectors are made again. They live in
 * memory only: the vectors themselves are cached in the {@code embeddings}
 * table, so rebuilding this after a restart costs no model calls.
 */
public final class ProfileEmbeddings {

    /**
     * @param profileSha256 the version of the profile the vectors are for
     * @param embedderKey   {@link EmbeddingSettings#key()} of what made them
     * @param byBulletId    each bullet's vector, by bullet id
     */
    public record Snapshot(String profileSha256, String embedderKey, Map<String, float[]> byBulletId) {

        public Snapshot {
            Objects.requireNonNull(profileSha256, "profileSha256");
            Objects.requireNonNull(embedderKey, "embedderKey");
            byBulletId = Map.copyOf(byBulletId);
        }
    }

    private volatile Snapshot snapshot;

    /** @return the vectors, or empty until the embedding pass has made them */
    public Optional<Snapshot> current() {
        return Optional.ofNullable(snapshot);
    }

    /** @return whether the vectors held are for this profile version and embedder */
    public boolean isCurrent(String profileSha256, String embedderKey) {
        Snapshot held = snapshot;
        return held != null && held.profileSha256().equals(profileSha256) && held.embedderKey().equals(embedderKey);
    }

    /** @param snapshot the vectors to hold from now on */
    void set(Snapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }
}
