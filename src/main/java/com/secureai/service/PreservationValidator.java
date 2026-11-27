package com.secureai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tool preservation contract for log output. A compression is accepted only if:
 *  1. FORMAT_RECOGNIZED   - the raw output is in a format the contract understands (otherwise it cannot be verified)
 *  2. LEVEL_COUNTS        - per-level entry counts match the original
 *  3. SIGNATURES_PRESENT  - every distinct WARN/ERROR signature in the original is present
 *  4. SIGNATURE_COUNTS    - each of those signatures keeps its exact occurrence count
 *  5. TIME_RANGE          - first and last timestamps match the original
 *
 * "Needles" are the rare signatures (seen at most {@value #RARE_THRESHOLD} times): the ones a lossy compressor
 * is most likely to merge into a bulk cluster.
 */
@Service
public class PreservationValidator {

    public static final int RARE_THRESHOLD = 3;

    public record Result(
            boolean passed,
            Map<String, Boolean> checks,
            List<String> failures,
            int signaturesTotal,
            int signaturesPreserved,
            int needlesTotal,
            int needlesPreserved,
            List<String> lostSignatures
    ) {}

    public Result evaluate(String raw, String compressed) {
        LogLines.Stats before = LogLines.stats(raw);
        LogLines.Stats after = LogLines.stats(compressed);

        Map<String, Boolean> checks = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        boolean recognized = LogLines.detect(raw) != LogLines.Format.UNKNOWN;
        checks.put("FORMAT_RECOGNIZED", recognized);
        if (!recognized) {
            failures.add("Format not recognized; the preservation contract cannot be verified");
        }

        boolean levels = before.levelCounts.equals(after.levelCounts);
        checks.put("LEVEL_COUNTS", levels);
        if (!levels) {
            failures.add("Per-level counts differ: original " + before.levelCounts + " vs compressed " + after.levelCounts);
        }

        int total = before.signatureCounts.size();
        int preserved = 0;
        int needlesTotal = 0;
        int needlesPreserved = 0;
        boolean countsMatch = true;
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, Integer> e : before.signatureCounts.entrySet()) {
            Integer afterCount = after.signatureCounts.get(e.getKey());
            boolean present = afterCount != null;
            boolean rare = e.getValue() <= RARE_THRESHOLD;
            if (present) {
                preserved++;
                if (!afterCount.equals(e.getValue())) {
                    countsMatch = false;
                }
            } else {
                lost.add(e.getKey());
            }
            if (rare) {
                needlesTotal++;
                if (present) {
                    needlesPreserved++;
                }
            }
        }
        checks.put("SIGNATURES_PRESENT", preserved == total);
        if (preserved != total) {
            failures.add((total - preserved) + " of " + total + " distinct WARN/ERROR signatures are missing");
        }
        checks.put("SIGNATURE_COUNTS", preserved == total && countsMatch);
        if (preserved == total && !countsMatch) {
            failures.add("Occurrence counts of WARN/ERROR signatures differ from the original");
        }

        boolean time = java.util.Objects.equals(before.minTs, after.minTs) && java.util.Objects.equals(before.maxTs, after.maxTs);
        checks.put("TIME_RANGE", time);
        if (!time) {
            failures.add("Time range differs: original " + before.minTs + ".." + before.maxTs
                    + " vs compressed " + after.minTs + ".." + after.maxTs);
        }

        boolean passed = checks.values().stream().allMatch(Boolean::booleanValue);
        return new Result(passed, checks, failures, total, preserved, needlesTotal, needlesPreserved, lost);
    }
}
