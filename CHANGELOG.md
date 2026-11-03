# Changelog

Format: wersje SemVer; kolejność od najnowszej.

## 0.1.0 - w przygotowaniu

- Sidecar obcego serwera MCP na `mcp-platform-starter` 3.0.0-beta-14 i kontraktach 6.3.2 (CC-193, CC-206, CC-207, CC-226, CC-227).
- Odczyt `tools/list` obcego serwera przy starcie (z oczekiwaniem), przepisanie schematów na zamknięte i ograniczone, pominięcie narzędzi,
  których nie da się ograniczyć, przedrostek nazw i lista dozwolonych narzędzi.
- Manifest C6 z `source: adapter` budowany z listy narzędzi, którą adapter wystawia; wysyłany operacją `submitAdapterManifest` przez mTLS
  (CN `mcp-adapter-<serverId>`), z ponawianiem.
- `tools/call`: poświadczenie użytkownika w nagłówku w konfigurowalnym formacie (domyślnie `Authorization: Token <PAT>`), sesja na wywołanie,
  walidacja argumentów przed przekazaniem, wynik jako `UntrustedText`, stały `not_found_or_denied`, `X-Request-Id` do obcego serwera.
- Demo: `sooperset/mcp-atlassian` 0.23.1 (MIT), obraz przypięty po digeście, szczegóły w README.
- Tryb odbiorcy `adapter` (CC-226): decyzja lokalna z klasyfikacji administratora w JWT (`mcp_risk`, `mcp_action`), brak klasyfikacji to `401`, wartość inna niż `READ_ONLY` to odmowa
  lokalna (CC-227), rozbieżność z podpowiedziami obcego serwera logowana i liczona. Ryzyko zastępcze tylko dla podpowiedzi w manifeście i hashu.
- Po przeglądzie: jeden termin na całą wymianę z obcym serwerem, higiena opisów argumentów, `enum`/`const` i nazw właściwości (odrzucenie narzędzia), budżet węzłów schematu,
  walidacja `mcp-path`, formatu nagłówka i adresu rejestru, HTTP 401 obcego serwera jako `credential_required`, powód techniczny w logu i metryce, ryzyko zastępcze
  podniesione do `DESTRUCTIVE` dla nazw ze słowem destrukcyjnym, utwardzony kontener obcego serwera w osobnych sieciach, użytkownik 10001, obraz CI po digeście.
- Licencja Apache-2.0, workflow wydania z obrazem na ghcr.io i SBOM CycloneDX, `SECURITY.md`, `CONTRIBUTING.md`.
