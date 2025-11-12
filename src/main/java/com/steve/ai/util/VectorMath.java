package com.steve.ai.util;

/**
 * Vector mathematics utilities for embedding operations
 * Provides cosine similarity, normalization, and other vector operations
 */
public class VectorMath {
    /**
     * Calculate cosine similarity between two vectors
     * Returns value between -1 and 1, where 1 means identical vectors
     * Assumes vectors are already normalized
     * @param a First vector
     * @param b Second vector
     * @return Cosine similarity score
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimensions");
        }

        float dotProduct = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
        }

        return dotProduct;
    }

    /**
     * Calculate cosine similarity with normalization
     * Use this if vectors are not already normalized
     * @param a First vector
     * @param b Second vector
     * @return Cosine similarity score
     */
    public static float cosineSimilarityWithNormalization(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimensions");
        }

        float dotProduct = 0;
        float magnitudeA = 0;
        float magnitudeB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            magnitudeA += a[i] * a[i];
            magnitudeB += b[i] * b[i];
        }

        magnitudeA = (float) Math.sqrt(magnitudeA);
        magnitudeB = (float) Math.sqrt(magnitudeB);

        if (magnitudeA == 0 || magnitudeB == 0) {
            return 0;
        }

        return dotProduct / (magnitudeA * magnitudeB);
    }

    /**
     * Normalize a vector to unit length (L2 norm)
     * @param vector Input vector
     * @return Normalized vector
     */
    public static float[] normalize(float[] vector) {
        float magnitude = 0;
        for (float v : vector) {
            magnitude += v * v;
        }
        magnitude = (float) Math.sqrt(magnitude);

        if (magnitude == 0) {
            return vector.clone();
        }

        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / magnitude;
        }

        return normalized;
    }

    /**
     * Calculate magnitude (L2 norm) of a vector
     * @param vector Input vector
     * @return Magnitude
     */
    public static float magnitude(float[] vector) {
        float sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        return (float) Math.sqrt(sum);
    }

    /**
     * Calculate dot product of two vectors
     * @param a First vector
     * @param b Second vector
     * @return Dot product
     */
    public static float dotProduct(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimensions");
        }

        float result = 0;
        for (int i = 0; i < a.length; i++) {
            result += a[i] * b[i];
        }

        return result;
    }

    /**
     * Calculate Euclidean distance between two vectors
     * @param a First vector
     * @param b Second vector
     * @return Euclidean distance
     */
    public static float euclideanDistance(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimensions");
        }

        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }

        return (float) Math.sqrt(sum);
    }

    /**
     * Add two vectors element-wise
     * @param a First vector
     * @param b Second vector
     * @return Sum vector
     */
    public static float[] add(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimensions");
        }

        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] + b[i];
        }

        return result;
    }

    /**
     * Subtract two vectors element-wise (a - b)
     * @param a First vector
     * @param b Second vector
     * @return Difference vector
     */
    public static float[] subtract(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimensions");
        }

        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] - b[i];
        }

        return result;
    }

    /**
     * Multiply vector by scalar
     * @param vector Input vector
     * @param scalar Scalar multiplier
     * @return Scaled vector
     */
    public static float[] scale(float[] vector, float scalar) {
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] * scalar;
        }

        return result;
    }

    /**
     * Check if a vector is normalized (magnitude close to 1)
     * @param vector Input vector
     * @param epsilon Tolerance for floating point comparison
     * @return True if normalized
     */
    public static boolean isNormalized(float[] vector, float epsilon) {
        float mag = magnitude(vector);
        return Math.abs(mag - 1.0f) < epsilon;
    }

    /**
     * Check if a vector is normalized with default epsilon (0.001)
     * @param vector Input vector
     * @return True if normalized
     */
    public static boolean isNormalized(float[] vector) {
        return isNormalized(vector, 0.001f);
    }
}
