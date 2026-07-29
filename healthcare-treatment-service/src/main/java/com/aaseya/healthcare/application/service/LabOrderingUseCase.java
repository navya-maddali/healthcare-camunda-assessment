package com.aaseya.healthcare.application.service;

import com.aaseya.camunda.framework.core.exception.BusinessException;
import com.aaseya.healthcare.domain.model.LabOrder;

import java.util.UUID;

/**
 * Places a diagnostic order for one branch of the parallel diagnostics sub-process.
 *
 * <p>Throws the framework's {@link BusinessException} when the diagnostics system is
 * unavailable. Per the framework's error-handling convention, {@code BaseWorker} translates
 * that into a BPMN error carrying the exception's error code, which the
 * {@code DIAGNOSTIC_SYSTEM_UNAVAILABLE} boundary event catches and escalates to the physician.
 * Technical faults are deliberately <em>not</em> raised this way — those stay as incidents so
 * Camunda's retry budget applies.
 */
public class LabOrderingUseCase {

    /** BPMN error code matched by the boundary event on the diagnostics sub-process. */
    public static final String DIAGNOSTIC_SYSTEM_UNAVAILABLE = "DIAGNOSTIC_SYSTEM_UNAVAILABLE";

    /**
     * Places an order for a single test.
     *
     * @param testType   the test to order
     * @param systemDown when {@code true}, simulates the diagnostics system being unreachable;
     *                   sourced from the {@code diagnosticSystemDown} process variable so the
     *                   exception path can be demonstrated without restarting the service
     * @return the placed order
     * @throws BusinessException when {@code systemDown} is {@code true}
     */
    public LabOrder placeOrder(String testType, boolean systemDown) {
        if (systemDown) {
            throw new BusinessException(DIAGNOSTIC_SYSTEM_UNAVAILABLE,
                    "Diagnostics system unavailable while ordering " + testType);
        }

        String orderId = "ORD-" + testType + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        return new LabOrder(orderId, testType, LabOrder.ORDERED);
    }
}
