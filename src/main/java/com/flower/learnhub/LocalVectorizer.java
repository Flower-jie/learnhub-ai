package com.flower.learnhub;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LocalVectorizer {
    public static final int DIMENSION = 2048;
    private static final Pattern TERMS = Pattern.compile("\\p{IsHan}+|[\\p{IsLatin}0-9]+");

    public List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        var matcher = TERMS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            int[] codepoints = token.codePoints().toArray();
            if (Character.UnicodeScript.of(codepoints[0]) == Character.UnicodeScript.HAN && codepoints.length > 1) {
                for (int i = 0; i < codepoints.length - 1; i++) terms.add(new String(codepoints, i, 2));
            } else {
                terms.add(token);
            }
        }
        return terms;
    }

    public double[] vector(String text) {
        double[] vector = new double[DIMENSION];
        for (String term : terms(text)) vector[Math.floorMod(term.hashCode(), DIMENSION)] += 1.0;
        return normalize(vector);
    }

    public boolean hasSharedTerm(String left, String right) {
        var leftTerms = new HashSet<>(terms(left));
        return terms(right).stream().anyMatch(leftTerms::contains);
    }

    public static double[] normalize(double[] input) {
        double sum = 0;
        for (double value : input) {
            if (!Double.isFinite(value)) throw ApiException.upstream();
            sum += value * value;
        }
        if (!Double.isFinite(sum)) throw ApiException.upstream();
        double norm = Math.sqrt(sum);
        double[] result = input.clone();
        if (norm > 0) for (int i = 0; i < result.length; i++) result[i] /= norm;
        return result;
    }

    public static double cosine(double[] left, double[] right) {
        if (left.length != right.length) throw ApiException.profileMismatch();
        double dot = 0;
        for (int i = 0; i < left.length; i++) dot += left[i] * right[i];
        return Math.max(-1.0, Math.min(1.0, dot));
    }
}
