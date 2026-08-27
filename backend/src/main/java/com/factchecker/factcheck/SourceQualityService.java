package com.factchecker.factcheck;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Set;

/**
 * Deterministic domain-based authority heuristic. This intentionally does NOT ask the LLM "is this
 * source trustworthy" - source quality is a factual property of the domain, not a judgment call the
 * model should be inventing.
 */
@Service
public class SourceQualityService {

    private static final Set<String> PRIMARY_DOMAINS = Set.of(
            "who.int", "nih.gov", "cdc.gov", "fda.gov", "nasa.gov", "un.org", "nature.com",
            "sciencedirect.com", "nejm.org", "thelancet.com", "cochranelibrary.com",
            "pubmed.ncbi.nlm.nih.gov", "ncbi.nlm.nih.gov", "science.org", "jamanetwork.com",
            "bmj.com", "europa.eu"
    );

    private static final Set<String> ESTABLISHED_DOMAINS = Set.of(
            "reuters.com", "apnews.com", "bbc.com", "bbc.co.uk", "npr.org", "nytimes.com",
            "wsj.com", "theguardian.com", "economist.com", "bloomberg.com", "washingtonpost.com",
            "aljazeera.com", "afp.com"
    );

    private static final Set<String> CONTEXT_ONLY_DOMAINS = Set.of(
            "wikipedia.org", "reddit.com", "quora.com", "medium.com"
    );

    public AuthorityTier classify(String url) {
        String domain = extractDomain(url);
        if (domain == null) return AuthorityTier.UNKNOWN;

        if (matches(domain, PRIMARY_DOMAINS)) return AuthorityTier.PRIMARY_AUTHORITY;
        if (matches(domain, ESTABLISHED_DOMAINS)) return AuthorityTier.ESTABLISHED;
        if (matches(domain, CONTEXT_ONLY_DOMAINS)) return AuthorityTier.CONTEXT_ONLY;

        if (domain.endsWith(".gov") || domain.endsWith(".edu") || domain.endsWith(".int")
                || domain.endsWith(".gov.uk") || domain.endsWith(".ac.uk")) {
            return AuthorityTier.PRIMARY_AUTHORITY;
        }

        return AuthorityTier.UNKNOWN;
    }

    private boolean matches(String domain, Set<String> known) {
        return known.stream().anyMatch(k -> domain.equals(k) || domain.endsWith("." + k));
    }

    /** Public - reused by SourceIndependenceService to cluster sources sharing the same origin domain. */
    public String extractDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return null;
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception ex) {
            return null;
        }
    }
}
