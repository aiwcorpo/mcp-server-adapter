package pl.aiwcorpo.mcp.adapter.tool;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.platform.observability.ToolCallInterceptor;

/**
 * The local decision of the adapter on the classification of the administrator (CC-227, CC-226). The gateway sends an adapter only
 * {@code READ_ONLY}; anything else means a gateway that does not match this build, and a write the adapter has no resource scope to judge.
 * It is refused here, on top of the log and the counter the starter writes for it, so that the call never reaches the foreign server.
 *
 * <p>The classification may differ from what the foreign server's hints suggest (the administrator called a tool read that the server
 * marks as a write). That is allowed, since the administrator decides, but it is logged at WARN and counted
 * ({@code mcp_adapter_classification_divergence_total}) so that a reviewer sees where the two disagree (recommendation W8).
 */
final class ClassificationInterceptor implements ToolCallInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(ClassificationInterceptor.class);
    private static final String READ_ONLY = "READ_ONLY";

    private final MeterRegistry meters;

    ClassificationInterceptor(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public String intercept(Invocation invocation, Next next) {
        String classified = invocation.caller().mcpRisk();
        if (classified == null || !READ_ONLY.equals(classified)) {
            LOG.warn("event=adapter_local_denied reason=classification_not_read_only tool={} classified={}", invocation.tool(),
                    classified == null ? "-" : classified.replaceAll("[^A-Z_]", "?"));
            count("mcp_adapter_local_denied_total", "reason", "classification_not_read_only");
            throw new SecurityException("the classification of this tool does not allow it on an adapter");
        }
        String hint = invocation.risk().name();
        if (!hint.equals(classified)) {
            LOG.warn("event=adapter_classification_diverges tool={} classified={} hints={}", invocation.tool(), classified, hint);
            if (meters != null) {
                meters.counter("mcp_adapter_classification_divergence_total", "classified", classified, "hints", hint).increment();
            }
        }
        return next.proceed(invocation.arguments());
    }

    private void count(String name, String tag, String value) {
        if (meters != null) {
            meters.counter(name, tag, value).increment();
        }
    }
}
