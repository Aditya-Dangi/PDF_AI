package com.factchecker.factcheck;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SourceQualityService is the deterministic half of "how trustworthy is this source" - it is
 * intentionally NOT an LLM judgment (see class javadoc), so its domain-matching rules need to be
 * exactly right rather than "probably right."
 */
class SourceQualityServiceTest {

    private final SourceQualityService service = new SourceQualityService();

    @Test
    void curatedScientificDomainIsPrimaryAuthority() {
        assertThat(service.classify("https://www.who.int/news/some-article"))
                .isEqualTo(AuthorityTier.PRIMARY_AUTHORITY);
        assertThat(service.classify("https://pubmed.ncbi.nlm.nih.gov/12345"))
                .isEqualTo(AuthorityTier.PRIMARY_AUTHORITY);
    }

    @Test
    void dotGovAndDotEduDomainsAreAlwaysPrimaryAuthorityEvenIfNotCurated() {
        assertThat(service.classify("https://www.some-agency.gov/report"))
                .isEqualTo(AuthorityTier.PRIMARY_AUTHORITY);
        assertThat(service.classify("https://research.some-university.edu/paper"))
                .isEqualTo(AuthorityTier.PRIMARY_AUTHORITY);
    }

    @Test
    void curatedNewsOrganizationIsEstablished() {
        assertThat(service.classify("https://www.reuters.com/world/some-story"))
                .isEqualTo(AuthorityTier.ESTABLISHED);
    }

    @Test
    void wikipediaIsContextOnlyNotAuthoritative() {
        assertThat(service.classify("https://en.wikipedia.org/wiki/Something"))
                .isEqualTo(AuthorityTier.CONTEXT_ONLY);
    }

    @Test
    void randomBlogIsUnknown() {
        assertThat(service.classify("https://someones-random-blog.example/post-1"))
                .isEqualTo(AuthorityTier.UNKNOWN);
    }

    @Test
    void wwwPrefixDoesNotAffectClassification() {
        assertThat(service.classify("https://www.who.int/news"))
                .isEqualTo(service.classify("https://who.int/news"));
    }

    @Test
    void subdomainOfACuratedDomainIsStillRecognized() {
        assertThat(service.classify("https://articles.reuters.com/world/some-story"))
                .isEqualTo(AuthorityTier.ESTABLISHED);
    }

    @Test
    void unparseableUrlIsUnknownRatherThanCrashing() {
        assertThat(service.classify("not a url at all")).isEqualTo(AuthorityTier.UNKNOWN);
    }
}
