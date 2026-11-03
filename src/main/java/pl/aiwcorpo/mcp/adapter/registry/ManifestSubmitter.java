package pl.aiwcorpo.mcp.adapter.registry;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;

/**
 * Sends the manifest to the gateway's registry with {@code submitAdapterManifest} (C5, CC-206): {@code PUT
 * /admin/v1/registry/adapters/{serverId}} over mTLS, with the certificate whose CN is {@code mcp-adapter-<serverId>}. Starts when the
 * server is ready to answer, because the registry may read {@code tools/list} of the adapter afterwards, and repeats with a growing
 * pause until the registry answers {@code 200}: the gateway may be starting, or its configuration for this server may still be
 * missing ({@code 403}, {@code 422 adapter_mode_mismatch}), which an administrator fixes without restarting the adapter.
 *
 * <p>The call approves nothing: every new or changed tool is pending until an administrator classifies it. The answer body is not
 * logged, only its HTTP status and the {@code reason} code when it has one.
 */
public final class ManifestSubmitter {

    private static final Logger LOG = LoggerFactory.getLogger(ManifestSubmitter.class);
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([a-z_]{1,64})\"");

    private final AdapterProperties.Registry settings;
    private final String serverId;
    private final ManifestCheck manifest;
    private final HttpClient http;
    private final AtomicBoolean submitted = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger lastStatus = new AtomicInteger();

    public ManifestSubmitter(AdapterProperties.Registry settings, String serverId, ManifestCheck manifest, HttpClient http,
                             MeterRegistry meters) {
        this.settings = settings;
        this.serverId = serverId;
        this.manifest = manifest;
        this.http = http;
        if (meters != null) {
            meters.gauge("mcp_adapter_manifest_submitted", submitted, flag -> flag.get() ? 1 : 0);
            // 0 until the first answer; a status other than 200 that stays is a configuration an administrator has to fix (403, 422).
            meters.gauge("mcp_adapter_manifest_last_status", lastStatus, AtomicInteger::get);
        }
    }

    public boolean submitted() {
        return submitted.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (settings.adminUrl() == null || manifest.manifest().isEmpty() || !started.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("manifest-submitter").start(this::run);
    }

    private void run() {
        Duration pause = settings.retryInterval();
        while (!submitted.get()) {
            if (attempt()) {
                return;
            }
            try {
                Thread.sleep(pause.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            pause = pause.multipliedBy(2).compareTo(settings.maxRetryInterval()) > 0 ? settings.maxRetryInterval() : pause.multipliedBy(2);
        }
    }

    /** One attempt; true when the registry stored the manifest. */
    public boolean attempt() {
        URI uri = settings.adminUrl().resolve("/admin/v1/registry/adapters/" + serverId);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(settings.requestTimeout())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(manifest.manifest().orElseThrow(), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream stream = response.body()) {
                body = new String(stream.readNBytes((int) Math.min(settings.maxResponseSize().toBytes(), Integer.MAX_VALUE)),
                        StandardCharsets.UTF_8);
            }
            int status = response.statusCode();
            lastStatus.set(status);
            if (status == 200) {
                submitted.set(true);
                LOG.info("event=manifest_submitted serverId={} status=200", serverId);
                return true;
            }
            Matcher reason = REASON.matcher(body);
            LOG.error("event=manifest_rejected serverId={} status={} reason={} action=retry", serverId, status,
                    reason.find() ? reason.group(1) : "-");
        } catch (IOException ex) {
            lastStatus.set(-1);
            LOG.warn("event=manifest_submit_failed serverId={} exception={} action=retry", serverId, ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
