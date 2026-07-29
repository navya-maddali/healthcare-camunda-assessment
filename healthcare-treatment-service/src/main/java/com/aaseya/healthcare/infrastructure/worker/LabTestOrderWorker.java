package com.aaseya.healthcare.infrastructure.worker;

import com.aaseya.camunda.framework.core.idempotency.IdempotencyGuard;
import com.aaseya.camunda.framework.core.worker.BaseWorker;
import com.aaseya.camunda.framework.core.worker.VariableMapper;
import com.aaseya.camunda.framework.core.worker.WorkResult;
import com.aaseya.healthcare.application.service.LabOrderingUseCase;
import com.aaseya.healthcare.domain.model.LabOrder;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Places one diagnostic order per branch of the parallel diagnostics sub-process.
 *
 * <p>Holds no business rules — ordering and the unavailability decision live in
 * {@link LabOrderingUseCase}. Idempotency is keyed on {@code caseId + testType} (mapped into
 * {@code businessKey} by the BPMN), so a replayed branch does not raise a duplicate order.
 */
@Component
public class LabTestOrderWorker extends BaseWorker<LabTestOrderWorker.OrderVars> {

    private static final Logger log = LoggerFactory.getLogger(LabTestOrderWorker.class);

    private final LabOrderingUseCase labOrdering;

    /**
     * @param mapper        framework variable mapper
     * @param guard         framework idempotency guard
     * @param meterRegistry Micrometer registry for {@code framework_job_*} counters
     * @param labOrdering   ordering use case
     */
    public LabTestOrderWorker(VariableMapper mapper, IdempotencyGuard guard,
                              MeterRegistry meterRegistry, LabOrderingUseCase labOrdering) {
        super(mapper, guard, meterRegistry);
        this.labOrdering = labOrdering;
    }

    /**
     * Typed input variables for the {@code lab-test-ordering} job.
     *
     * @param patientId             patient the test is for
     * @param testType              the test to order
     * @param caseId                admission business key
     * @param diagnosticSystemDown  simulates the diagnostics system being unreachable; absent
     *                              on the happy path, so boxed to tolerate {@code null}
     */
    public record OrderVars(String patientId, String testType, String caseId,
                            Boolean diagnosticSystemDown) {
    }

    @Override
    protected Class<OrderVars> varsType() {
        return OrderVars.class;
    }

    @JobWorker(type = "lab-test-ordering", autoComplete = false)
    public void handleLabOrder(JobClient client, ActivatedJob job) {
        execute(client, job);
    }

    @Override
    protected WorkResult doWork(OrderVars vars, ActivatedJob job) {
        log.info("Lab order received | patient={} test={}", vars.patientId(), vars.testType());

        boolean systemDown = Boolean.TRUE.equals(vars.diagnosticSystemDown());
        LabOrder order = labOrdering.placeOrder(vars.testType(), systemDown);

        log.info("Lab order placed | orderId={}", order.orderId());
        return WorkResult.completed(Map.of(
                "orderId", order.orderId(),
                "testStatus", order.status()));
    }
}
