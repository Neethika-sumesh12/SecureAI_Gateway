package com.secureai.model;

import java.util.List;
import java.util.Map;

/**
 * Telemetry for the tool-output compression stage. Token counts are estimates (chars / 4), not tokenizer counts.
 *
 * decision:  COMPRESSED | SKIPPED_SMALL | FALLBACK_RAW
 * validated: true if the gateway enforced the preservation contract on this request. When false the contract
 *            results in {@code checks} were measured for reporting only and did not influence the outcome.
 * Needle counts describe the context that actually reached the model.
 * validationTimeMs is the cost of an ENFORCED contract check (0 when not enforced). shadowCheckTimeMs is the cost of a
 * check that was evaluated for reporting only; a production deployment that skips validation would not pay it.
 */
public record CompressionReport(
        String decision,
        String strategy,
        boolean validated,
        int rawTokens,
        int compressedTokens,
        double savingsPercent,
        Map<String, Boolean> checks,
        List<String> failures,
        int signaturesTotal,
        int signaturesPreserved,
        int needlesTotal,
        int needlesPreserved,
        String originalId,
        int injectionsQuarantined,
        int secretsRedacted,
        double compressionTimeMs,
        double validationTimeMs,
        double shadowCheckTimeMs,
        String contextPreview,
        String modelAnswer,
        String groundTruthAnswer,
        Boolean answerCorrect
) {
    public CompressionReport withAnswer(String modelAnswer, String groundTruthAnswer) {
        return new CompressionReport(decision, strategy, validated, rawTokens, compressedTokens, savingsPercent,
                checks, failures, signaturesTotal, signaturesPreserved, needlesTotal, needlesPreserved, originalId,
                injectionsQuarantined, secretsRedacted, compressionTimeMs, validationTimeMs, shadowCheckTimeMs, contextPreview,
                modelAnswer, groundTruthAnswer, modelAnswer.equals(groundTruthAnswer));
    }
}
