# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub: Security tab of this repository, "Report a vulnerability" (private vulnerability
reporting). Do not open a public issue for a security problem.

Include the affected version (`pom.xml`), the foreign server and its version, the configuration involved (without secrets) and steps to
reproduce. In scope: a request that reaches the foreign server without passing the gateway checks, a credential of one user that reaches
another user's call or a log, a tool result that is not marked as untrusted data, a schema that lets an unbounded value through to the
foreign server, a response of the foreign server that changes what the adapter sends or logs.

## Scope

In scope: the code of this repository (foreign client, schema bounding, tool wrapper, manifest and its submission, configuration).

Out of scope:

- The foreign server itself (for the demo, `sooperset/mcp-atlassian`): report to its maintainers. The adapter limits what a foreign
  server can do to the platform (bounded arguments, untrusted results, one credential per call), not what it does to its own system.
- The gateway, the credential broker, the registry and the policies: separate repositories.
- The `dev` and `devstack` profiles of the starter relax TLS, mTLS and the replay store by design and are refused outside a development stack.
- `MCP_FOREIGN_ALLOW_PLAIN_HTTP=true` sends the credential of the user over plain http; it is for a private network between the adapter and
  its foreign server and nothing else.

## Supported versions

Only the latest released version receives fixes. Published versions are immutable; a fix ships as a new release.
