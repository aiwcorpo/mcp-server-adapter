# mcp-server-adapter

A Java sidecar that puts a foreign MCP server (one whose code the platform does not control) behind `mcp-gateway`.

A foreign server knows nothing about the platform: it does not verify the internal JWT, does not know the policies and does not
accept the credential in the platform's form. The adapter does that for it. It accepts traffic only from the gateway (mTLS and the
internal JWT, like every server built on the starter), reads the foreign server's tool list at start, submits it to the registry as a
manifest and forwards calls with the credential of the user who made them.

It is for platform operators who want to connect a ready-made MCP server (open source or from a vendor) to any system of the
platform: the system id is any `SystemId` of the contracts (`^[a-z][a-z0-9]{1,30}$`) and nothing in the adapter enumerates systems. It is one
container image configured through environment variables, not a library or a template. It has no policies and no resource types of its own:
the gateway decides every call, and an administrator classifies the tools at approval.

Stack: Java 25, Spring Boot 4.1.0, `mcp-platform-starter` 4.0.0 (the version in `pom.xml`), `mcp-contracts` 7.0.0.
License: Apache-2.0 ([LICENSE](LICENSE), [NOTICE](NOTICE)). Current state and plan: [STATUS.md](STATUS.md) (in Polish).
Changes: [CHANGELOG.md](CHANGELOG.md). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md). Vulnerabilities: [SECURITY.md](SECURITY.md).

