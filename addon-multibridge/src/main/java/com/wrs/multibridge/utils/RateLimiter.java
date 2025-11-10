package com.wrs.multibridge.utils;

public class RateLimiter {

    private final int maxTokens;
    private final long refillIntervalMs;
    private double tokens;
    private long lastRefill;

    public RateLimiter(int maxTokens, long refillIntervalMs) {
        this.maxTokens = Math.max(1, maxTokens);
        this.refillIntervalMs = Math.max(1L, refillIntervalMs);
        this.tokens = this.maxTokens;
        this.lastRefill = System.currentTimeMillis();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0D) {
            tokens -= 1.0D;
            return true;
        }
        return false;
    }

    public synchronized void reset() {
        tokens = maxTokens;
        lastRefill = System.currentTimeMillis();
    }

    private void refill() {
        long now = System.currentTimeMillis();
        if (now <= lastRefill) {
            return;
        }
        double tokensToAdd = ((double) (now - lastRefill) / (double) refillIntervalMs) * maxTokens;
        if (tokensToAdd > 0) {
            tokens = Math.min(maxTokens, tokens + tokensToAdd);
            lastRefill = now;
        }
    }
}
