# Changelog

Format: SemVer versions, newest first.

## 0.1.0 - in preparation

- Sidecar of a foreign MCP server on `mcp-platform-starter` 3.0.0-beta-14 and contracts 6.3.2 (CC-193, CC-206, CC-207, CC-226, CC-227).
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
