package com.aaseya.healthcare.application;

import com.aaseya.camunda.framework.core.exception.BusinessException;
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
     * @param orderId      the order being reported on
     * @param testType     the test that was run
     * @param analyserDown when {@code true}, simulates the analyser failing <em>after</em> the
     *                     order was accepted. This is the failure that makes compensation
     *                     meaningful: unlike {@code diagnosticSystemDown}, which fails at ordering
     *                     and leaves nothing booked, here a slot is already reserved and has to be
     *                     released. Raises the same BPMN error, so the existing boundary event on
     *                     the diagnostics sub-process catches it unchanged.
     * @return the ingested result
     * @throws BusinessException when {@code analyserDown} is {@code true}
     */
    public LabResult ingest(String orderId, String testType, boolean analyserDown) {
        if (analyserDown) {
            throw new BusinessException(LabOrderingUseCase.DIAGNOSTIC_SYSTEM_UNAVAILABLE,
                    "Analyser unavailable while reporting " + testType + " for order " + orderId);
        }

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
