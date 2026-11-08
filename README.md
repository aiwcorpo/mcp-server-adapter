# mcp-server-adapter

A Java sidecar that puts a foreign MCP server (one whose code the platform does not control) behind `mcp-gateway`.

A foreign server knows nothing about the platform: it does not verify the internal JWT, does not know the policies and does not
accept the credential in the platform's form. The adapter does that for it. The gateway connects to its address; it accepts only calls with the
gateway's internal JWT (like every server built on the starter), reads the foreign server's tool list at start, serves it as a manifest for
the gateway to pull and forwards calls with the credential of the user who made them.

It is for platform operators who want to connect a ready-made MCP server (open source or from a vendor) to any system of the
platform: the system id is any `SystemId` of the contracts (`^[a-z][a-z0-9]{1,30}$`) and nothing in the adapter enumerates systems. It is one
container image configured through environment variables, not a library or a template. It has no policies and no resource types of its own:
the gateway decides every call, and an administrator classifies the tools at approval.

Stack: Java 25, Spring Boot 4.1.0, `mcp-platform-starter` 4.0.0 (the version in `pom.xml`), `mcp-contracts` 7.0.0.
License: Apache-2.0 ([LICENSE](LICENSE), [NOTICE](NOTICE)).
Changes: [CHANGELOG.md](CHANGELOG.md). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md). Vulnerabilities: [SECURITY.md](SECURITY.md).

