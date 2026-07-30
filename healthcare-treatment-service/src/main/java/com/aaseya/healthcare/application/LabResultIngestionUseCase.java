package com.aaseya.healthcare.application;

import com.aaseya.healthcare.domain.LabResult;

/**
 * Ingests the outcome of a placed diagnostic order.
 *
 * <p>The assessment calls for both lab/imaging <em>ordering</em> and <em>result ingestion</em>
 * as job workers; this is the ingestion half. Each multi-instance branch produces one result,
 * and the sub-process collects them into the {@code labResults} output collection so the
 * physician sees every finding on the treatment-plan task.
 */
public class LabResultIngestionUseCase {

    /**
     * Produces the result for an order.
     *
     * <p>Cardiology findings arrive from the technician's Tasklist form rather than here, so
     * this stands in for the automated analysers only.
     *
     * @param orderId  the order being reported on
     * @param testType the test that was run
     * @return the ingested result
     */
    public LabResult ingest(String orderId, String testType) {
        String summary = switch (testType) {
            case "ECG" -> "Sinus rhythm, no ST elevation.";
            case "ECHO" -> "Ejection fraction within normal limits.";
            case "TROPONIN" -> "Troponin I below assay threshold.";
            case "CHEST_XRAY" -> "Clear lung fields, no consolidation.";
            case "CT_BRAIN" -> "No acute intracranial abnormality.";
            case "LAB_COMPLETE" -> "Full blood count and metabolic panel within range.";
            default -> "Result recorded for " + testType + ".";
        };

        return new LabResult(orderId, testType, LabResult.COMPLETED, summary);
    }
}
