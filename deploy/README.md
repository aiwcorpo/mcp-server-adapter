# Install mcp-server-adapter

The sidecar of one foreign MCP server as a container (Docker Compose). It runs behind the MCP gateway; the gateway, the credential
broker, the identity provider and the foreign server itself are installed elsewhere. The image is built from the `Dockerfile` in
the repository root and published as `ghcr.io/aiwcorpo/mcp-server-adapter:<version>`.

```
deploy/
|-- compose.yaml                 the adapter as a container (read-only, no capabilities) and a state volume
|-- mcp-adapter.env              settings for Compose: fill in the empty values
|-- certs/                       only for your own certificate; may stay empty (never committed)
`-- docker-compose.demo.yml      fragment for the dev stack: the adapter with sooperset/mcp-atlassian 0.23.1 for Jira
```

You start the adapter; the platform operator enters its address in the portal and approves the connection. Nobody issues a certificate
for the adapter, creates a token or submits anything: the gateway connects to the address, reads the certificate, pulls the manifest (it says
`source: adapter`) and pushes the policy data itself. The host of the adapter may be another machine than the platform.

One adapter serves one foreign server and one system. The system id is not a list built into the adapter: any id that matches the
contracts (`^[a-z][a-z0-9]{1,30}$`, so `notes`, `jira`, `wiki2`) works, and the server id is `mcp-<system>`. For another foreign server copy
this directory and change `MCP_SERVER_ID` and the addresses.

## Prerequisites

- Docker Engine with Compose v2.20 or newer, 1 GiB of memory.
- The public key set of the gateway, `https://<domain of the platform>/.well-known/mcp-jwks.json` (the operator tells you the domain). The
  host must reach that domain on port 443, and the platform must reach the port of the adapter (8443 by default).
- The addresses of the foreign server and of the portal page for connecting an account.
- The foreign server on a network that only the adapter shares with it. It gets the credential of the calling user, so it must not
  be reachable from the networks of other servers.
- A certificate is optional. Without one the adapter generates a self-signed certificate on its first start and keeps it in the state
  volume. With your own (PEM files or a PKCS12 keystore, from any CA, signed or not) put it in `certs/`; see `mcp-adapter.env`.

## Install

1. Fill in the empty values in `mcp-adapter.env`: `MCP_SERVER_ID`, `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL` and `MCP_FOREIGN_URL`. The
   other lines are optional and commented out.
2. Start:

    ```bash
    docker compose up -d
    docker compose logs mcp-adapter | grep event=tls_certificate
    ```

    The adapter waits up to 90 s for the foreign server to answer `tools/list` and exits when it does not. The log line has the fingerprint
    of the certificate it serves:

    ```
    event=tls_certificate source=generated fingerprint=sha256:9f2c...e1 detail="..."
    ```

    Keep it: the operator compares it with the one the portal shows before approving the connection. The certificate is generated once and
    reused at every start, as long as the state volume (`state`, mounted at `/var/lib/mcp/state`) stays; a new volume is a new certificate and
    a new fingerprint to approve. With `docker compose down -v` the volume is gone.

3. Give the operator the address: `https://<host>:8443/mcp`, with the host name or address the platform reaches, and the fingerprint.

The adapter has no policy engine, so it never answers `409 policy_data_stale`: it takes the policy data the gateway pushes to
`POST /.well-known/mcp-policy-data`, checks only its shape, and decides nothing with it.

## Add the server to the platform

Systems and servers are data of the broker and the gateway, not settings of this directory. Nothing restarts, in either direction:
every instance sees a change within a few seconds. Adding, changing and removing need an administrator token with a fresh MFA login
(`mcp-platform-admin`); the portal (Administration) does all of this, and the calls below are the same operations for scripts.

1. **System in the broker.** Say how the user's credential is kept and which adapter of the broker talks to the product, for example
   a product that takes the user's own token (`user-secret`):

    ```bash
    curl --cert bff.crt --key bff.key --cacert ca.crt -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
      -H 'If-None-Match: *' -X PUT https://mcp-credential-broker.corp.internal:8443/v1/admin/systems/notes -d '{
        "baseUrl":"https://notes.corp.internal","adapter":"generic-rest",
        "supportedTypes":["user-secret"],"defaultType":"user-secret","secretScheme":"Bearer"}'
    ```

