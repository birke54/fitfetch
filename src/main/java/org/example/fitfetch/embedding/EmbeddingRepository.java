package org.example.fitfetch.embedding;

import org.example.fitfetch.domain.Embedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Spring Data JPA repository for the {@link Embedding} vector cache. */
@Repository
public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {

    /**
     * @param model        the embedding model
     * @param inputSha256s hashes of the texts wanted
     * @return the vectors already cached for them; texts not yet embedded are
     *         simply absent
     */
    List<Embedding> findByModelAndInputSha256In(String model, Collection<String> inputSha256s);
}
