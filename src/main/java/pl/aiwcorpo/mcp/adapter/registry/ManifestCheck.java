package pl.aiwcorpo.mcp.adapter.registry;

import io.modelcontextprotocol.server.McpSyncServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/**
 * Builds the manifest once the MCP server is up. A manifest the registry would reject stops the server here, at startup, instead of
 * being refused by the gateway after the container looks healthy.
 */
public final class ManifestCheck implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(ManifestCheck.class);

    private final ObjectProvider<McpSyncServer> servers;
    private final McpPlatformProperties platform;
    private final boolean devEnvironment;
    private volatile String manifest;

    public ManifestCheck(ObjectProvider<McpSyncServer> servers, McpPlatformProperties platform, boolean devEnvironment) {
        this.servers = servers;
        this.platform = platform;
        this.devEnvironment = devEnvironment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        McpSyncServer server = servers.getIfAvailable();
        if (server == null) {
            return;
        }
        manifest = AdapterManifest.build(platform, server.listTools(), devEnvironment);
        String file = platform.getManifest().getOutputFile();
        if (file != null && !file.isBlank()) {
            try {
                Path path = Path.of(file);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(path, manifest + "\n", StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException("cannot write the manifest to " + file, ex);
            }
        }
        LOG.info("event=manifest_ready tools={}", server.listTools().size());
    }

    public Optional<String> manifest() {
        return Optional.ofNullable(manifest);
    }
}
