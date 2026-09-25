package com.jobassistant.rag;

public final class VectorMath {

    private VectorMath() {
    }

    public static float[] normalise(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        if (norm == 0) return out;
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    public static double dot(float[] a, float[] b) {
        double sum = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) sum += (double) a[i] * b[i];
        return sum;
    }

    public static double cosine(float[] a, float[] b) {
        return dot(normalise(a), normalise(b));
    }
}