Operator and developer guides for the whole platform are in [mcp-docs](https://github.com/aiwcorpo/mcp-docs):
`docs/operator/foreign-servers.md` (connecting a foreign server) and `docs/developer/adapter.md` (what the adapter does to the tools).

## How it works

```
MCP client -> gateway --TLS + JWT C2 + X-MCP-Credential--> adapter --HTTP + Authorization: Token <PAT>--> foreign MCP server -> system
                 |                                           ^
                 +--- GET /.well-known/mcp-manifest ---------+   (pulled by the gateway; POST /.well-known/mcp-policy-data is pushed)
```

1. Start. The adapter waits (up to `startup-wait`, 90 s by default) until the foreign server answers `tools/list`, rewrites every
   `inputSchema` into a closed, bounded schema and drops the tools it cannot bound.
2. Manifest. The starter builds a C6 manifest with `source: adapter` (no `risk` and `action`, empty `resourceRefs`), checks it against the
   gateway's rules and serves it at `GET /.well-known/mcp-manifest` for the gateway to pull when an administrator adds the address, on a
   refresh and in its runtime loop. Nothing is approved by pulling it: each tool is `pending_approval` until an administrator classifies it in the
   portal. An unclassified tool is hidden by the gateway. The adapter has no policy engine; it takes the policy data pushed to
   `POST /.well-known/mcp-policy-data`, checks its shape and decides nothing with it.
3. Call. The starter checks the caller and the arguments against the published schema, and the adapter refuses a classification other
   than `READ_ONLY`. The gateway has already decided the call by group and the emergency overrides. Only then does the adapter open a one-call MCP session to the foreign server with an
   `Authorization` header built from the user's credential, and return the result as untrusted data.

What the adapter guarantees:

- Only the gateway can call it. A request without the `mcp-gateway` certificate and a valid internal JWT gets `401`; `/actuator/health`
  is the only path without a JWT.
- It uses the credential of the calling user, never one of its own. The session to the foreign server lives for one call, redirects are
  not followed, and nothing the foreign server answered is logged or copied into errors.
- Arguments outside the published schema are refused as `invalid_argument` without contacting the foreign server.
- Results are `UntrustedText`: normalized (no control, bidirectional or zero-width characters) and cut to `result-max-chars`. Non-text
  content is dropped.
- Every error the foreign server reports gives the same `not_found_or_denied`. Exceptions: HTTP 401 at the transport gives
  `credential_required`, an unreachable or slow server gives `upstream_unavailable`, an oversized answer gives `internal_error`.
  The technical reason goes to the log (`event=foreign_call_failed`) and to the counter `mcp_adapter_foreign_failures_total{reason}`.
- Only tools classified `READ_ONLY` are allowed, because tools of a foreign server have no `resourceRefs` and a write has no resource scope.
  The gateway sends the classification in the claims `mcp_risk` and `mcp_action`; a call without them is refused with `401`.

A tool is skipped (log line `event=foreign_tool_skipped` with the reason) when its schema cannot be bounded: an open map, a union of
two types, recursion, a list without `items`, depth above 8, a name that breaks the contract, more than 2000 schema nodes, or hidden
characters in names, enums or argument descriptions.

## Build and test

JDK 25 is required. The tests need no container runtime: the foreign server is a stub inside the test JVM.
The platform artifacts (`mcp-platform-starter`, `mcp-contracts`) come from Maven Central; no registry login is needed.

```bash
./mvnw -B verify                        # tests and target/mcp-server-adapter.jar
docker build -t mcp-server-adapter:dev .
```

### Run locally

`.run/` has two IntelliJ configurations, as the native servers have. Start the foreign server first: the demo one, from the image
pinned in the Demo section below, which must already be in your local Docker (the startup waits up to 90 s for it):

```bash
docker run -d --rm --name adapter-foreign-dev -p 127.0.0.1:18931:8000 -e JIRA_URL=http://localhost:18081 \
  -e JIRA_PERSONAL_TOKEN=placeholder-never-used -e ALLOW_GLOBAL_CRED_FALLBACK=false -e TOOLSETS=all \
  -e ENABLED_TOOLS=jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions \
  ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466 \
  --transport streamable-http --port 8000 --host 0.0.0.0
```

Then start `LocalGateway` (the gateway of `mcp-platform-test` 4.0.0 on `http://127.0.0.1:18500/mcp`, needs the artifact in `~/.m2`:
`./mvnw verify` of the starter) and `McpServerAdapter (dev, behind LocalGateway)`. `tools/list` works with any
`Authorization: Bearer <token>`. `tools/call` answers `unauthenticated`: LocalGateway signs no `mcp_risk` and `mcp_action`, which an
adapter requires, so calls need a real gateway. Stop the foreign server with `docker stop adapter-foreign-dev`.

The container runs as the numeric user 10001:10001 on a glibc base (the native Cedar library does not work on musl) and listens
on 8443. The adapter writes only to its state directory (`/var/lib/mcp/state`, a volume): the generated certificate and the cursor key. The release image is published as
`ghcr.io/aiwcorpo/mcp-server-adapter:<version>` by `.github/workflows/release.yml`.

## Configuration

Everything is set through environment variables read from `src/main/resources/application.yml`. The generated table is in mcp-docs:
`docs/reference/env-adapter.md`. Settings of the starter (JWT, TLS, Redis) are the same as for any server built on it, except that an adapter has no `policy-data` and no `MCP_POLICY_*`: it has no policies of its own and decides nothing with the policy data the gateway pushes.

The settings you need to get started:

- `MCP_SERVER_ID`: `mcp-<system>` with any `SystemId`, no default. The `serverId` the gateway derives from the manifest; the server is an adapter because the manifest says `source: adapter`, and that never changes.
- `MCP_FOREIGN_URL`, `MCP_FOREIGN_PATH`: the foreign server and its MCP path (`/mcp`). https is required; plain http only with
  `MCP_FOREIGN_ALLOW_PLAIN_HTTP=true` in the `dev` profile.
- `MCP_FOREIGN_AUTHORIZATION_FORMAT`: how the credential becomes a header, `Token {credential}` by default. Start requires
  `{credential}` exactly once; a fixed value would mean a shared account instead of the user's identity.
- `MCP_FOREIGN_DISCOVERY_AUTHORIZATION`: a header used only for the startup `tools/list`, for servers that refuse any request without one.
- `MCP_ADAPTER_TOOLS_INCLUDE`, `MCP_ADAPTER_STRIP_PREFIX`: which tools to publish and which prefix to remove from their names.
- `MCP_GATEWAY_JWKS_URI`: the public key set of the gateway, `https://<domain>/.well-known/mcp-jwks.json`.
- Optional: `MCP_TLS_CERT` and `MCP_TLS_KEY`, or `MCP_TLS_KEYSTORE` with `MCP_TLS_STORE_PASSWORD`, for your own certificate (otherwise the adapter
  generates a self-signed one into `MCP_STATE_DIR` and logs its fingerprint at start), `MCP_PLAIN_HTTP=true` for no TLS inside the Docker network of the
  platform, and `MCP_REDIS_HOST` and `MCP_PAGING_CURSOR_KEY` for several instances behind a balancer.

Limits (`aiwcorpo.mcp.adapter.limits.*`) and timeouts (`foreign.connect-timeout`, `request-timeout`, `max-response-size`) are in
`AdapterProperties`. The adapter submits nothing: the gateway pulls the manifest, so a change of the tool list (a restart of the adapter) reaches the
gateway at its next pull, within a minute, and the changed tools wait for classification again.

## Demo: sooperset/mcp-atlassian 0.23.1 for Jira

The demo wraps [sooperset/mcp-atlassian](https://github.com/sooperset/mcp-atlassian) (MIT) with four read tools. The image is pinned by
index digest:

```
ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466
```

The full Compose fragment is `deploy/docker-compose.demo.yml`; how to install an adapter in general, with the steps that
add and remove the server without restarting anything, is in [deploy/README.md](deploy/README.md). Points that are not obvious from it:

- `ENABLED_TOOLS=jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions`. A write tool would stay hidden
  (`tool_disabled`), because the gateway allows only `READ_ONLY` for adapter tools.
- `JIRA_PERSONAL_TOKEN=placeholder-never-used` is a filler that is never used: without it the server does not list the Jira tools. `ALLOW_GLOBAL_CRED_FALLBACK`
  is off, so a call without a credential in the header ends with `401` there.
- The server also refuses `initialize` without `Authorization`, hence `MCP_FOREIGN_DISCOVERY_AUTHORIZATION=Token discovery-only`.
- The foreign container is hardened (`read_only`, `cap_drop: [ALL]`, `no-new-privileges`, memory and process limits) and sits in two
  internal networks only, so it does not see other servers.
- The adapter sends `Authorization: Token <PAT>`; the server passes it to Jira as `Bearer <PAT>`.

After the schema rewrite the tools are `get_issue`, `search`, `get_all_projects` and `get_transitions` (prefix `jira_` removed), all
with `readOnlyHint: true`.

To check the real image, start a one-off container on the default Docker network (port on 127.0.0.1 only, no access to the stack) and run
the test profile; the test starts a fake Jira on `127.0.0.1:18932` (Bearer `alice-pat`, `bob-pat`; issue `ABC-1`):

```bash
docker run -d --rm --name adapter-probe -p 127.0.0.1:18931:8000 \
  -e JIRA_URL=http://host.docker.internal:18932 -e JIRA_PERSONAL_TOKEN=placeholder-never-used -e TOOLSETS=all \
  -e ENABLED_TOOLS=jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions \
  ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466 \
  --transport streamable-http --port 8000 --host 0.0.0.0
./mvnw -B test -Preal-image -Dtest=RealImageTest
```

## Security

Invariants of this component. The platform-wide list is in
[mcp-docs](https://github.com/aiwcorpo/mcp-docs) under `docs/security/invariants.md`.

- Traffic only from the gateway (the internal JWT of the right purpose on every path); no anonymous path except health.
- The user's credential reaches the foreign server, never a shared one. The adapter holds no secrets besides its TLS key.
- Fail closed: a missing classification, a schema that cannot be bounded and a classification other than `READ_ONLY` all mean refusal.
- Text from the foreign server reaches the model only as untrusted data, and tool descriptions are pinned by the hash in the manifest that
  the administrator approves.
- One audit event `tool_call` per call, with the gateway's `requestId` (also sent to the foreign server as `X-Request-Id`), without
  arguments or results.

Residual risk: invariant 8 (the same answer for "does not exist" and "no access") depends on the foreign server. If it returns an error
as an ordinary result, for example the text "Issue does not exist", that text reaches the model and shows the difference. The adapter
cannot catch this. Check how a server reports errors before connecting it (for mcp-atlassian 0.23.1 a Jira error comes back as
`isError`, verified on the real image).

The adapter also does not refresh the manifest at runtime (a changed tool list needs a restart), does not keep sessions between calls
(four HTTP requests per call) and does not judge whether the foreign server is safe itself.
