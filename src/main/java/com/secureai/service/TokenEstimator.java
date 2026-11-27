package com.secureai.service;

/** Rough token estimate (chars / 4). An approximation, not a real tokenizer count. */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }
}
