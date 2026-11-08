# Contributing

The adapter follows the contracts of `mcp-contracts` (C14 `GET /.well-known/mcp-manifest`, C6 manifest with `source: adapter`). A change that needs a
different contract starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that repository and needs approval before it is implemented here.

## Setup

JDK 25. The `mcp-platform-starter` and `mcp-contracts` artifacts must be
in `~/.m2` or reachable on Maven Central (see the README, "Build and test").

```bash
./mvnw -B verify     # the same command runs in CI
```

`./mvnw -B verify` must pass before a merge. The test against the real
`mcp-atlassian` image (`-Preal-image`) is run by hand when the foreign client or the schema bounding changes.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the same commit as the code
they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
