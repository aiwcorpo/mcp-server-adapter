# mcp-server-adapter

Part of [MCP Platform](https://github.com/aiwcorpo/mcp-platform), a self-hosted MCP gateway for companies. Documentation: [mcp.aiwcorpo.pl](https://mcp.aiwcorpo.pl/).

A sidecar that puts a third-party MCP server, one whose code you do not control, behind the aiwcorpo MCP gateway. For platform operators who want to connect a ready-made open source or vendor server.

The adapter reads the tool list of the third-party server, serves it to the gateway as a manifest and forwards each call with the credential of the user who made it. One adapter serves one server and one system.

## Quick start

```bash
cd deploy
# fill in MCP_SERVER_ID, MCP_GATEWAY_JWKS_URI, MCP_CONNECT_URL and MCP_FOREIGN_URL in mcp-adapter.env
docker compose up -d
docker compose logs mcp-adapter | grep event=tls_certificate
```

Then the platform operator adds `https://<this host>:8443/mcp` in the portal. Full steps: [deploy/README.md](deploy/README.md).

## What to expect

- Only tools classified `READ_ONLY` work. A tool that changes data stays hidden, because tools of a third-party server name no resource a write could be scoped to.
- The server gets the credential of the calling user, never a shared one. Keep it on a network that only the adapter shares with it.
- Tools whose input schema cannot be bounded (open maps, unions of two types, recursion, lists without `items`, depth above 8) are skipped. The log line `event=foreign_tool_skipped` gives the reason.
- Every error of the server reaches the model as `not_found_or_denied`, except `credential_required` (HTTP 401) and `upstream_unavailable`. Check how the server reports errors first: an error returned as ordinary text reaches the model unchanged.
- A change in the tool list needs a restart of the adapter. The gateway pulls the new manifest within a minute and the changed tools wait for classification again.

## Configuration

Required, in `deploy/mcp-adapter.env`:

| Variable | Description |
| --- | --- |
| `MCP_SERVER_ID` | `mcp-<system>`, where `<system>` is the system id in the broker (2 to 31 lowercase letters and digits, starting with a letter) |
| `MCP_GATEWAY_JWKS_URI` | key set of the gateway: `https://<platform>/.well-known/mcp-jwks.json` |
| `MCP_CONNECT_URL` | portal page where a user connects an account: `https://<platform>/connections?system={system}` |
| `MCP_FOREIGN_URL` | address of the third-party server, `https` |

Often needed:

| Variable | Description |
| --- | --- |
| `MCP_FOREIGN_PATH` | MCP path of the server, default `/mcp` |
| `MCP_FOREIGN_AUTHORIZATION_FORMAT` | how the credential becomes a header, default `Token {credential}`; `{credential}` must appear exactly once |
| `MCP_FOREIGN_DISCOVERY_AUTHORIZATION` | header for the startup `tools/list`, for servers that refuse a request without one |
| `MCP_ADAPTER_TOOLS_INCLUDE` | tools to publish, comma-separated; empty means all that can be bounded |
| `MCP_ADAPTER_STRIP_PREFIX` | prefix to remove from tool names |

Own certificate, Redis for several instances and limits are described in the same file and in `src/main/resources/application.yml`.

## Run a third-party MCP server behind the adapter

The example puts [sooperset/mcp-atlassian](https://github.com/sooperset/mcp-atlassian) 0.23.1 (MIT) in front of Jira with four read tools. It runs on a test stack: the third-party server speaks plain `http`, which the adapter accepts only with the `devstack` profile. The Compose fragment with all settings is `deploy/docker-compose.demo.yml`.

1. Pull the image, pinned by digest:

    ```bash
    docker pull ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466
    ```

2. Merge `deploy/docker-compose.demo.yml` into your test stack. Set `ADAPTER_IMAGE` (`mcp-server-adapter:<tag>`) and `MCP_GATEWAY_JWKS_URI`. If the stack already runs a native `mcp-jira`, stop it: the adapter takes its server id.

3. Keep these settings of the third-party server (they are in the fragment):

    ```yaml
    ENABLED_TOOLS: jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions
    JIRA_PERSONAL_TOKEN: placeholder-never-used
    ALLOW_GLOBAL_CRED_FALLBACK: "false"
    ```

    `ENABLED_TOOLS` lists read tools only. The token is a filler: without it the server does not list the Jira tools. With the fallback off, a call without a credential ends with 401.

4. Keep these settings of the adapter:

    ```yaml
    MCP_FOREIGN_URL: http://mcp-atlassian:8000
    MCP_FOREIGN_ALLOW_PLAIN_HTTP: "true"
    MCP_FOREIGN_AUTHORIZATION_FORMAT: "Token {credential}"
    MCP_FOREIGN_DISCOVERY_AUTHORIZATION: "Token discovery-only"
    MCP_ADAPTER_STRIP_PREFIX: jira_
    ```

    The discovery header is needed because the server refuses `initialize` without `Authorization`. The adapter sends `Token <PAT>` and the server passes it to Jira as `Bearer <PAT>`.

5. Start the stack:

    ```bash
    docker compose up -d
    ```

    The adapter waits up to 90 seconds for the third-party server to answer `tools/list`.

6. In the portal, add the server `http://mcp-jira.internal:8443/mcp` with "allow plain http" (the network alias from the fragment), approve the connection and approve the tools. They are `get_issue`, `search`, `get_all_projects` and `get_transitions`, all read-only.

The third-party container is the least trusted one in the stack. The fragment runs it read-only, without capabilities and with memory limits, on two internal networks (one shared with the adapter, one with Jira), so it cannot see other servers.

## Build

```bash
export JAVA_HOME=<path to JDK 25>
./mvnw -B verify
```

The jar is `target/mcp-server-adapter.jar`; `docker build -t mcp-server-adapter:dev .` builds the image. The tests need no Docker. Working on the code: [CONTRIBUTING.md](CONTRIBUTING.md).

## More

- [deploy/README.md](deploy/README.md): installation, adding the server to the platform, upgrade from 1.x
- [CHANGELOG.md](CHANGELOG.md), [SECURITY.md](SECURITY.md)

Apache License 2.0.
