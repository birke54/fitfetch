package org.example.fitfetch.match;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires match scoring. */
@Configuration
public class MatchConfig {

    /** @return the scoring rules */
    @Bean
    public MatchScorer matchScorer() {
        return new MatchScorer();
    }
}
