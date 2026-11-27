package com.secureai.model;

import java.util.ArrayList;
import java.util.List;

public class GatewayResponse {
    private String decision; // ALLOWED, SANITIZED, BLOCKED, APPROVAL_REQUIRED
    private String statusMessage;
    private String finalResponse;
    private RiskLevel riskLevel;
    private int riskScore; // 0 - 100
    private String detectedThreat;
    private String sanitizedPrompt;
    private boolean toolExecuted;
    private String executedToolName;
    private String toolResult;
    private CompressionReport compression; // only set for log tool output

    // Latency Telemetry (in milliseconds)
    private long gatewayInspectionTimeMs;
    private long modelInferenceTimeMs;
    private long outputValidationTimeMs;
    private long totalResponseTimeMs;

    private List<String> auditLogs = new ArrayList<>();

    public GatewayResponse() {}

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public String getFinalResponse() { return finalResponse; }
    public void setFinalResponse(String finalResponse) { this.finalResponse = finalResponse; }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }

    public String getDetectedThreat() { return detectedThreat; }
    public void setDetectedThreat(String detectedThreat) { this.detectedThreat = detectedThreat; }

    public String getSanitizedPrompt() { return sanitizedPrompt; }
    public void setSanitizedPrompt(String sanitizedPrompt) { this.sanitizedPrompt = sanitizedPrompt; }

    public boolean isToolExecuted() { return toolExecuted; }
    public void setToolExecuted(boolean toolExecuted) { this.toolExecuted = toolExecuted; }

    public String getExecutedToolName() { return executedToolName; }
    public void setExecutedToolName(String executedToolName) { this.executedToolName = executedToolName; }

    public String getToolResult() { return toolResult; }
    public void setToolResult(String toolResult) { this.toolResult = toolResult; }

    public CompressionReport getCompression() { return compression; }
    public void setCompression(CompressionReport compression) { this.compression = compression; }

    public long getGatewayInspectionTimeMs() { return gatewayInspectionTimeMs; }
    public void setGatewayInspectionTimeMs(long gatewayInspectionTimeMs) { this.gatewayInspectionTimeMs = gatewayInspectionTimeMs; }

    public long getModelInferenceTimeMs() { return modelInferenceTimeMs; }
    public void setModelInferenceTimeMs(long modelInferenceTimeMs) { this.modelInferenceTimeMs = modelInferenceTimeMs; }

    public long getOutputValidationTimeMs() { return outputValidationTimeMs; }
    public void setOutputValidationTimeMs(long outputValidationTimeMs) { this.outputValidationTimeMs = outputValidationTimeMs; }

    public long getTotalResponseTimeMs() { return totalResponseTimeMs; }
    public void setTotalResponseTimeMs(long totalResponseTimeMs) { this.totalResponseTimeMs = totalResponseTimeMs; }

    public List<String> getAuditLogs() { return auditLogs; }
    public void setAuditLogs(List<String> auditLogs) { this.auditLogs = auditLogs; }

    public void addAuditLog(String log) { this.auditLogs.add(log); }
}
