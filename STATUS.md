# STATUS: mcp-server-adapter (ws-13)

Stan na 2026-11-03: etap 3 M6 zrobiony po stronie repozytorium, nic nie wdrożone. Wersja 1.0.0, starter 3.0.0, kontrakty 6.3.2.

## Wersja 1.0.0 (2026-11-04)

Wersja projektu `1.0.0`, `mcp-platform.version` 3.0.0, kontrakty 6.3.2. Usunięte pozostałości GitHub Packages: `distributionManagement`, `.ci/settings.xml`, `MCP_MAVEN_REPOSITORY_*` w `ci/run.sh`, workflowach, `.gitlab-ci.yml`, `Jenkinsfile`, README i CONTRIBUTING; job CI ma tylko `contents: read`, bez tokenu. Obraz ma etykiety `org.opencontainers.image.source` i `licenses`. `make ci`: 99 testów, zielone. CHANGELOG: jeden wpis 1.0.0. Obraz `mcp-server-adapter:m6-<sha>` budowany po commicie (tag w raporcie).

## Zrobione

- Sidecar: przyjmuje ruch tylko od gatewaya (mTLS, wewnętrzny JWT, starter), czyta `tools/list` obcego serwera przy starcie, przepisuje schematy na zamknięte i
  ograniczone, publikuje narzędzia przez starter, przekazuje `tools/call` z `Authorization` zbudowanym z `X-MCP-Credential` (format konfigurowalny),
  zwraca wynik jako `UntrustedText`, mapuje błędy obcego serwera na stały `not_found_or_denied` albo `upstream_unavailable`.
- Manifest C6 `source: adapter` z listy narzędzi, którą adapter sam wystawia; `submitAdapterManifest` przez mTLS z ponawianiem.
- Demo na `sooperset/mcp-atlassian` 0.23.1: tag istnieje, `LICENSE` to MIT (sprawdzony w tagu `v0.23.1`), obraz pobrany jeden raz po digeście
  `sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466` (indeks; arm64 `4206f3a9...`).
- Testy: 99 w `ci/run.sh verify` (żaden pominięty) i 2 w profilu `real-image` na prawdziwym obrazie.

## Decyzje i odstępstwa od briefu

- **Klasyfikacja z JWT zamiast ryzyka zastępczego przy decyzji lokalnej** (beta-14, CC-226/CC-227; README, "Klasyfikacja administratora a ryzyko zastępcze"): sidecar działa w trybie odbiorcy `adapter`;
  `tools/call` bez `mcp_risk`/`mcp_action` to `401 classification_missing` (starter); lokalny Cedar `call` dostaje ryzyko i akcję z claimów (`ClassifiedToolRiskRegistry`); wartość inna niż
  `READ_ONLY` jest odmawiana lokalnie (`ClassificationInterceptor`: log, `mcp_adapter_local_denied_total`, obok log/licznik startera); rozbieżność klasyfikacji z podpowiedziami jest logowana i liczona
  (`mcp_adapter_classification_divergence_total`). Ryzyko zastępcze z podpowiedzi zostaje tylko tam, gdzie starter wymaga go przy rejestracji: podpowiedzi w `tools/list`, więc manifest i `definitionHash`,
  oraz `risk` w zdarzeniu `tool_call` adaptera. Hash zależy od niego pośrednio (starter nadpisuje podpowiedzi). Nazwa ze słowem destrukcyjnym jest zawsze `DESTRUCTIVE`; narzędzie, które starter mimo to
  odrzuci, jest pomijane z logiem.
- **Startowy `tools/list` wymaga nagłówka `Authorization`**: mcp-atlassian 0.23.1 odmawia każdego żądania bez niego (`401`), więc jest ustawienie
  `discovery-authorization` (wartość nie jest poświadczeniem niczyim). Narzędzia Jiry pojawiają się w liście tylko, gdy serwer ma w env `JIRA_URL` i
  `JIRA_PERSONAL_TOKEN` (zapychacz `placeholder-never-used`, nieużywany przy wyłączonym `ALLOW_GLOBAL_CRED_FALLBACK`).
- **Starterowy manifest wyłączony** (`PlatformManifestAutoConfiguration` wykluczona): jego wpisy mają `risk` i `action`, a adapter ich nie ma. Brak `GET /mcp-manifest`.
- **Sesja obcego serwera na wywołanie**: cztery żądania HTTP zamiast jednego, w zamian żadna sesja ani token nie są współdzielone.
- Błędy zgłoszone przez obcy serwer (wynik `isError`, błąd JSON-RPC, 403, 404) to ten sam `not_found_or_denied`; HTTP 401 transportowe to `credential_required`. Powód techniczny
  (`result_error`, `http_5xx`, `timeout` i inne z zamkniętej listy) jest w logu `event=foreign_call_failed` i w liczniku `mcp_adapter_foreign_failures_total{reason}`, bez treści z obcego serwera.
  Ryzyko rezydualne (błąd zwrócony jako zwykły wynik) opisane w README.
- Narzędzia z otwartą mapą w schemacie (`jira_update_issue.fields`, `jira_create_issue.additional_fields`) nie da się opublikować; poza demo.

## Czego nie zrobiono

- Nie odświeżam manifestu w czasie działania (zmiana listy obcego serwera wymaga restartu).
- Nie sprawdzałem mTLS adaptera do rejestru przeciw prawdziwemu gatewayowi (testy używają atrapy rejestru po http); to scenariusz ws-01 i ws-10.
- Workflow wydania i obraz na ghcr.io nie były uruchamiane (brak repozytorium); nic nie wypchnięto.
- Fragment `deploy/docker-compose.demo.yml` nie był uruchomiony na stosie.

