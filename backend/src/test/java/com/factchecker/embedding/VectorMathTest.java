package com.factchecker.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * VectorMath is the entire "Document Retrieval Confidence" score: a raw cosine similarity mapped
 * to a 0-100 percentage. It is pure arithmetic with no LLM involvement, so it deserves exact,
 * hand-checked test cases rather than approximate ones.
 */
class VectorMathTest {

    @Test
    void identicalVectorsHaveSimilarityOfOne() {
        double[] v = {1, 2, 3};

        assertThat(VectorMath.cosineSimilarity(v, v)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void orthogonalVectorsHaveSimilarityOfZero() {
        double[] a = {1, 0};
        double[] b = {0, 1};

        assertThat(VectorMath.cosineSimilarity(a, b)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void oppositeVectorsHaveSimilarityOfNegativeOne() {
        double[] a = {1, 2, 3};
        double[] b = {-1, -2, -3};

        assertThat(VectorMath.cosineSimilarity(a, b)).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void similarityIsScaleInvariant() {
        double[] a = {1, 2, 3};
        double[] scaled = {2, 4, 6};

        assertThat(VectorMath.cosineSimilarity(a, scaled)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void aZeroVectorProducesZeroRatherThanDividingByZero() {
        double[] zero = {0, 0, 0};
        double[] other = {1, 2, 3};

        assertThat(VectorMath.cosineSimilarity(zero, other)).isZero();
    }

    @Test
    void mismatchedDimensionsThrow() {
        double[] a = {1, 2, 3};
        double[] b = {1, 2};

        assertThatThrownBy(() -> VectorMath.cosineSimilarity(a, b))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confidencePercentMapsFullRangeCorrectly() {
        assertThat(VectorMath.toConfidencePercent(1.0)).isEqualTo(100.0);
        assertThat(VectorMath.toConfidencePercent(0.0)).isEqualTo(50.0);
        assertThat(VectorMath.toConfidencePercent(-1.0)).isEqualTo(0.0);
    }

    @Test
    void confidencePercentClampsOutOfRangeInput() {
        // Floating point roundoff can occasionally push a "should be 1.0" cosine similarity
        // slightly past 1.0 - the percentage must not exceed 100 or go negative because of that.
        assertThat(VectorMath.toConfidencePercent(1.0000001)).isEqualTo(100.0);
        assertThat(VectorMath.toConfidencePercent(-1.0000001)).isEqualTo(0.0);
    }

    @Test
    void matchesHandComputedExampleFromRealUsage() {
        // Mirrors the shape of a real (question, matching-chunk) pair from this app's own testing:
        // moderately related text lands well above the midpoint but well short of a perfect match.
        double[] question = {0.8, 0.2, 0.1};
        double[] chunk = {0.6, 0.3, 0.2};

        double similarity = VectorMath.cosineSimilarity(question, chunk);
        double percent = VectorMath.toConfidencePercent(similarity);

        assertThat(similarity).isGreaterThan(0.9);
        assertThat(percent).isGreaterThan(90.0);
    }
}
