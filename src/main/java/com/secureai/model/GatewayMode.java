package com.secureai.model;

public enum GatewayMode {
    FAST,      // Mode A: Direct Pass-Through, minimal checks, lowest latency, high risk
    STRICT,    // Mode B: Deep sync checks on every request, 100% security, high latency penalty
    BALANCED   // Mode C: Risk-Adaptive routing, fast-path for benign, stepped-up check for risky
}
