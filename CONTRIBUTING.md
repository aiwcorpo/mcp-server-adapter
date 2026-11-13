# Contributing

The adapter follows the contracts of `mcp-contracts` (C14 `GET /.well-known/mcp-manifest`, C6 manifest with `source: adapter`). A
change that needs a different contract starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that
repository and needs approval before it is implemented here.

## Setup

JDK 25. The tests need no container runtime: the third-party server is a stub inside the test JVM. The platform artifacts
(`mcp-platform-starter` 4.0.0, `mcp-contracts` 7.0.0) come from Maven Central, or must already be in `~/.m2`.

```bash
./mvnw -B verify                        # tests and target/mcp-server-adapter.jar; the same command runs in CI
docker build -t mcp-server-adapter:dev .
```

`./mvnw -B verify` must pass before a merge. The test against the real `mcp-atlassian` image is run by hand when the foreign client
or the schema bounding changes. Start a one-off container (port on 127.0.0.1 only), then run the profile; the test starts a fake Jira
on `127.0.0.1:18932` (Bearer `alice-pat`, `bob-pat`; issue `ABC-1`):

```bash
docker run -d --rm --name adapter-probe -p 127.0.0.1:18931:8000 \
  -e JIRA_URL=http://host.docker.internal:18932 -e JIRA_PERSONAL_TOKEN=placeholder-never-used -e TOOLSETS=all \
  -e ENABLED_TOOLS=jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions \
  ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466 \
  --transport streamable-http --port 8000 --host 0.0.0.0
./mvnw -B test -Preal-image -Dtest=RealImageTest
```

The image runs as user 10001:10001 on a glibc base (the native Cedar library does not work on musl) and listens on 8443. It writes
only to `/var/lib/mcp/state` (a volume): the generated certificate and the cursor key. The release image is published as
`ghcr.io/aiwcorpo/mcp-server-adapter:<version>` by `.github/workflows/release.yml`. The workflow refuses a tag whose commit is not on
`main` and a version already in the registry; who may push a tag and start the publish job is decided on GitHub: a tag ruleset on `v*`
limited to the release maintainers, and required reviewers on the environment `release`.

## Running locally

`.run/` has two IntelliJ configurations. Start the third-party server first with the `docker run` above, but on port 18931 and with
`JIRA_URL=http://localhost:18081`; the image must already be in your local Docker (the startup waits up to 90 s). Then start
`LocalGateway` (the gateway of `mcp-platform-test` 4.0.0 on `http://127.0.0.1:18500/mcp`; needs the artifact in `~/.m2`, from
`./mvnw verify` of the starter) and `McpServerAdapter (dev, behind LocalGateway)`. `tools/list` works with any
`Authorization: Bearer <token>`. `tools/call` answers `unauthenticated`: LocalGateway signs no `mcp_risk` and `mcp_action`, which an
adapter requires, so calls need a real gateway. Stop the server with `docker stop adapter-foreign-dev`.

## How it works

```
MCP client -> gateway --TLS + internal JWT + X-MCP-Credential--> adapter --HTTP + Authorization: Token <PAT>--> third-party server
                 |                                                ^
                 +--- GET /.well-known/mcp-manifest --------------+   (POST /.well-known/mcp-policy-data is pushed)
```

1. Start. The adapter waits up to `startup-wait` (90 s) for the server to answer `tools/list`, rewrites every `inputSchema` into a
   closed, bounded schema and drops the tools it cannot bound (open map, union of two types, recursion, list without `items`, depth
   above 8, a name that breaks the contract, more than 2000 schema nodes, hidden characters in names, enums or descriptions).
2. Manifest. The starter builds a C6 manifest with `source: adapter` (no `risk` and `action`, empty `resourceRefs`) and serves it
   at `GET /.well-known/mcp-manifest`. Nothing is approved by pulling it: each tool is `pending_approval` until an administrator
   classifies it, and an unclassified tool is hidden. The adapter has no policy engine; it checks the shape of the pushed policy data
   and decides nothing with it.
3. Call. The starter checks the caller and the arguments against the published schema, and the adapter refuses a classification
   other than `READ_ONLY` (claims `mcp_risk` and `mcp_action`; a call without them is `401`). Then it opens a one-call MCP session
   with an `Authorization` header built from the user's credential and returns the result as untrusted text.

## Invariants to keep

- Only the gateway can call the adapter: a request without the `mcp-gateway` certificate and a valid internal JWT gets `401`;
  `/actuator/health` is the only path without a JWT.
- The user's credential reaches the server, never a shared one. The session lives for one call, redirects are not followed, and
  nothing the server answered is logged or copied into errors. The adapter holds no secret besides its TLS key.
- Arguments outside the published schema are `invalid_argument`, without contacting the server.
- Results are `UntrustedText`: normalized (no control, bidirectional or zero-width characters) and cut to `result-max-chars`;
  non-text content is dropped.
- Errors of the server give `not_found_or_denied`. Exceptions: HTTP 401 at the transport is `credential_required`, an unreachable
  or slow server is `upstream_unavailable`, an oversized answer is `internal_error`. The reason goes to the log
  (`event=foreign_call_failed`) and to `mcp_adapter_foreign_failures_total{reason}`.
- Fail closed: a missing classification, a schema that cannot be bounded and a classification other than `READ_ONLY` mean refusal.
- One audit event `tool_call` per call, with the gateway's `requestId` (also sent to the server as `X-Request-Id`), without
  arguments or results.

Known limits: the adapter does not refresh the manifest at runtime, keeps no sessions between calls (four HTTP requests per call)
and does not judge whether the server is safe. The same answer for "does not exist" and "no access" depends on the server; for
`mcp-atlassian` 0.23.1 a Jira error comes back as `isError`.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the same commit as
the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
