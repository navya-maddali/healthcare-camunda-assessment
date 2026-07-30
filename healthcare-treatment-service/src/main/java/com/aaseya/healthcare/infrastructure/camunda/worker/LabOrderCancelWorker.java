package com.aaseya.healthcare.infrastructure.camunda.worker;

import com.aaseya.camunda.framework.core.idempotency.IdempotencyGuard;
import com.aaseya.camunda.framework.core.worker.BaseWorker;
import com.aaseya.camunda.framework.core.worker.VariableMapper;
import com.aaseya.camunda.framework.core.worker.WorkResult;
import com.aaseya.healthcare.application.LabOrderingUseCase;
import com.aaseya.healthcare.domain.LabOrder;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Withdraws a diagnostic order that was placed but never produced a result.
 *
 * <p>This is the compensation handler for {@code Task_OrderTest}. It is not reached by a sequence
 * flow — the engine invokes it when compensation is thrown for the diagnostics scope, once per
 * branch that completed its order, in reverse order of completion.
 *
 * <p>Compensation must not fail. A handler that raises an error leaves the process stuck in the
 * middle of unwinding, which is worse than the fault being compensated: the clinical flow is
 * already heading for physician escalation and must get there. So the cancellation is treated as
 * best-effort and any fault is logged rather than propagated.
 */
@Component
public class LabOrderCancelWorker extends BaseWorker<LabOrderCancelWorker.CancelVars> {

    private static final Logger log = LoggerFactory.getLogger(LabOrderCancelWorker.class);

    private final LabOrderingUseCase labOrdering;

    /**
     * @param mapper        framework variable mapper
     * @param guard         framework idempotency guard
     * @param meterRegistry Micrometer registry for {@code framework_job_*} counters
     * @param labOrdering   ordering use case, which owns the cancelling rule
     */
    public LabOrderCancelWorker(VariableMapper mapper, IdempotencyGuard guard,
                                MeterRegistry meterRegistry, LabOrderingUseCase labOrdering) {
        super(mapper, guard, meterRegistry);
        this.labOrdering = labOrdering;
    }

    /**
     * Typed input variables for the {@code lab-order-cancellation} job.
     *
     * @param orderId  the order to withdraw
     * @param testType the test it was booked for
     * @param caseId   admission business key
     */
    public record CancelVars(String orderId, String testType, String caseId) {
    }

    @Override
    protected Class<CancelVars> varsType() {
        return CancelVars.class;
    }

    @JobWorker(type = "lab-order-cancellation", autoComplete = false)
    public void handleOrderCancellation(JobClient client, ActivatedJob job) {
        execute(client, job);
    }

    @Override
    protected WorkResult doWork(CancelVars vars, ActivatedJob job) {
        if (vars.orderId() == null || vars.orderId().isBlank()) {
            // The branch was interrupted before it booked anything. Nothing to withdraw.
            log.info("Compensation skipped, no order was placed | case={} test={}",
                    vars.caseId(), vars.testType());
            return WorkResult.completed(Map.of());
        }

        try {
            LabOrder cancelled = labOrdering.cancelOrder(vars.orderId(), vars.testType());
            log.info("Compensated diagnostic order | order={} test={} status={}",
                    cancelled.orderId(), cancelled.testType(), cancelled.status());
            return WorkResult.completed(Map.of("cancelledOrderId", cancelled.orderId()));
        } catch (RuntimeException ex) {
            log.error("Order cancellation failed, continuing to escalation | order={}",
                    vars.orderId(), ex);
            return WorkResult.completed(Map.of());
        }
    }
}
