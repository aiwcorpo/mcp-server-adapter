package pl.aiwcorpo.mcp.adapter.tool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.adapter.foreign.ForeignCallException;

/**
 * Where the technical reason of a failed foreign call goes. The client gets one of three fixed answers and cannot tell a refusal from an
 * error of the system, so the operator needs the difference somewhere else: a log line (tool, reason, HTTP status, request id; never a
 * word the foreign server said) and the counter {@code mcp_adapter_foreign_failures_total} per reason, whose rise separates a wave of
 * errors from ordinary refusals.
 */
final class ForeignFailures {

    private static final Logger LOG = LoggerFactory.getLogger(ForeignFailures.class);

    private final MeterRegistry meters;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    ForeignFailures(MeterRegistry meters) {
        this.meters = meters;
    }

    void record(String tool, String requestId, ForeignCallException failure) {
        String reason = failure.reason().label();
        if (failure.kind() == ForeignCallException.Kind.UNAVAILABLE || failure.kind() == ForeignCallException.Kind.OVERSIZE) {
            LOG.warn("event=foreign_call_failed tool={} reason={} status={} requestId={}", tool, reason, failure.httpStatus(),
                    safe(requestId));
        } else {
            LOG.info("event=foreign_call_failed tool={} reason={} status={} requestId={}", tool, reason, failure.httpStatus(),
                    safe(requestId));
        }
        if (meters != null) {
            counters.computeIfAbsent(reason, label -> Counter.builder("mcp_adapter_foreign_failures_total")
                    .description("Failed calls to the foreign server by technical reason").tag("reason", label).register(meters))
                    .increment();
        }
    }

    private static String safe(String requestId) {
        return requestId == null ? "-" : requestId.replaceAll("[^A-Za-z0-9._-]", "?");
    }
}
