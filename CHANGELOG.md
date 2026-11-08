# Changelog

Format: SemVer versions, newest first.

## 2.0.0

Major release: no list of systems, no policy settings. Built on `mcp-platform-starter` 4.0.0 and `mcp-contracts` 7.0.0 (CC-229 ... CC-241).

- CC-249, the gateway connects to the adapter by its address: the adapter no longer submits its manifest (no registry channel, no certificate
  `mcp-adapter-<serverId>`, no `MCP_REGISTRY_*`, `ManifestSubmitter`, `AdapterManifest` or the metrics `mcp_adapter_manifest_*`). The starter serves the manifest
  it builds in `receiver-mode: adapter` (`source: adapter`) at `GET /.well-known/mcp-manifest`, and the gateway pulls it; the server is an adapter because that
  manifest says so, no `adapter: true` is given when it is added. The adapter takes `POST /.well-known/mcp-policy-data` and decides nothing with it. No server
  certificate from the internal CA, no mTLS, no `MCP_INTERNAL_URL`, `MCP_TLS_TRUSTSTORE` and no Redis service in `deploy/compose.yaml`: TLS is the author's own
  certificate (`MCP_TLS_CERT` and `MCP_TLS_KEY`, or `MCP_TLS_KEYSTORE`), a self-signed one generated once into `MCP_STATE_DIR` (its fingerprint is in the log at
  start), or `MCP_PLAIN_HTTP=true`; the jti is kept in memory unless `MCP_REDIS_HOST` is set. `MCP_GATEWAY_JWKS_URI` is the public
  `https://<domain>/.well-known/mcp-jwks.json`. `deploy/README.md` has the new procedure.
- Works for any system id that matches the contracts (`^[a-z][a-z0-9]{1,30}$`): `MCP_SERVER_ID` has no default and the adapter holds no list of
  systems. The server is added in the gateway with `adapter: true` at creation (the flag cannot be changed later).
- The manifest stays `source: adapter` with no `risk`, `action`, `resourceTypes`, `policies` or `policiesHash`; tests check the absence and a system
  outside the five former ones.
- Removed: `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR`, `aiwcorpo.mcp.platform.policy.*`, the
  `/app/state` volume and the policy keys. An adapter takes no policy data from the gateway either: the starter turns its policy engine off in the
  `adapter` receiver mode, so the gateway alone decides `call`.
- Removed `ClassifiedToolRiskRegistry`: nothing asks the starter for the risk of a tool during a call any more. The local refusal of a
  classification other than `READ_ONLY` (`ClassificationInterceptor`) and the divergence log and counter stay. The test of the local read group is gone
  with the local Cedar check.
- `deploy/` has `README.md` (certificates: server CN `mcp-<system>`, SAN `mcp-<system>.internal`, `serverAuth` and `clientAuth`; registry CN
  `mcp-adapter-mcp-<system>`; the procedure that adds the system in the broker and the server in the gateway with `adapter: true`, deploys, classifies
  the tools and removes both, with nothing restarting), `compose.yaml`, `mcp-adapter.env` and `certs/`. The demo fragment lost its policy settings.

## 1.0.0

First public release. Built on `mcp-platform-starter` 3.0.0 and `mcp-contracts` 6.3.2 (CC-193, CC-206, CC-207, CC-226, CC-227).

- Sidecar that puts a foreign MCP server behind the gateway: it accepts only gateway traffic (mTLS and the internal JWT) and forwards
  calls with the credential of the calling user.
- Reads `tools/list` of the foreign server at start (waiting for it), rewrites schemas into closed and bounded ones, skips tools that cannot
  be bounded, supports a name prefix and a list of allowed tools.
- C6 manifest with `source: adapter`, built from the tool list the adapter exposes and submitted with `submitAdapterManifest` over mTLS
  (CN `mcp-adapter-<serverId>`), with retries.
- `tools/call`: the user's credential in a header of configurable format (default `Authorization: Token <PAT>`), one session per call,
  argument validation before forwarding, result as `UntrustedText`, fixed `not_found_or_denied`, `X-Request-Id` passed to the foreign server.
- Demo: `sooperset/mcp-atlassian` 0.23.1 (MIT), image pinned by digest, details in the README.
- Receiver mode `adapter` (CC-226): the local decision uses the administrator's classification from the JWT (`mcp_risk`, `mcp_action`); a
  missing classification gives `401`; a value other than `READ_ONLY` is refused locally (CC-227); a divergence from the foreign server's hints is
  logged and counted. The substitute risk is used only for the hints in the manifest and for the hash.
- After review: one deadline for the whole exchange with the foreign server, hygiene of argument descriptions, `enum`/`const` and property
  names (the tool is rejected), a schema node budget, validation of `mcp-path`, of the header format and of the registry address, HTTP 401 of
  the foreign server as `credential_required`, the technical reason in the log and in a metric, the substitute risk raised to `DESTRUCTIVE`
  for names with a destructive word, a hardened foreign container in separate networks, user 10001, the CI image pinned by digest.
- Apache-2.0 license, a release workflow with an image on ghcr.io and a CycloneDX SBOM, `SECURITY.md`, `CONTRIBUTING.md`.
