#!/usr/bin/env bash
# Single CI entry point: every CI system, the container image and a developer's shell call this script and nothing else.
# .gitlab-ci.yml, Jenkinsfile and .github/workflows/*.yml are thin wrappers around it. Needs bash 3.2 or newer (Linux, macOS;
# Windows through WSL2), git, perl, JDK 25 and either a filled ~/.m2 with the platform artifacts (mcp-platform-starter, mcp-contracts)
# or MCP_MAVEN_REPOSITORY_URL (+ _USER, _PASSWORD) pointing at the registry that holds them.
#
#   ci/run.sh                 verify (default)
#   ci/run.sh verify          hygiene checks, then clean verify; fails when the build fails, when no test ran or when one was skipped
#   ci/run.sh hygiene         only the text checks of the sources (non-ASCII quotes and spaces, tool traces)
#   ci/run.sh image           docker build of the jar that verify made, tagged mcp-server-adapter:<prefix>-<sha7>
#                             (CI_IMAGE_TAG_PREFIX, default m6); needs a Docker daemon; never pushes
#
# The tests need no container runtime: the foreign server and the registry are stubs inside the test JVM.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

java_major() {
  "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p' | head -n 1
}

jdk_is_25() {
  local major
  [[ -x "$1/bin/java" ]] || return 1
  major="$(java_major "$1/bin/java" 2>/dev/null || true)"
  [[ -n "$major" ]] && [[ "${major%%.*}" -ge 25 ]] 2>/dev/null
}

# The build needs Java 25. Keeps the current JDK when it is new enough, otherwise looks in the usual places (macOS
# java_home, Homebrew, /usr/lib/jvm, SDKMAN, IntelliJ); JAVA_HOME set to a JDK 25 skips the search.
select_jdk() {
  local candidate
  if [[ -n "${JAVA_HOME:-}" ]] && jdk_is_25 "$JAVA_HOME"; then
    return 0
  fi
  if command -v java >/dev/null 2>&1; then
    candidate="$(dirname "$(dirname "$(command -v java)")")"
    if jdk_is_25 "$candidate"; then
      export JAVA_HOME="$candidate"
      return 0
    fi
  fi
  candidate=""
  if [[ -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
  fi
  if [[ -n "$candidate" ]] && jdk_is_25 "$candidate"; then
    export JAVA_HOME="$candidate"
    return 0
  fi
  if command -v brew >/dev/null 2>&1; then
    candidate="$(brew --prefix openjdk@25 2>/dev/null || true)"
    for candidate in "$candidate/libexec/openjdk.jdk/Contents/Home" "$candidate/libexec"; do
      if jdk_is_25 "$candidate"; then
        export JAVA_HOME="$candidate"
        return 0
      fi
    done
  fi
  for candidate in /usr/lib/jvm/*25* "$HOME"/.sdkman/candidates/java/25* "$HOME"/.jdks/*25*; do
    if jdk_is_25 "$candidate"; then
      export JAVA_HOME="$candidate"
      return 0
    fi
  done
  echo "ci/run.sh: JDK 25 not found; set JAVA_HOME to a JDK 25 or use the maven:3.9.12-eclipse-temurin-25 image" >&2
  return 1
}

select_jdk

repo="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"

mvnw() {
  local args=(-B -Dmaven.repo.local="$repo")
  if [[ -n "${MCP_MAVEN_REPOSITORY_URL:-}" ]]; then
    args+=(-s .ci/settings.xml)
  fi
  ./mvnw "${args[@]}" "$@"
}

project_version() {
  mvnw -q -N org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n 1
}

# Tracked and untracked-but-not-ignored sources; .m2/ (a cache inside the workspace) is ignored by git and left out by name too.
source_files() {
  git ls-files -z --cached --others --exclude-standard -- '*.java' '*.md' '*.xml' '*.yml' '*.yaml' '*.properties' '*.json' '*.sh' \
    ':!:ci/run.sh' ':!:.m2/**'
}

# The two checks of the house style: characters that give away generated text, and phrases left by tools. The file list is
# read once into an array, so no check runs on a chunk of it and a failing tool is told apart from "found" (grep: 0 found, 1 none, 2 error).
hygiene() {
  local failed=0 status files=() file
  while IFS= read -r -d '' file; do
    files+=("$file")
  done < <(source_files)
  if [[ "${#files[@]}" -eq 0 ]]; then
    echo "ci/run.sh: no source files to check" >&2
    return 1
  fi
  status=0
  perl -CSD -e 'my $bad = 0; while (<<>>) { if (/[\x{00A0}\x{200B}\x{202F}\x{FEFF}\x{2018}\x{2019}\x{201C}\x{201D}\x{2026}]/) { print "$ARGV:$.: $_"; $bad = 1 } } continue { close ARGV if eof } exit($bad);' -- "${files[@]}" || status=$?
  if [[ "$status" -eq 1 ]]; then
    echo "ci/run.sh: non-ASCII quotes, spaces or ellipsis found (above)" >&2
    failed=1
  elif [[ "$status" -ne 0 ]]; then
    echo "ci/run.sh: the text check itself failed ($status)" >&2
    return 1
  fi
  status=0
  grep -niE 'as an ai|generated with|co-authored-by: claude|lorem ipsum|your-api-key|example\.com' -- "${files[@]}" || status=$?
  if [[ "$status" -eq 0 ]]; then
    echo "ci/run.sh: tool traces or placeholders found (above)" >&2
    failed=1
  elif [[ "$status" -ne 1 ]]; then
    echo "ci/run.sh: the phrase check itself failed ($status)" >&2
    return 1
  fi
  return "$failed"
}

# A build that ran no tests proves nothing (a skipped module, a broken surefire setup, every test skipped).
tests_ran() {
  cat target/surefire-reports/TEST-*.xml 2>/dev/null | grep -c '<testcase ' || true
}

tests_skipped() {
  cat target/surefire-reports/TEST-*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | grep -o ' skipped="[0-9]*"' \
    | tr -dc '0-9\n' | awk '{ n += $1 } END { print n + 0 }'
}

verify() {
  hygiene
  mvnw clean verify
  local ran skipped
  ran="$(tests_ran)"
  skipped="$(tests_skipped)"
  if [[ "${ran:-0}" -lt "${CI_MIN_TESTS:-60}" ]]; then
    echo "ci/run.sh: $ran test cases ran; at least ${CI_MIN_TESTS:-60} are required" >&2
    return 1
  fi
  if [[ "$skipped" -ne 0 ]]; then
    echo "ci/run.sh: $skipped tests were skipped" >&2
    return 1
  fi
  echo "ci/run.sh: $ran test cases ran, none skipped"
}

image() {
  if [[ ! -f target/mcp-server-adapter.jar ]]; then
    echo "ci/run.sh image: target/mcp-server-adapter.jar is missing; run ci/run.sh verify first" >&2
    return 1
  fi
  local tag="mcp-server-adapter:${CI_IMAGE_TAG_PREFIX:-m6}-$(git rev-parse --short=7 HEAD)"
  docker build -t "$tag" .
  echo "ci/run.sh: built $tag"
}

case "${1-verify}" in
  verify) verify ;;
  hygiene) hygiene ;;
  image) image ;;
  *) echo "usage: ci/run.sh [verify|hygiene|image]" >&2; exit 2 ;;
esac
