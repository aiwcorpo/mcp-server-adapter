package pl.aiwcorpo.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.unit.DataSize;
import pl.aiwcorpo.mcp.adapter.config.AdapterProperties;
import pl.aiwcorpo.mcp.adapter.registry.ManifestCheck;
import pl.aiwcorpo.mcp.adapter.registry.ManifestSubmitter;

/** submitAdapterManifest: the PUT to the path of the server id, the body the manifest, retries until the registry stores it. */
class ManifestSubmissionTest extends ServerTestSupport {

    @Autowired
    ManifestCheck check;

    private ManifestSubmitter submitter() {
        AdapterProperties.Registry settings = new AdapterProperties.Registry(URI.create(REGISTRY.url()), null,
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(2), DataSize.ofKilobytes(64));
        return new ManifestSubmitter(settings, SERVER_ID, check, HttpClient.newHttpClient(), null);
    }

    @Test
    void theStartupSubmissionSentTheManifestToThePathOfTheServer() {
        REGISTRY.answer();
        ManifestSubmitter submitter = submitter();

        assertThat(submitter.attempt()).isTrue();

        assertThat(REGISTRY.puts()).singleElement().satisfies(put -> {
            assertThat(put.path()).isEqualTo("/admin/v1/registry/adapters/mcp-jira");
            assertThat(put.contentType()).isEqualTo("application/json");
            assertThat(JSON.readTree(put.body())).isEqualTo(JSON.readTree(check.manifest().orElseThrow()));
        });
    }

    @Test
    void aRejectionIsNotSuccessAndIsRetriedUntilTheRegistryStoresTheManifest() {
        REGISTRY.answer(403, 422, 503);
        ManifestSubmitter submitter = submitter();

        assertThat(submitter.attempt()).isFalse();
        assertThat(submitter.attempt()).isFalse();
        assertThat(submitter.attempt()).isFalse();
        assertThat(submitter.submitted()).isFalse();
        assertThat(submitter.attempt()).isTrue();
        assertThat(REGISTRY.puts()).hasSize(4);
    }

    @Test
    void theBackgroundSubmitterKeepsTryingAfterTheFirstFailures() {
        REGISTRY.answer(500, 500);
        ManifestSubmitter submitter = submitter();

        submitter.onReady();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(submitter.submitted()).isTrue());
        assertThat(REGISTRY.puts()).hasSize(3);
    }

    @Test
    void aRegistryThatIsNotThereIsRetriedNotFatal() {
        AdapterProperties.Registry settings = new AdapterProperties.Registry(URI.create("http://127.0.0.1:1"), null,
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(1), DataSize.ofKilobytes(64));
        ManifestSubmitter submitter = new ManifestSubmitter(settings, SERVER_ID, check, HttpClient.newHttpClient(), null);

        assertThat(submitter.attempt()).isFalse();
    }
}
