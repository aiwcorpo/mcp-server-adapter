package pl.aiwcorpo.mcp.adapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The manifest of the adapter ({@code source: adapter}, CC-193) is the one the starter builds in {@code receiver-mode: adapter} from the
 * tools this server lists, and the starter serves it at {@code GET /.well-known/mcp-manifest} for the gateway to pull (CC-249).
 */
@SpringBootApplication
public class AdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdapterApplication.class, args);
    }
}