2. **Server in the gateway.** The operator enters the address `https://<host>:8443/mcp` in the portal (Administration, Servers, Add), or calls
   `POST /admin/v1/servers` with `{"internalUrl": "..."}` (fresh MFA). The gateway connects, reads the certificate, pulls the manifest and
   shows a card with the system, the certificate (subject, issuer, names, validity, fingerprint) and the tools it found. The `serverId` is
   `mcp-notes`, derived from the manifest, and the server is an adapter because its first manifest says `source: adapter`; that never changes
   (to turn an adapter into a native server or the other way, delete the server and add it again). An address that is loopback or link-local
   is refused, and `http` needs `allowPlainHttp`, which only an administrator may set (for an adapter inside the Docker network of the
   platform, started with `MCP_PLAIN_HTTP=true`).
3. **Approve the connection** (fresh MFA), after comparing the fingerprint on the card with the one in the adapter log:
    - a certificate that a CA trusted by the gateway signed, for the name of the address, is trusted as `ca`, and a renewal needs nothing;
    - any other certificate, a self-signed one included, is pinned (`pinned`) by its fingerprint: when the adapter later presents another
      certificate it becomes unreachable, and the portal shows the new fingerprint for approval.
4. **Classify the tools.** Every tool is `pending_approval` and hidden (`tool_disabled`) until an administrator classifies it with the
   approval (`POST /admin/v1/registry/servers/mcp-notes/tools/{tool}/approve` with `risk` and `action`, or Administration, Servers in the
   portal). The only classification an adapter takes is `READ_ONLY` with `read`; a tool that changes data stays unclassified and hidden.
   There are no policies to approve and no resource types: an adapter has neither, and decides nothing about resources.
5. **Groups.** The identity provider needs the group `mcp-notes-read`, which the gateway derives from the risk of a tool. There is no
   scope mapping for an adapter. A change in the foreign server's tool list (another image, another `MCP_ADAPTER_TOOLS_INCLUDE`) needs a
   restart of the adapter; the gateway pulls the new manifest, the changed tools wait for classification again and a removed one loses it.

### Remove the server

Delete the server in the gateway (`DELETE /admin/v1/servers/mcp-notes`) and the system in the broker (`DELETE /v1/admin/systems/notes`).
Its tools leave every `tools/list` and calls are refused as for an unknown tool. Then stop the container and the foreign server.

## Several instances

Behind a balancer the instances share one address, so they need what one instance keeps in its own memory or state directory: Redis for the
one-time `jti` (`MCP_REDIS_HOST` and the lines under it in `mcp-adapter.env`; without it a token could be used once on each instance), the same
`MCP_PAGING_CURSOR_KEY` (otherwise a cursor from one instance is refused by another) and the same certificate (`MCP_TLS_CERT` and `MCP_TLS_KEY`,
otherwise each instance generates its own and the gateway sees the fingerprint change from one call to the next). Compose runs one instance; Redis is
yours.

## Closed network

Copy the image to your registry and point Compose at it in a `.env` file next to `compose.yaml`:

```bash
MCP_REGISTRY=artifactory.corp.test/mcp
```

## Upgrade from 1.x

Version 2.0.0 does not read `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE` and `MCP_POLICY_STATE_DIR`, and refuses to
start when they are set through `aiwcorpo.mcp.platform.policy.*`. Remove them, together with the policy keys and the `/app/state` volume;
nothing replaces them, because an adapter has no policies. It also does not read `MCP_REGISTRY_URL`, `MCP_REGISTRY_TLS_KEYSTORE`,
`MCP_REGISTRY_SSL_BUNDLE`, `MCP_INTERNAL_URL` and `MCP_TLS_TRUSTSTORE`: the adapter no longer submits its manifest (there is no registry channel and no
certificate `mcp-adapter-<serverId>`), the gateway pulls it from `/.well-known/mcp-manifest`. `MCP_SERVER_ID` no longer has a default and must be
set. The certificate from the internal CA is not needed any more: keep it (`MCP_TLS_CERT`, `MCP_TLS_KEY` or `MCP_TLS_KEYSTORE` with
`MCP_TLS_STORE_PASSWORD`) or let the adapter generate one, and have the operator approve the connection.
