package com.sherlock.ui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OpinionDto {

    @JsonProperty("case_id")
    private String caseId;

    @JsonProperty("preliminary_analysis")
    private String preliminaryAnalysis;

    @JsonProperty("possible_causes")
    private List<PossibleCauseDto> possibleCauses = new ArrayList<>();

    @JsonProperty("self_debate_summary")
    private String selfDebateSummary;

    @JsonProperty("executive_summary")
    private String executiveSummary;

    @JsonProperty("primary_hypothesis")
    private String primaryHypothesis;

    private Double confidence = 0.85;

    @JsonProperty("confidence_explanation")
    private String confidenceExplanation;

    @JsonProperty("supporting_evidence")
    private List<EvidencePointDto> supportingEvidence = new ArrayList<>();

    @JsonProperty("flaws_and_counter_evidence")
    private List<FlawPointDto> flawsAndCounterEvidence = new ArrayList<>();

    @JsonProperty("alternative_hypotheses")
    private List<AlternativeHypothesisDto> alternativeHypotheses = new ArrayList<>();

    @JsonProperty("investigative_leads")
    private List<InvestigativeLeadDto> investigativeLeads = new ArrayList<>();

    @JsonProperty("reasoning_trace")
    private String reasoningTrace;

    @JsonProperty("generated_at")
    private String generatedAt;

    public OpinionDto() {}

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public String getPreliminaryAnalysis() { return preliminaryAnalysis; }
    public void setPreliminaryAnalysis(String preliminaryAnalysis) { this.preliminaryAnalysis = preliminaryAnalysis; }

    public List<PossibleCauseDto> getPossibleCauses() { return possibleCauses; }
    public void setPossibleCauses(List<PossibleCauseDto> possibleCauses) { this.possibleCauses = possibleCauses; }

    public String getSelfDebateSummary() { return selfDebateSummary; }
    public void setSelfDebateSummary(String selfDebateSummary) { this.selfDebateSummary = selfDebateSummary; }

    public String getExecutiveSummary() { return executiveSummary; }
    public void setExecutiveSummary(String executiveSummary) { this.executiveSummary = executiveSummary; }

    public String getPrimaryHypothesis() { return primaryHypothesis; }
    public void setPrimaryHypothesis(String primaryHypothesis) { this.primaryHypothesis = primaryHypothesis; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getConfidenceExplanation() { return confidenceExplanation; }
    public void setConfidenceExplanation(String confidenceExplanation) { this.confidenceExplanation = confidenceExplanation; }

    public List<EvidencePointDto> getSupportingEvidence() { return supportingEvidence; }
    public void setSupportingEvidence(List<EvidencePointDto> supportingEvidence) { this.supportingEvidence = supportingEvidence; }

    public List<FlawPointDto> getFlawsAndCounterEvidence() { return flawsAndCounterEvidence; }
    public void setFlawsAndCounterEvidence(List<FlawPointDto> flawsAndCounterEvidence) { this.flawsAndCounterEvidence = flawsAndCounterEvidence; }

    public List<AlternativeHypothesisDto> getAlternativeHypotheses() { return alternativeHypotheses; }
    public void setAlternativeHypotheses(List<AlternativeHypothesisDto> alternativeHypotheses) { this.alternativeHypotheses = alternativeHypotheses; }

    public List<InvestigativeLeadDto> getInvestigativeLeads() { return investigativeLeads; }
    public void setInvestigativeLeads(List<InvestigativeLeadDto> investigativeLeads) { this.investigativeLeads = investigativeLeads; }

    public String getReasoningTrace() { return reasoningTrace; }
    public void setReasoningTrace(String reasoningTrace) { this.reasoningTrace = reasoningTrace; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PossibleCauseDto {
        private String cause;
        private String category;

        @JsonProperty("evidence_indicators")
        private List<String> evidenceIndicators = new ArrayList<>();

        private String significance;

        public PossibleCauseDto() {}

        public String getCause() { return cause; }
        public void setCause(String cause) { this.cause = cause; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public List<String> getEvidenceIndicators() { return evidenceIndicators; }
        public void setEvidenceIndicators(List<String> evidenceIndicators) { this.evidenceIndicators = evidenceIndicators; }

        public String getSignificance() { return significance; }
        public void setSignificance(String significance) { this.significance = significance; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvidencePointDto {
        private String claim;

        @JsonProperty("source_file")
        private String sourceFile;

        @JsonProperty("chunk_id")
        private String chunkId;

        private String quote;
        private String relevance;

        @JsonProperty("entities_involved")
        private List<String> entitiesInvolved = new ArrayList<>();

        public EvidencePointDto() {}

        public String getClaim() { return claim; }
        public void setClaim(String claim) { this.claim = claim; }

        public String getSourceFile() { return sourceFile; }
        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }

        public String getQuote() { return quote; }
        public void setQuote(String quote) { this.quote = quote; }

        public String getRelevance() { return relevance; }
        public void setRelevance(String relevance) { this.relevance = relevance; }

        public List<String> getEntitiesInvolved() { return entitiesInvolved; }
        public void setEntitiesInvolved(List<String> entitiesInvolved) { this.entitiesInvolved = entitiesInvolved; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FlawPointDto {
        private String point;
        private String type;

        @JsonProperty("source_file")
        private String sourceFile;

        @JsonProperty("chunk_id")
        private String chunkId;

        private String quote;
        private String impact;

        @JsonProperty("entities_involved")
        private List<String> entitiesInvolved = new ArrayList<>();

        public FlawPointDto() {}

        public String getPoint() { return point; }
        public void setPoint(String point) { this.point = point; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getSourceFile() { return sourceFile; }
        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }

        public String getQuote() { return quote; }
        public void setQuote(String quote) { this.quote = quote; }

        public String getImpact() { return impact; }
        public void setImpact(String impact) { this.impact = impact; }

        public List<String> getEntitiesInvolved() { return entitiesInvolved; }
        public void setEntitiesInvolved(List<String> entitiesInvolved) { this.entitiesInvolved = entitiesInvolved; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlternativeHypothesisDto {
        private String title;
        private String description;

        @JsonProperty("supporting_points")
        private List<String> supportingPoints = new ArrayList<>();

        @JsonProperty("counter_points")
        private List<String> counterPoints = new ArrayList<>();

        public AlternativeHypothesisDto() {}

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public List<String> getSupportingPoints() { return supportingPoints; }
        public void setSupportingPoints(List<String> supportingPoints) { this.supportingPoints = supportingPoints; }

        public List<String> getCounterPoints() { return counterPoints; }
        public void setCounterPoints(List<String> counterPoints) { this.counterPoints = counterPoints; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InvestigativeLeadDto {
        private String lead;
        private String priority;
        private String action;
        private String rationale;

        public InvestigativeLeadDto() {}

        public String getLead() { return lead; }
        public void setLead(String lead) { this.lead = lead; }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }

        public String getRationale() { return rationale; }
        public void setRationale(String rationale) { this.rationale = rationale; }
    }
}
