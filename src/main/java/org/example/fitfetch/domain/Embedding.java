package org.example.fitfetch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.example.fitfetch.embedding.Vectors;

import java.time.OffsetDateTime;

/**
 * JPA entity for one cached vector in the {@code embeddings} table.
 *
 * <p>Keyed by the model and the SHA-256 of the exact text embedded, prefix
 * included, rather than by what the text belongs to. The same text is then
 * embedded once however many jobs or bullets hold it, and a restart re-embeds
 * nothing. The text itself is not stored: it lives with the job signal or in
 * the profile.
 */
@Entity
@Table(
        name = "embeddings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_embeddings_model_input",
                columnNames = {"model", "input_sha256"}
        )
)
public class Embedding {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(name = "model", nullable = false, length = 64)
    private String model;

    @Column(name = "input_sha256", nullable = false, length = 64)
    private String inputSha256;

    @Column(name = "dimensions", nullable = false)
    private int dimensions;

    @Column(name = "vector", nullable = false)
    private byte[] vector;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** No-arg constructor required by JPA; not intended for application use. */
    protected Embedding() {
    }

    /**
     * @param model       the model that produced it
     * @param inputSha256 hex SHA-256 of the exact text embedded
     * @param vector      the vector
     * @param now         when it was made
     */
    public Embedding(String model, String inputSha256, float[] vector, OffsetDateTime now) {
        this.model = model;
        this.inputSha256 = inputSha256;
        this.dimensions = vector.length;
        this.vector = Vectors.toBytes(vector);
        this.createdAt = now;
    }

    /** @return the model that produced it */
    public String getModel() {
        return model;
    }

    /** @return hex SHA-256 of the exact text embedded */
    public String getInputSha256() {
        return inputSha256;
    }

    /** @return the vector */
    public float[] getVector() {
        return Vectors.fromBytes(vector);
    }
}
