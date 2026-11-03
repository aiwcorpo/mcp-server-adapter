package pl.aiwcorpo.mcp.adapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import pl.aiwcorpo.mcp.platform.manifest.PlatformManifestAutoConfiguration;

/**
 * The starter's own manifest (built from {@code @ToolRisk}, with {@code risk} and {@code action} in every entry) is the wrong one
 * for an adapter, so its configuration is excluded; {@link pl.aiwcorpo.mcp.adapter.registry.ManifestCheck} builds the adapter's.
 * The starter does not require the manifest configuration, unlike the JWT check, the audit wrapper and the policy engine.
 */
@SpringBootApplication(exclude = PlatformManifestAutoConfiguration.class)
public class AdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdapterApplication.class, args);
    }
}
