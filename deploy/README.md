# Install mcp-server-adapter

The adapter of one third-party MCP server as a container. It runs on any host that the platform can reach; the platform (gateway, broker, portal) is installed separately. One adapter serves one third-party server and one system: for another server copy this directory and change `MCP_SERVER_ID` and the addresses.

## Requirements

- Docker Engine with Compose v2.20 or newer, 1 GiB of memory.
- The domain of the platform, from its operator. This host must reach `https://<platform>` on port 443.
- The platform must reach this host on port 8443.
- The address of the third-party server, on a network that only the adapter shares with it. It receives the credential of the calling user.
- The address of the portal page for connecting an account.

## Install

1. Fill in the empty values in `mcp-adapter.env`:

    ```bash
    MCP_SERVER_ID=mcp-<system>
    MCP_GATEWAY_JWKS_URI=https://<platform>/.well-known/mcp-jwks.json
    MCP_CONNECT_URL=https://<platform>/connections?system={system}
    MCP_FOREIGN_URL=https://<third-party server>
    ```

    `<system>` is the id the system has in the broker: 2 to 31 lowercase letters and digits, starting with a letter.

2. If the third-party server is not on the default Docker network, add the network under `mcp-adapter` and under `networks:` in `compose.yaml` (the comment in the file shows where). The server must not be reachable from the networks of other servers.

3. Start the adapter:

    ```bash
    docker compose up -d
    ```

    The adapter waits up to 90 seconds for the third-party server to answer `tools/list` and exits when it does not.

4. Read the certificate fingerprint:

    ```bash
    docker compose logs mcp-adapter | grep event=tls_certificate
    ```

    Expected: `event=tls_certificate source=generated fingerprint=sha256:...`

5. Send the platform operator the address `https://<this host>:8443/mcp` and the fingerprint.

The adapter generates its certificate on the first start and keeps it in the `state` volume. A new volume means a new certificate and a new approval. `docker compose down -v` deletes the volume.

## Check that it works

```bash
docker compose ps
```

Expected: `mcp-adapter` is `healthy`. Every path except `/actuator/health` answers 401 without a token from the gateway.

## Add the server to the platform

The platform operator does this in the portal, Administration, with a fresh login and OTP:

1. Systems: add the system (the `<system>` of `MCP_SERVER_ID`) with the adapter and credential type that fit the product. For a product that takes the user's own token use adapter `generic-rest`, type `user-secret`, scheme `Bearer`.
2. Servers, Add server: `https://<this host>:8443/mcp`. The gateway reads the certificate and the manifest.
3. Approvals: compare the fingerprint with the one from step 4 and approve the connection.
4. Approve every tool with risk `READ_ONLY` and action `read`. An adapter accepts no other classification, so a tool that changes data stays hidden.
5. In Keycloak, create the group `mcp-<system>-read` and add the users. There is no scope mapping for an adapter.

Each user then stores a credential for the system in the portal, Connections.

To remove the server, delete it and its system in the portal, then stop the container and the third-party server.

## Common problems

| Symptom | Cause | Fix |
| --- | --- | --- |
| The adapter exits right after start | The third-party server did not answer `tools/list` within 90 s, or it refuses requests without `Authorization` | Check `MCP_FOREIGN_URL` and the network; for the second case set `MCP_FOREIGN_DISCOVERY_AUTHORIZATION` |
| Server status `certificate_changed` | The `state` volume was recreated and the adapter generated a new certificate | Approve the new fingerprint in the portal |
| A tool is missing from the list | It is not approved, not `READ_ONLY`, or its schema cannot be bounded | Check the log for `event=foreign_tool_skipped` |
| A tool changed after a new image of the third-party server | The tool list is read at start | Restart the adapter; the changed tools wait for approval again |

## Several instances

Instances behind one address need the same values in `mcp-adapter.env`:

| Setting | Why |
| --- | --- |
| `MCP_REDIS_HOST` and the other `MCP_REDIS_*` | one-time token ids shared between instances |
| `MCP_PAGING_CURSOR_KEY` (`openssl rand -base64 32`) | a cursor from one instance works on another |
| `MCP_TLS_CERT` and `MCP_TLS_KEY` | the gateway sees one fingerprint |

## Your own certificate

Put a PEM pair or a PKCS12 keystore in `certs/` and set `MCP_TLS_CERT` and `MCP_TLS_KEY`, or `MCP_TLS_KEYSTORE` and `MCP_TLS_STORE_PASSWORD`, in `mcp-adapter.env`. A certificate from a CA the gateway trusts, for the host name in the address, is trusted without pinning, and a renewal needs no new approval.

## Closed network

Copy the image to your registry and set it in a `.env` file next to `compose.yaml`:

```bash
MCP_REGISTRY=artifactory.corp.test/mcp
```

## Upgrade from 1.x

Remove `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR`, `MCP_REGISTRY_URL`, `MCP_REGISTRY_TLS_KEYSTORE`, `MCP_REGISTRY_SSL_BUNDLE`, `MCP_INTERNAL_URL` and `MCP_TLS_TRUSTSTORE`, together with the policy keys and the `/app/state` volume. Set `MCP_SERVER_ID`, which no longer has a default. The adapter generates a certificate or uses the one you give it; the operator approves the connection again.
