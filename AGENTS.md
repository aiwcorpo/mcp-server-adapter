# AGENTS.md

A sidecar of [MCP Platform](https://github.com/aiwcorpo/mcp-platform) that puts one third-party MCP server, whose code we do not
control, behind `mcp-gateway`. It reads the server's tool list, bounds every input schema, and forwards each call over MCP
streamable HTTP with the calling user's credential. It is built on `mcp-starter-lib` in `receiver-mode: adapter` (CC-226,
`mcp-platform.version` in `pom.xml`). The starter serves the C6 manifest with `source: adapter` at C14
`GET /.well-known/mcp-manifest`; the gateway learns that the server is an adapter from that manifest, not from any setting.
How it works and the invariants: CONTRIBUTING.md. Operator steps: README.md and `deploy/README.md`.

## Commands

JDK 25 is required; the default `java` on PATH may be older. On macOS with Homebrew:
`export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.

```bash
./mvnw -B verify                          # full CI run, about 25 s, no Docker; writes target/mcp-server-adapter.jar
./mvnw -B test -Dtest=SchemaTightenerTest # one test class
docker build -t mcp-server-adapter:dev .  # after verify; the Dockerfile copies the jar from target/
```

`RealImageTest` (profile `real-image`) runs the real `mcp-atlassian` image against a fake Jira on the host. It is excluded from
`verify` and run by hand when `ForeignMcpClient` or `SchemaTightener` changes; the `docker run` line is in CONTRIBUTING.md. Use a
one-off container bound to 127.0.0.1 only. There is no separate lint or formatter step.

## Layout

- `src/main/java/pl/aiwcorpo/mcp/adapter/`: `schema/` bounds input schemas and drops tools it cannot bound, `foreign/` is the
  client of the third-party server and its tool catalog, `tool/` turns calls into foreign calls, enforces the classification and
  maps errors.
- Tests run the third-party server in the test JVM (`StubForeignServer`). `src/test/resources/mcp-atlassian-0.23.1-tools.json` is
  the real tool list of that release; keep it as captured.
- `deploy/compose.yaml` pins the adapter image tag to the release version; `deploy/docker-compose.demo.yml` is the test-stack
  example with `mcp-atlassian` pinned by digest.

## Rules

- Contract first: a change to the manifest, the well-known paths or an error code starts as a `proposed` CC entry in
  `CONTRACT_CHANGES.md` of `mcp-contracts` and waits for a person's approval. Cite the CC id in the commit.
- Fail closed: a tool without a classification, a classification other than `READ_ONLY`, a call without the `mcp_risk` and
  `mcp_action` claims, and a schema that cannot be bounded all mean refusal. Do not add a write path or a local risk decision;
  classification belongs to the administrator in the gateway.
- Nothing the third-party server answers is logged or copied into an error. Its errors become `not_found_or_denied`, HTTP 401
  becomes `credential_required`, unreachable or slow becomes `upstream_unavailable`; the reason goes to `event=foreign_call_failed`
  and the metric `mcp_adapter_foreign_failures_total{reason}`.
- Each call gets a new MCP session with the user's credential only: no shared credential, no redirects followed, nothing kept
  between calls. Results stay `UntrustedText` (normalized, cut to `result-max-chars`, non-text content dropped).
- Arguments outside the published schema are `invalid_argument` without contacting the server. Audit carries no arguments or
  results.
- The image stays on a glibc base (the native Cedar library fails on musl), user 10001:10001, writing only to
  `/var/lib/mcp/state`. Base images stay pinned by digest, workflow actions by commit SHA.
- Test methods are sentences in camelCase. CHANGELOG.md: newest first, CC ids in the bullets; the version heading is written by
  the person who releases.

## Boundaries

Ask a person before you change the version in `pom.xml` or `deploy/compose.yaml`, create or push a tag, change
`.github/workflows/release.yml` (images go to `ghcr.io/aiwcorpo/mcp-server-adapter` only), push to `main`, or point the adapter
at a real third-party server, Jira or account. Tests use dummy tokens and data only. Never commit secrets, `.env` files, filled-in
`mcp-adapter.env` values, TLS material, internal host names or IP addresses.

## Pull requests

One change per PR. Commit subject in English, imperative, up to 72 characters; the body says what changed and why. Tests go in
the same commit as the code; add a negative test for every refusal. `./mvnw -B verify` must pass. Update README.md,
`deploy/README.md` and CHANGELOG.md when an operator would notice the change. See CONTRIBUTING.md.
