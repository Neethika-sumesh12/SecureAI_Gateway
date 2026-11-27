package com.secureai.service;

import com.secureai.model.CompressionReport;
import com.secureai.model.GatewayMode;
import com.secureai.model.GatewayRequest;
import com.secureai.model.GatewayResponse;
import com.secureai.model.RiskLevel;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class GatewayOrchestratorService {

    private static final int RECALL_DISPLAY_LINES = 40;

    private final SecurityInspectionService inspectionService;
    private final MockEnterpriseToolsService toolsService;
    private final LlmReasoningService llmService;
    private final CompressionStageService compressionStage;
    private final OriginalStore originalStore;

    public GatewayOrchestratorService(
            SecurityInspectionService inspectionService,
            MockEnterpriseToolsService toolsService,
            LlmReasoningService llmService,
            CompressionStageService compressionStage,
            OriginalStore originalStore
    ) {
        this.inspectionService = inspectionService;
        this.toolsService = toolsService;
        this.llmService = llmService;
        this.compressionStage = compressionStage;
        this.originalStore = originalStore;
    }

    public GatewayResponse handleRequest(GatewayRequest request) {
        long totalStart = System.currentTimeMillis();
        GatewayResponse response = new GatewayResponse();
        GatewayMode mode = request.getMode() != null ? request.getMode() : GatewayMode.BALANCED;
        String prompt = request.getPrompt();

        response.addAuditLog("Received request under mode [" + mode + "] from user [" + request.getUsername() + " (" + request.getRole() + ")]");

        // ----------------------------------------------------
        // MODE A: FAST AI (Minimal / Pass-through)
        // ----------------------------------------------------
        if (mode == GatewayMode.FAST) {
            response.setGatewayInspectionTimeMs(1);
            response.setRiskLevel(RiskLevel.LOW);
            response.setRiskScore(0);
            response.setDetectedThreat("None (Inspections Bypassed)");
            response.setDecision("ALLOWED (FAST PASS-THROUGH)");
            response.setStatusMessage("Bypassed gateway security controls for lowest latency.");

            // Directly invoke model
            LlmReasoningService.ModelOutput modelOutput = llmService.process(prompt, true);
            response.setModelInferenceTimeMs(modelOutput.inferenceTimeMs());

            // If model proposes a tool, Fast mode executes it blindly without RBAC
            long fastOutStart = System.currentTimeMillis();
            if (modelOutput.suggestedTool() != null) {
                response.setToolExecuted(true);
                response.setExecutedToolName(modelOutput.suggestedTool());
                MockEnterpriseToolsService.ToolExecutionResult toolRes = executeTool(
                        modelOutput.suggestedTool(),
                        modelOutput.toolParam(),
                        request.getUsername(),
                        request.getRole(),
                        false,
                        true, // bypass authorization check!
                        request.getWorkload(),
                        mode
                );
                if ("getServiceLogs".equals(modelOutput.suggestedTool()) && toolRes.success()) {
                    String shown = applyLogCompression(response, mode, request, prompt, toolRes.output(), true);
                    response.setFinalResponse(modelOutput.content() + "\n\n" + shown);
                } else {
                    response.setToolResult(toolRes.output() != null ? toolRes.output() : toolRes.accessViolation());
                    response.setFinalResponse(modelOutput.content() + "\n\n[Tool Output]:\n" + displayTool(response.getToolResult(), modelOutput.suggestedTool()));
                }
            } else {
                response.setFinalResponse(modelOutput.content());
            }

            response.setOutputValidationTimeMs(response.getCompression() != null ? System.currentTimeMillis() - fastOutStart : 1);
            response.setTotalResponseTimeMs(System.currentTimeMillis() - totalStart);
            return response;
        }

        // ----------------------------------------------------
        // MODE B: STRICT SECURE & MODE C: BALANCED (Adaptive)
        // ----------------------------------------------------
        long inspStart = System.currentTimeMillis();
        
        // In Strict mode, simulate deep synchronous rule evaluation & heuristic scans (+80-120ms)
        if (mode == GatewayMode.STRICT) {
            try {
                Thread.sleep(85);
            } catch (InterruptedException ignored) {}
        } else {
            // Balanced mode: lightweight micro-scan (+5-10ms)
            try {
                Thread.sleep(8);
            } catch (InterruptedException ignored) {}
        }

        SecurityInspectionService.RiskAssessment assessment = inspectionService.inspect(
                prompt,
                request.getRole(),
                request.isSimulatedApproval()
        );

        long inspTime = System.currentTimeMillis() - inspStart;
        response.setGatewayInspectionTimeMs(inspTime);
        response.setRiskLevel(assessment.riskLevel());
        response.setRiskScore(assessment.riskScore());
        response.setDetectedThreat(assessment.detectedThreat());
        response.setSanitizedPrompt(assessment.sanitizedPrompt());

        response.addAuditLog("Security Inspection completed: Risk=" + assessment.riskLevel() + " (" + assessment.riskScore() + "/100), Threat=" + assessment.detectedThreat());

        // Decision Gate: Prompt Injection
        if (assessment.isPromptInjection()) {
            response.setDecision("BLOCKED (PROMPT INJECTION)");
            response.setStatusMessage("Request dropped by Gateway Policy: Prompt injection signature detected.");
            response.setFinalResponse("⛔ [GATEWAY BLOCKED]: Your prompt contains unauthorized instruction overrides (OWASP LLM01).");
            response.setModelInferenceTimeMs(0);
            response.setOutputValidationTimeMs(2);
            response.setTotalResponseTimeMs(System.currentTimeMillis() - totalStart);
            response.addAuditLog("Policy Action: Dropped request before LLM inference.");
            return response;
        }

        // Decision Gate: High-impact Action requiring Human-In-The-Loop approval
        if (assessment.requiresApproval() && !request.isSimulatedApproval() && !"ROLE_ADMIN".equals(request.getRole())) {
            response.setDecision("APPROVAL_REQUIRED (HITL)");
            response.setStatusMessage("High-impact action detected. Human-in-the-Loop approval is required before execution.");
            response.setFinalResponse("⚠️ [GATEWAY GATE]: Action requires explicit Administrator / Manager approval to execute dangerous operations (OWASP LLM06 Excessive Agency).");
            response.setModelInferenceTimeMs(0);
            response.setOutputValidationTimeMs(2);
            response.setTotalResponseTimeMs(System.currentTimeMillis() - totalStart);
            response.addAuditLog("Policy Action: Suspended execution awaiting Human-in-the-Loop confirmation.");
            return response;
        }

        // Sanitization decision
        if (assessment.containsSecrets()) {
            response.setDecision("SANITIZED");
            response.setStatusMessage("Sensitive credentials/connection strings were detected and masked prior to LLM forwarding.");
            response.addAuditLog("Policy Action: Masked sensitive tokens to prevent external data leakage.");
        } else {
            response.setDecision("ALLOWED");
            response.setStatusMessage("Request passed all gateway security verification checks.");
        }

        // Forward sanitized prompt to LLM
        LlmReasoningService.ModelOutput modelOutput = llmService.process(assessment.sanitizedPrompt(), false);
        response.setModelInferenceTimeMs(modelOutput.inferenceTimeMs());

        // Output validation / Tool Execution Layer
        long outStart = System.currentTimeMillis();
        if (modelOutput.suggestedTool() != null) {
            response.setToolExecuted(true);
            response.setExecutedToolName(modelOutput.suggestedTool());

            MockEnterpriseToolsService.ToolExecutionResult toolRes = executeTool(
                    modelOutput.suggestedTool(),
                    modelOutput.toolParam(),
                    request.getUsername(),
                    request.getRole(),
                    request.isSimulatedApproval(),
                    false, // strictly enforce authorization!
                    request.getWorkload(),
                    mode
            );

            if (toolRes.success() && "getServiceLogs".equals(modelOutput.suggestedTool())) {
                String shown = applyLogCompression(response, mode, request, assessment.sanitizedPrompt(), toolRes.output(), false);
                response.setFinalResponse(modelOutput.content() + "\n\n" + shown);
                response.addAuditLog("Tool [" + modelOutput.suggestedTool() + "] executed successfully with authorized context.");
            } else if (toolRes.success()) {
                response.setToolResult(toolRes.output());
                response.setFinalResponse(modelOutput.content() + "\n\n[Tool Output]:\n" + displayTool(toolRes.output(), modelOutput.suggestedTool()));
                response.addAuditLog("Tool [" + modelOutput.suggestedTool() + "] executed successfully with authorized context.");
            } else {
                response.setToolResult(toolRes.accessViolation());
                response.setFinalResponse(modelOutput.content() + "\n\n[Gateway Policy Violation]:\n" + toolRes.accessViolation());
                response.addAuditLog("Tool execution blocked by downstream authorization: " + toolRes.accessViolation());
            }
        } else {
            response.setFinalResponse(modelOutput.content());
        }

        // In Strict mode, synchronous post-processing & audit serialization delay (+25ms)
        if (mode == GatewayMode.STRICT) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException ignored) {}
        }

        response.setOutputValidationTimeMs(System.currentTimeMillis() - outStart);
        response.setTotalResponseTimeMs(System.currentTimeMillis() - totalStart);
        return response;
    }

    /**
     * Compression stage for log tool output: tool result -> inspect -> reduce -> validate -> fall back -> LLM.
     * The context (compressed or raw) goes back to the mock model, which answers the user's question from it.
     * Returns the text to show the user.
     */
    private String applyLogCompression(GatewayResponse response, GatewayMode mode, GatewayRequest request,
                                       String question, String rawOutput, boolean unprotected) {
        CompressionStageService.StageResult stage =
                compressionStage.process(mode, request.getUsername(), "getServiceLogs", rawOutput);

        LlmReasoningService.ContextAnswer answer = llmService.answerFromContext(question, stage.context(), unprotected);
        String truth = llmService.computeAnswer(question, stage.referenceContext());
        CompressionReport report = stage.report().withAnswer(answer.answer(), truth);

        response.setCompression(report);
        response.setToolResult(stage.context());
        response.setModelInferenceTimeMs(response.getModelInferenceTimeMs() + answer.inferenceTimeMs());
        stage.audit().forEach(response::addAuditLog);
        response.addAuditLog("Compression decision=" + report.decision() + ", tokens " + report.rawTokens() + " -> "
                + report.compressedTokens() + " (" + report.savingsPercent() + "% saved), needles "
                + report.needlesPreserved() + "/" + report.needlesTotal() + " preserved in model context.");
        response.addAuditLog("Model answer " + (report.answerCorrect() ? "matches" : "DIFFERS from") + " ground truth from the raw log.");

        return "[Tool Output -> model context | " + report.decision() + ", " + report.rawTokens() + " -> "
                + report.compressedTokens() + " tokens (est.)]:\n" + report.contextPreview()
                + "\n\n[Model Answer]: " + report.modelAnswer();
    }

    private static String displayTool(String text, String toolName) {
        if (text == null || !"recallOriginal".equals(toolName)) {
            return text;
        }
        String[] lines = text.split("\\R");
        if (lines.length <= RECALL_DISPLAY_LINES) {
            return text;
        }
        return String.join("\n", Arrays.copyOfRange(lines, 0, RECALL_DISPLAY_LINES))
                + "\n... (" + (lines.length - RECALL_DISPLAY_LINES) + " more lines; full text in toolResult)";
    }

    private MockEnterpriseToolsService.ToolExecutionResult executeTool(
            String toolName,
            String param,
            String username,
            String role,
            boolean approval,
            boolean bypassAuth,
            String workload,
            GatewayMode mode
    ) {
        if ("getServiceLogs".equals(toolName)) {
            return toolsService.getServiceLogs(param, workload);
        } else if ("recallOriginal".equals(toolName)) {
            OriginalStore.RecallResult rr = originalStore.recall(param, username, role, bypassAuth);
            if (!rr.found() || !rr.allowed()) {
                return new MockEnterpriseToolsService.ToolExecutionResult(false, toolName, null, rr.message());
            }
            // Recalled text is redacted again (defense in depth) in every mode that inspects output.
            String out = mode == GatewayMode.FAST ? rr.content() : inspectionService.redactSecrets(rr.content());
            return new MockEnterpriseToolsService.ToolExecutionResult(true, toolName, out, null);
        } else if ("fetchInvoice".equals(toolName)) {
            return toolsService.fetchInvoice(param, username, role, bypassAuth);
        } else if ("restartServer".equals(toolName)) {
            return toolsService.restartServer(param, role, approval, bypassAuth);
        }
        return new MockEnterpriseToolsService.ToolExecutionResult(false, toolName, "Unknown tool: " + toolName, null);
    }
}
