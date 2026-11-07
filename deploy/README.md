# Install mcp-server-adapter

The sidecar of one foreign MCP server as a container (Docker Compose). It runs behind the MCP gateway; the gateway, the credential
broker, the identity provider and the foreign server itself are installed elsewhere. The image is built from the `Dockerfile` in
the repository root and published as `ghcr.io/aiwcorpo/mcp-server-adapter:<version>`.

```
deploy/
|-- compose.yaml                 the adapter as a container (read-only, no capabilities)
|-- mcp-adapter.env              settings for Compose: fill in the empty values
|-- certs/                       keystore.p12, registry-client.p12 and truststore.p12 go here (never committed)
`-- docker-compose.demo.yml      fragment for the dev stack: the adapter with sooperset/mcp-atlassian 0.23.1 for Jira
```

One adapter serves one foreign server and one system. The system id is not a list built into the adapter: any id that matches the
contracts (`^[a-z][a-z0-9]{1,30}$`, so `notes`, `jira`, `wiki2`) works, and the server id is `mcp-<system>`. For another foreign server copy
this directory and change `MCP_SERVER_ID` and the addresses.

## Prerequisites

- Docker Engine with Compose v2.20 or newer, 1 GiB of memory.
- From your internal CA, two certificates:
  - the server certificate: CN `mcp-<system>` (the gateway takes the server id from it), SAN `mcp-<system>.internal` (the gateway
    checks this name), extended key usage `serverAuth` and `clientAuth` (the gateway pins every server certificate to both usages);
  - the registry certificate: CN `mcp-adapter-mcp-<system>` (compared as a whole, for this one server only; the gateway answers `403` to
    any other CN), extended key usage `clientAuth`. The adapter submits its manifest with it. It is a different certificate from the
    server one.

  Also the CA certificate.
- The addresses of the gateway (internal TLS port; its certificate must carry the host name you use), of the foreign server and of the
  portal page for connecting an account.
- Redis: `compose.yaml` starts one next to the server (service `redis`, reachable from the server only, default user off, one ACL account that may run `SET` on its own `jti:` keys). `MCP_REDIS_PASSWORD` in the env file is its password: choose one (`openssl rand -hex 24`). For one Redis shared by several instances, remove the service and set `MCP_REDIS_HOST` in the compose file.
- The foreign server on a network that only the adapter shares with it. It gets the credential of the calling user, so it must not
  be reachable from the networks of other servers or of Redis.

## Install

1. Copy this directory to the host.
2. Put three files in `certs/`:

    | File | What |
    | --- | --- |
    | `keystore.p12` | the server certificate and its key |
    | `registry-client.p12` | the registry certificate and its key |
    | `truststore.p12` | the internal CA certificate |

    All three use one password. From PEM files (here for the system `notes`):

    ```bash
    openssl pkcs12 -export -inkey mcp-notes.key -in mcp-notes.crt -certfile ca.crt -name mcp-notes -out certs/keystore.p12
    openssl pkcs12 -export -inkey adapter-notes.key -in adapter-notes.crt -certfile ca.crt -name registry -out certs/registry-client.p12
    keytool -importcert -noprompt -alias ca -file ca.crt -keystore certs/truststore.p12 -storetype PKCS12
    ```

3. Fill in the empty values in `mcp-adapter.env`.
4. Start:

    ```bash
    docker compose up -d
    ```

The adapter waits up to 90 s for the foreign server to answer `tools/list` and exits when it does not. Until the platform knows the
server, the adapter keeps submitting its manifest (a pause that grows to 60 s) and logs `event=manifest_rejected`; this is the state the
next section ends, and nothing needs a restart.

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

2. **Server in the gateway, with `adapter` set to true.** The flag is given at creation and never changes (to turn a native server into an
   adapter or the other way, delete the server and add it again). The address is the internal HTTPS URL of this container:

    ```bash
    curl --cert bff.crt --key bff.key --cacert ca.crt -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
      https://mcp-gateway.corp.internal:8443/admin/v1/servers -d '{"serverId":"mcp-notes","system":"notes","adapter":true,
      "internalUrl":"https://mcp-notes.corp.internal:8443/mcp","credential":{"required":true,"acceptedTypes":["user-secret"],"type":"user-secret"}}'
    ```

    An adapter whose server is not in the registry yet, or is there without the flag, gets `403` or `422 adapter_mode_mismatch` and
    retries, so the order of steps 2 and 3 does not matter.
3. **Deploy** the adapter as above (`docker compose up -d`). It submits its manifest itself on the adapter channel
   (`PUT /admin/v1/registry/adapters/mcp-notes`, certificate `mcp-adapter-mcp-notes`); there is no pipeline step and no manifest to
   register by hand. The metric `mcp_adapter_manifest_submitted` is 1 once the registry has stored it.
4. **Classify the tools.** Every tool is `pending_approval` and hidden (`tool_disabled`) until an administrator classifies it with the
   approval (`POST /admin/v1/registry/servers/mcp-notes/tools/{tool}/approve` with `risk` and `action`, or Administration, Servers in the
   portal). The only classification an adapter takes is `READ_ONLY` with `read`; a tool that changes data stays unclassified and hidden.
   There are no policies to approve and no resource types: an adapter has neither, and decides nothing about resources.
5. **Groups.** The identity provider needs the group `mcp-notes-read`, which the gateway derives from the risk of a tool. There is no
   scope mapping for an adapter. A change in the foreign server's tool list (another image, another `MCP_ADAPTER_TOOLS_INCLUDE`) needs a
   restart of the adapter; the changed tools wait for classification again and a removed one loses it.

### Remove the server

Delete the server in the gateway (`DELETE /admin/v1/servers/mcp-notes`) and the system in the broker (`DELETE /v1/admin/systems/notes`).
Its tools leave every `tools/list` and calls are refused as for an unknown tool. Then stop the container and the foreign server.

## Closed network

Copy the image to your registry and point Compose at it in a `.env` file next to `compose.yaml`:

```bash
MCP_REGISTRY=artifactory.corp.test/mcp
```

## Upgrade from 1.x

Version 2.0.0 does not read `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE` and `MCP_POLICY_STATE_DIR`, and refuses to
start when they are set through `aiwcorpo.mcp.platform.policy.*`. Remove them, together with the policy keys and the `/app/state` volume;
nothing replaces them, because an adapter has no policies and needs no policy data. `MCP_SERVER_ID` no longer has a default and must be
set. Renew the server certificate if it has no `clientAuth` usage. The adapter registers on the adapter channel as before.