Operator and developer guides for the whole platform are in [mcp-docs](https://github.com/aiwcorpo/mcp-docs):
`docs/operator/foreign-servers.md` (connecting a foreign server) and `docs/developer/adapter.md` (what the adapter does to the tools).

## How it works

```
MCP client -> gateway --mTLS + JWT C2 + X-MCP-Credential--> adapter --HTTP + Authorization: Token <PAT>--> foreign MCP server -> system
                 ^                                             |
                 +--- PUT /admin/v1/registry/adapters/{id} ----+   (at start, certificate mcp-adapter-<serverId>)
```

1. Start. The adapter waits (up to `startup-wait`, 90 s by default) until the foreign server answers `tools/list`, rewrites every
   `inputSchema` into a closed, bounded schema and drops the tools it cannot bound.
2. Manifest. It builds a C6 manifest with `source: adapter` (no `risk` and `action`, empty `resourceRefs`), checks it against the registry
   rules and submits it with `PUT /admin/v1/registry/adapters/{serverId}`, retrying until the registry answers `200`. Nothing is
   approved by this call: each tool is `pending_approval` until an administrator classifies it in the portal. An unclassified tool is
   hidden by the gateway.
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

JDK 25 is required. The tests need no container runtime: the foreign server and the registry are stubs inside the test JVM.
The platform artifacts (`mcp-platform-starter`, `mcp-contracts`) come from Maven Central; no registry login is needed.
Linux and macOS with bash 3.2 or newer; on Windows use WSL2.

```bash
make ci            # ci/run.sh verify: text checks, clean verify, fails when no test ran or one was skipped
ci/run.sh image    # docker build, tag mcp-server-adapter:m6-<sha7>; never pushes
```

The container runs as the numeric user 10001:10001 on a glibc base (the native Cedar library does not work on musl) and listens
on 8443. The adapter writes nothing to disk. The release image is published as
`ghcr.io/aiwcorpo/mcp-server-adapter:<version>` by `.github/workflows/release.yml`.

## Configuration

Everything is set through environment variables read from `src/main/resources/application.yml`. The generated table is in mcp-docs:
`docs/reference/env-adapter.md`. Settings of the starter (JWT, TLS, Redis) are the same as for any server built on it, except that an adapter has no `policy-data` and no `MCP_POLICY_*`: it has no policies of its own and takes no data from the gateway.

The settings you need to get started:

- `MCP_SERVER_ID`: `mcp-<system>` with any `SystemId`, no default. The same `serverId` that was added in the gateway with `adapter: true` (set at creation, never changed).
- `MCP_FOREIGN_URL`, `MCP_FOREIGN_PATH`: the foreign server and its MCP path (`/mcp`). https is required; plain http only with
  `MCP_FOREIGN_ALLOW_PLAIN_HTTP=true` in the `dev` profile.
- `MCP_FOREIGN_AUTHORIZATION_FORMAT`: how the credential becomes a header, `Token {credential}` by default. Start requires
  `{credential}` exactly once; a fixed value would mean a shared account instead of the user's identity.
- `MCP_FOREIGN_DISCOVERY_AUTHORIZATION`: a header used only for the startup `tools/list`, for servers that refuse any request without one.
- `MCP_ADAPTER_TOOLS_INCLUDE`, `MCP_ADAPTER_STRIP_PREFIX`: which tools to publish and which prefix to remove from their names.
- `MCP_REGISTRY_URL`, `MCP_REGISTRY_TLS_KEYSTORE`, `MCP_REGISTRY_SSL_BUNDLE` (`adapter-registry`): the gateway's internal port and the client certificate with CN `mcp-adapter-<serverId>`.
- `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD`: the server certificate (CN `<serverId>`, SAN `<serverId>.internal`, usages `serverAuth` and `clientAuth`) and the internal CA.
- `MCP_INTERNAL_URL`: the address under which the gateway reaches the adapter.

Limits (`aiwcorpo.mcp.adapter.limits.*`) and timeouts (`foreign.connect-timeout`, `request-timeout`, `max-response-size`) are in
`AdapterProperties`. The metric `mcp_adapter_manifest_submitted` is 1 once the registry has stored the manifest;
`mcp_adapter_manifest_last_status` is the last HTTP status of the registry (0 before the first answer, -1 when it is unreachable).
When the registry refuses (`403`, `422 adapter_mode_mismatch`) the log shows `event=manifest_rejected` and the adapter retries at most
every 60 s, so fixing the gateway configuration needs no adapter restart.

## Demo: sooperset/mcp-atlassian 0.23.1 for Jira

The demo wraps [sooperset/mcp-atlassian](https://github.com/sooperset/mcp-atlassian) (MIT) with four read tools. The image is pinned by
index digest:

```
ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466
```

The full Compose fragment is `deploy/docker-compose.demo.yml`; how to install an adapter in general, with the certificates and the steps that
add and remove the server without restarting anything, is in [deploy/README.md](deploy/README.md). Points that are not obvious from it:

- `ENABLED_TOOLS=jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions`. A write tool would stay hidden
  (`tool_disabled`), because the registry allows only `READ_ONLY` for adapter tools.
- `JIRA_PERSONAL_TOKEN=placeholder-never-used` is a filler that is never used: without it the server does not list the Jira tools. `ALLOW_GLOBAL_CRED_FALLBACK`
  is off, so a call without a credential in the header ends with `401` there.
- The server also refuses `initialize` without `Authorization`, hence `MCP_FOREIGN_DISCOVERY_AUTHORIZATION=Token discovery-only`.
- The foreign container is hardened (`read_only`, `cap_drop: [ALL]`, `no-new-privileges`, memory and process limits) and sits in two
  internal networks only, so it does not see Redis or other servers.
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

- Traffic only from the gateway (mTLS and the internal JWT); no anonymous path except health.
- The user's credential reaches the foreign server, never a shared one. The adapter holds no secrets besides its TLS keys.
- Fail closed: a missing classification, a schema that cannot be bounded and a classification other than `READ_ONLY` all mean refusal.
- Text from the foreign server reaches the model only as untrusted data, and tool descriptions are pinned by the registry hash that
  the administrator approves.
- One audit event `tool_call` per call, with the gateway's `requestId` (also sent to the foreign server as `X-Request-Id`), without
  arguments or results.

Residual risk: invariant 8 (the same answer for "does not exist" and "no access") depends on the foreign server. If it returns an error
as an ordinary result, for example the text "Issue does not exist", that text reaches the model and shows the difference. The adapter
cannot catch this. Check how a server reports errors before connecting it (for mcp-atlassian 0.23.1 a Jira error comes back as
`isError`, verified on the real image).

The adapter also does not refresh the manifest at runtime (a changed tool list needs a restart), does not keep sessions between calls
(four HTTP requests per call) and does not judge whether the foreign server is safe itself.
