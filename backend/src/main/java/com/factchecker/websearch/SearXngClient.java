package com.factchecker.websearch;

import com.factchecker.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Web search client backed by a self-hosted SearXNG instance (open-source metasearch engine,
 * aggregates results from multiple search engines, no API key, no per-query cost). Requires
 * SearXNG's JSON output format to be enabled in its settings.yml (search.formats: [html, json]),
 * since it's disabled by default for public instances.
 */
@Component
public class SearXngClient {

    private static final Logger log = LoggerFactory.getLogger(SearXngClient.class);

    private final RestClient restClient;

    public SearXngClient(AppProperties appProperties, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(appProperties.getSearxng().getBaseUrl()).build();
    }

    public List<SearXngResult> search(String query, int maxResults) {
        try {
            SearXngResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .build())
                    .retrieve()
                    .body(SearXngResponse.class);

            if (response == null || response.results() == null) return List.of();
            return response.results().stream().limit(maxResults).toList();
        } catch (Exception ex) {
            log.error("SearXNG search failed for query '{}': {}", query, ex.getMessage());
            throw new WebSearchUnavailableException("Web search request failed: " + ex.getMessage(), ex);
        }
    }

    public record SearXngResult(String title, String url, String content, Double score, String publishedDate) {
    }

    private record SearXngResponse(List<SearXngResult> results) {
    }
}
