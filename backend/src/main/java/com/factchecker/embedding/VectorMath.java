package com.factchecker.embedding;

public final class VectorMath {

    private VectorMath() {
    }

    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Maps cosine similarity [-1, 1] to a [0, 100] confidence percentage, clamped. */
    public static double toConfidencePercent(double cosineSimilarity) {
        double clamped = Math.max(-1.0, Math.min(1.0, cosineSimilarity));
        return Math.round(((clamped + 1.0) / 2.0) * 10000.0) / 100.0;
    }
}