## Czego potrzebuje ws-01 (mcp-platform-dev)

1. **Certyfikat klienta** z CN dokładnie `mcp-adapter-mcp-jira` (EKU clientAuth, CA wewnętrzny stosu) w PKCS12 dla adaptera (`MCP_REGISTRY_TLS_KEYSTORE`, hasło jak
   `MCP_TLS_STORE_PASSWORD`). Osobny od certyfikatu serwera `mcp-jira.internal`. Nasłuch mTLS gatewaya (C5) musi ufać CA i wpuszczać ten CN do `submitAdapterManifest`.
   SAN certyfikatu gatewaya musi zawierać nazwę z `MCP_REGISTRY_URL` (adapter sprawdza nazwę hosta).
2. **Konfiguracja gatewaya**: `mcp-jira` w trybie adaptera (gateway wtedy dokłada `mcp_risk` i `mcp_action` do każdego `tools/call`; adapter bez nich odmawia, serwer natywny z nimi też) (`adapter_mode` per serverId, CC-206); natywny kontener `mcp-jira` i adapter nie mogą działać razem
   (ten sam serverId i alias `mcp-jira.internal`). Bez tej konfiguracji rejestr odpowiada `422 adapter_mode_mismatch`, a adapter ponawia co 60 s.
3. **Compose**: fragment `deploy/docker-compose.demo.yml` (obraz `ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64...`, już pobrany lokalnie, `pull_policy: never`; kontener
   adaptera `mcp-server-adapter:m6-<sha7>` z raportu). Dwie nowe sieci `internal`: `adapter-foreign` (adapter i mcp-atlassian) oraz `jira-foreign` (mcp-atlassian i **tylko** mock Jiry; fragment
   dopisuje mockowi `jira-foreign` z aliasem `jira.internal`). mcp-atlassian nie może być w `servers` ani w `core`. Kontener obcego serwera ma `read_only`, `cap_drop: [ALL]`,
   `no-new-privileges`, `tmpfs /tmp`, `mem_limit 512m`, `pids_limit 200` (sprawdzone na obrazie; healthcheck na Pythonie z obrazu działa). Obraz adaptera działa jako uid/gid 10001:
   wolumen `/app/state` musi być dla niego zapisywalny (krok init stosu, osobna linia dla tego wolumenu, bo natywne serwery mają inny uid). Zmienne środowiska jak w natywnym `mcp-jira` plus te z tabeli w README. Healthcheck adaptera: `bash -c "exec 3<>/dev/tcp/127.0.0.1/8443"` (domyślne `/bin/sh` obrazu to dash bez `/dev/tcp`; sprawdzone w jednorazowym kontenerze `--network none` z nasłuchującą klasą Javy: z `bash` przechodzi, bez nasłuchu nie). Mock Jiry jest dla mcp-atlassian osiągalny na całym porcie, także WireMock `/__admin` (basic auth).
4. **Mock Jiry**: używa `/rest/api/2/myself`, `issue/{key}`, `issue/{key}/comment` (GET i POST), `/field`, `search`, `project`, `transitions`. Mock ma `myself`, `issue`, `search`, `project`, POST
   `comment` i `transitions`; GET `comment` i `/field` dostają 404 (z fałszywą Jirą `get_issue` mimo to działał). `Authorization: Bearer <pat>` zgadza się z mockiem.
5. **Rejestr i polityki** (lokalna decyzja adaptera idzie z klasyfikacji w JWT, patrz README): po starcie 4 wpisy `pending_approval` z `source: adapter`; administrator klasyfikuje je w portalu (wszystkie cztery: `read`; od kontraktów 6.3.0 / CC-227 rejestr dopuszcza dla adaptera tylko `READ_ONLY`). Grupy Cedar jak dla Jiry (`mcp-jira-read`, `-write`); nazwy narzędzi bez przedrostka `jira_`.
6. **Typ poświadczenia** `user-secret` dla `jira` w brokerze (PAT w `X-MCP-Credential`); adapter zamienia go na `Token <PAT>`.

## Czego potrzebuje ws-10 (mcp-e2e)

- Scenariusz od zera: start adaptera, wpisy `pending_approval`/`source: adapter`, wywołanie przed klasyfikacją (`tool_disabled`), klasyfikacja przez portal, wywołanie jako alice i bob
  (każdy z własnym PAT; w mocku `Seen as` nie ma, więc rozróżnić po `myself` albo po dzienniku mocka), wywołanie z PAT-em, który Jira odrzuca (`not_found_or_denied`), audyt z `requestId`.
- Zmiana definicji obcego serwera (inna wersja obrazu albo `ENABLED_TOOLS`) i restart adaptera: wpis wraca do oczekujących, narzędzie usunięte znika wraz z klasyfikacją (CC-207).
- Próba obejścia: żądanie bezpośrednio do adaptera bez certyfikatu `mcp-gateway`/JWT (`401`) i bezpośrednio do mcp-atlassian z sieci `servers` (brak trasy).
- Atak na schemat: argument za długi, nieznana właściwość, `confirmation_code`; wynik z tekstem w rodzaju polecenia (musi wyjść jako `untrusted-data`).
- Sprawdzenie `tools/list` adaptera względem hashy w rejestrze po restarcie obcego serwera.
