# Contributing

The adapter follows the contracts of `mcp-contracts` (C5 `submitAdapterManifest`, C6 manifest with `source: adapter`). A change that needs a
different contract starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that repository and needs approval before it is implemented here.

## Setup

JDK 25. Linux and macOS with bash 3.2 or newer; Windows only through WSL2. The `mcp-platform-starter` and `mcp-contracts` artifacts must be
in `~/.m2` or in the registry named by `MCP_MAVEN_REPOSITORY_URL` (see the README, "Build and test").

```bash
ci/run.sh verify     # text checks, then clean verify; the same script runs in CI
```

`ci/run.sh verify` must pass before a merge: it fails when fewer than 90 tests ran or when one was skipped. The test against the real
`mcp-atlassian` image (`-Preal-image`) is run by hand when the foreign client or the schema bounding changes.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the same commit as the code
they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
