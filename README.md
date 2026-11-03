# mcp-server-adapter: serwer MCP spoza firmy za gatewayem platformy

Sidecar w Javie, który stawia obcy serwer MCP (taki, którego kodu platforma nie kontroluje) za gatewayem `mcp-gateway`.
Obcy serwer nie wie nic o platformie: nie weryfikuje wewnętrznego JWT, nie zna polityk i nie dostaje poświadczenia w formie, którą
zna platforma. Adapter robi to za niego. Przyjmuje ruch wyłącznie od gatewaya (mTLS i wewnętrzny JWT, jak każdy serwer na starterze),
przy starcie odczytuje listę narzędzi obcego serwera i wysyła ją do rejestru jako manifest, a wywołania przekazuje z poświadczeniem
użytkownika, który je zainicjował.

Stos: Java 25, Spring Boot 4.1.0, `mcp-platform-starter` 3.0.0-beta-13, kontrakty `mcp-contracts` 6.2.1 (CC-193, CC-206, CC-207).
Licencja Apache-2.0. Wersja: 0.1.0-SNAPSHOT (stan i plan w `STATUS.md`).

## Dla kogo

Dla operatora platformy, który chce podłączyć gotowy serwer MCP (open source albo od dostawcy) do jednego z pięciu znanych systemów
(`jira`, `confluence`, `gitlab`, `servicedesk`, `opensearch`). Nie jest biblioteką ani szablonem: to jeden obraz, konfigurowany
zmiennymi środowiska. Serwer na nowym systemie (szósty `SystemId`) wymaga zmiany kontraktów i decyzji człowieka po M6.

## Jak to działa

```
klient MCP -> gateway --mTLS + JWT C2 + X-MCP-Credential--> adapter --HTTP + Authorization: Token <PAT>--> obcy serwer MCP -> system
                 ^                                              |
                 +---- PUT /admin/v1/registry/adapters/{id} ----+   (przy starcie, certyfikat mcp-adapter-<serverId>)
```

1. **Start.** Adapter czeka (do `startup-wait`, domyślnie 90 s) aż obcy serwer odpowie na `tools/list`. Każdemu narzędziu przepisuje
   `inputSchema` na schemat zamknięty i ograniczony (niżej), odrzuca te, których nie umie ograniczyć, i publikuje resztę przez starter.
2. **Manifest.** Z listy narzędzi, którą sam wystawia, buduje manifest C6 z `source: adapter`: wpisy bez `risk` i `action`, z pustym
   `resourceRefs`, z `definitionHash` z biblioteki kontraktów (bez klasyfikacji). Sprawdza go schematem i regułami semantycznymi
   rejestru; manifest, który rejestr by odrzucił, zatrzymuje start. Potem wysyła go operacją `submitAdapterManifest`
   (`PUT /admin/v1/registry/adapters/{serverId}`) z certyfikatem klienta o CN `mcp-adapter-<serverId>` i ponawia z rosnącą przerwą,
   dopóki rejestr nie odpowie `200`. Wywołanie niczego nie zatwierdza: każde narzędzie jest `pending_approval`, a administrator
   nadaje `risk` i `action` przy zatwierdzaniu w portalu. Niesklasyfikowane narzędzie gateway ukrywa i odmawia jako `tool_disabled`.
3. **Wywołanie.** Starter sprawdza wywołującego (certyfikat gatewaya, JWT związany z żądaniem i z nazwą narzędzia), waliduje
   argumenty względem opublikowanego schematu i uruchamia lokalną kontrolę polityki. Dopiero potem adapter otwiera sesję MCP do
   obcego serwera z nagłówkiem `Authorization` zbudowanym z poświadczenia użytkownika (`X-MCP-Credential`), przekazuje argumenty i
   `X-Request-Id` żądania, zamyka sesję i oddaje wynik jako dane niezaufane.

### Co adapter gwarantuje

- **Tylko gateway.** Żądanie bez certyfikatu `mcp-gateway` i ważnego wewnętrznego JWT to `401`; `/actuator/health` jest jedyną ścieżką bez JWT.
- **Poświadczenie użytkownika, nigdy własne.** Sesja do obcego serwera jest jednorazowa i należy do jednego wywołania, więc żaden
  użytkownik nie dostaje cudzej sesji ani cudzego tokenu. Adapter nie trzyma żadnego sekretu (poza kluczami TLS) i nie loguje ani nie
  kopiuje do błędów niczego, co obcy serwer odpowiedział. Przekierowania HTTP nie są śledzone, żeby nagłówek nie trafił pod inny adres.
- **Walidacja przed przekazaniem.** Argument spoza opublikowanego schematu (nieznana właściwość, za długi tekst, liczba poza zakresem)
  to `invalid_argument` bez kontaktu z obcym serwerem.
- **Wyniki jako dane niezaufane.** Wynik to obiekt `UntrustedText` (`kind: untrusted-data`, `format: text/plain`, `text`, `truncated`),
  znormalizowany (bez znaków sterujących, bidi i zerowej szerokości) i obcięty do `result-max-chars`. Treści inne niż tekst są
  odrzucane i policzone w `omittedContentItems`.
- **Stały `not_found_or_denied`.** Błąd, który obcy serwer zgłasza (wynik z `isError`, błąd JSON-RPC, HTTP 403 i 404), daje zawsze ten
  sam komunikat, bo adapter nie odróżni braku zasobu od odmowy z tego, co serwer mówi (CC-146). Wyjątek: HTTP 401 na poziomie transportu
  znaczy, że serwer nie przyjął nagłówka z poświadczeniem użytkownika, więc użytkownik dostaje `credential_required` z linkiem do ponownego połączenia
  konta (tak jak w serwerach natywnych; nie zdradza to istnienia zasobu). Serwer nieosiągalny, wolny albo odpowiadający bez sensu to
  `upstream_unavailable`; za duża odpowiedź to `internal_error`.
- **Powód techniczny w logu i w metryce, nie w odpowiedzi.** Klient widzi jedną z trzech stałych odpowiedzi, więc operator dostaje różnicę osobno:
  linia `event=foreign_call_failed tool=... reason=... status=... requestId=...` (powód z zamkniętej listy: `result_error`, `jsonrpc_error`, `http_401`,
  `http_403`, `http_404`, `http_5xx`, `http_4xx`, `http_redirect`, `unreachable`, `timeout`, `oversize`, `malformed`, `credential_rejected`; nigdy słowo
  obcego serwera ani poświadczenie) i licznik `mcp_adapter_foreign_failures_total{reason}`. Wzrost `http_5xx`, `unreachable` albo `timeout` odróżnia awarię
  od zwykłych odmów.
- **Jeden termin na całą wymianę.** `request-timeout` (30 s) obejmuje nagłówki i ciało. Strumień zdarzeń jest czytany tylko do odpowiedzi na dane żądanie i
  zamykany; serwer, który otworzy strumień i nic nie odpowie albo sączy bajty, kończy się `upstream_unavailable` (`timeout`) w terminie, a nie wisi.
- **Audyt z `requestId`.** Starter zapisuje jedno zdarzenie C8 `tool_call` na wywołanie (z `requestId` gatewaya, bez argumentów i wyników);
  ten sam `requestId` idzie do obcego serwera jako `X-Request-Id`.

### Schematy zamknięte i ograniczone

Obcy serwer nie pisze schematów pod kontrakt (CC-078, CC-123), więc adapter publikuje własny schemat: whitelista słów kluczowych,
`additionalProperties: false` na każdym poziomie, `maxLength` na każdym tekście (domyślnie 4096, dla `body`, `comment`, `description`
i `content` 32768; mniejszy limit z obcego schematu zostaje), `maxItems` na każdej liście (100), `minimum` i `maximum` na każdej liczbie.
Z wartości opcjonalnych (`anyOf` z `null`) zostaje typ właściwy, `default` jest usuwany, lokalne `$ref` są rozwijane. Narzędzie jest
**pomijane** (z linią `event=foreign_tool_skipped` i powodem), gdy jego schematu nie da się ograniczyć: otwarta mapa (`fields: dict`),
unia dwóch typów, rekurencja, lista bez `items`, głębokość powyżej 8, nazwa niezgodna z kontraktem (snake_case, bez `__`) albo
argument `confirmation_code`, sprzeczny zakres liczby (np. `minimum` większy od skonfigurowanego maksimum, zamiast po cichu szerszego), schemat
rozwijający się ponad `schema-max-nodes` (2000 węzłów, ochrona przed wykładniczym rozwinięciem wspólnych `$ref`) albo tekst z ukrytymi znakami (niżej).
Wzorce (`pattern`) bez kotwic `^...$` albo niekompilujące się są pomijane, tak samo jak `format`, `multipleOf` i `exclusive*`; obcy serwer i tak
sprawdza je sam, więc schemat adaptera jest w tych miejscach szerszy niż jego własny.

Tekst obcego serwera, który trafia do modelu, ma higienę: opis narzędzia jest oczyszczany (znaki zakazane zamieniane na spację) i obcinany do 2000 znaków;
opis każdego argumentu, napisy w `enum` i `const` oraz nazwy właściwości są **odrzucane**, nie poprawiane: znak sterujący, bidi, tag (U+E0000...) albo
zerowej szerokości, tekst w `enum` dłuższy niż 256 znaków albo nazwa spoza `^[A-Za-z0-9_.-]{1,64}$` oznacza pominięcie całego narzędzia (opis
argumentu dłuższy niż 1000 znaków jest obcinany). Rejestr przypina całość hashem, więc administrator zatwierdza słowa, które model przeczyta, i widzi je w całości.

### Ryzyko lokalne a klasyfikacja administratora

Starter wymaga ryzyka każdego narzędzia (do audytu, do podpowiedzi `readOnlyHint` i `destructiveHint` w `tools/list` oraz do lokalnej kontroli polityki
`call`), a adapter nie klasyfikuje. Dlatego liczy ryzyko zastępcze z podpowiedzi obcego serwera, w jedną stronę błędu (niepewne to `DESTRUCTIVE`):
`readOnlyHint: true` daje `READ_ONLY`, jawne `destructiveHint: false` daje `UPDATE`, wszystko inne (brak podpowiedzi, `destructiveHint: true`) daje
`DESTRUCTIVE`. Nazwa ze słowem destrukcyjnym (`delete`, `remove`, `reset`, `clear`, `revoke` i inne z listy startera) jest zawsze `DESTRUCTIVE`, bez względu na
podpowiedzi. Gdyby starter mimo to odmówił rejestracji jednego narzędzia, adapter pomija je z logiem i startuje z resztą, zamiast
zatrzymywać się przez słowo obcego serwera.

To ryzyko **niczego nie rozstrzyga**: gateway autoryzuje według klasyfikacji administratora z rejestru i nigdy nie czyta podpowiedzi (CC-193, pkt 5);
narzędzie niesklasyfikowane tam jest ukryte i odmawiane jako `tool_disabled`. Lokalna kontrola jest drugą, słabszą barierą (koniunkcją z gatewayem, więc
dostęp jest najwyżej taki, jaki daje gateway). Skutki, o których muszą wiedzieć administrator i zespół SIEM:

- **Podpowiedzi w definicji nie są oryginalnymi podpowiedziami obcego serwera.** Starter nadpisuje `readOnlyHint` i `destructiveHint` w `tools/list` wartościami z
  ryzyka zastępczego i gubi pozostałe podpowiedzi (`idempotentHint`, `openWorldHint`; `title` zostaje). Manifest i `definitionHash` obejmują definicję taką, jaką adapter
  wystawia, więc **hash pośrednio zależy od podpowiedzi obcego serwera i od tej reguły**. Dla narzędzia, które nie zgłosiło żadnej podpowiedzi, administrator zobaczy
  `destructiveHint: true`, którego obcy serwer nie zgłosił. To tylko podpowiedzi (nikt ich nie czyta jako ryzyka), ale nie należy ich brać za dane obcego serwera.
- **Uwaga dla administratora przy klasyfikacji:** klasyfikacja niższa niż ryzyko zastępcze (np. `transition_issue`, które zgłasza `destructiveHint: true`, jako `UPDATE`) sprawia,
  że gateway przepuści użytkownika z grupą zapisu, a adapter odmówi mu lokalnie (`access_denied` w audycie adaptera). Zawodzi to bezpiecznie i widocznie; żeby narzędzie działało,
  grupa użytkownika musi spełniać obie kontrole albo narzędzie zostaje sklasyfikowane zgodnie z ryzykiem zastępczym. Klasyfikacja wyższa niż ryzyko zastępcze działa bez uwag
  (lokalna kontrola jest wtedy luźniejsza).
- **Kłamliwe `readOnlyHint` osłabia tylko drugą barierę.** Gateway nadal wymusza grupę i TOTP z klasyfikacji administratora.
- **Audyt:** zdarzenie `tool_call` adaptera ma `risk` zastępcze, który może się różnić od `risk` w zdarzeniu gatewaya z tym samym `requestId`. Reguły SIEM łączą po
  `requestId` i traktują `risk` gatewaya jako źródło prawdy.

Docelowo lepiej, by gateway przekazywał klasyfikację z rejestru w JWT, a starter jej używał; to zmiana kontraktu, poza M6.

## Instalacja

Obraz: `ghcr.io/aiwcorpo/mcp-server-adapter:<wersja>` (po wydaniu; workflow `.github/workflows/release.yml`). Lokalnie:

```bash
ci/run.sh verify   # JDK 25, ~/.m2 z mcp-platform-starter 3.0.0-beta-13 i mcp-contracts 6.2.1; 94 testy, żaden pominięty
ci/run.sh image    # docker build, tag mcp-server-adapter:m6-<sha7>; nic nie wypycha
```

Artefakty platformy są w GitHub Packages organizacji `aiwcorpo` (to samo ustawienie `~/.m2/settings.xml` co w README `mcp-starter-lib`,
"Instalacja"). Linux i macOS z bash 3.2 lub nowszym; Windows tylko przez WSL2. Kontener działa jako użytkownik numeryczny 10001:10001 (wolumen `/app/state` musi być dla niego zapisywalny), na bazie
glibc (natywna biblioteka Cedar nie działa na musl) i nasłuchuje na 8443.

## Konfiguracja

Wszystko przez zmienne środowiska (`src/main/resources/application.yml`). Ustawienia startera (JWT, polityki, TLS, Redis) są jak w serwerze Jira.

| Zmienna | Znaczenie |
| --- | --- |
| `MCP_SERVER_ID` | `mcp-<system>`; to samo `serverId`, które gateway ma skonfigurowane jako obsługiwane przez adapter |
| `MCP_FOREIGN_URL` | adres obcego serwera bez ścieżki; https, poza profilem `dev` http tylko z `MCP_FOREIGN_ALLOW_PLAIN_HTTP=true` |
| `MCP_FOREIGN_PATH` | ścieżka punktu MCP obcego serwera (`/mcp`); musi zaczynać się od pojedynczego `/` i nie zmienia schematu, hosta ani portu adresu bazowego (`//inny-host/mcp` zatrzymuje start) |
| `MCP_FOREIGN_AUTHORIZATION_FORMAT` | format nagłówka z poświadczeniem, `{credential}` to wartość z `X-MCP-Credential` (domyślnie `Token {credential}`; `Bearer {credential}` dla innych serwerów). Start wymaga `{credential}` dokładnie raz i braku znaków nowego wiersza: format ze stałą wartością oznaczałby wspólne konto zamiast tożsamości użytkownika |
| `MCP_FOREIGN_DISCOVERY_AUTHORIZATION` | nagłówek wyłącznie dla startowego `tools/list`; część serwerów odmawia każdego żądania bez nagłówka. To nie jest poświadczenie niczyje |
| `MCP_FOREIGN_STARTUP_WAIT` | jak długo czekać na obcy serwer przy starcie (90 s); po tym czasie adapter kończy z błędem |
| `MCP_FOREIGN_SSL_BUNDLE` | pakiet SSL z CA obcego serwera (https) |
| `MCP_ADAPTER_TOOLS_INCLUDE` | lista nazw (jak w obcym serwerze) do opublikowania; pusta publikuje wszystko, co da się ograniczyć |
| `MCP_ADAPTER_STRIP_PREFIX` | przedrostek usuwany z nazw (`jira_`), żeby narzędzia miały nazwy takie jak w serwerze tego systemu |
| `MCP_REGISTRY_URL` | adres nasłuchu mTLS gatewaya (C5), np. `https://mcp-gateway.internal:8443`; pusty wyłącza wysyłanie manifestu. Poza profilem `dev` wymagane https i `MCP_REGISTRY_SSL_BUNDLE` |
| `MCP_REGISTRY_TLS_KEYSTORE` | PKCS12 z certyfikatem klienta o CN `mcp-adapter-<serverId>` (osobny od `MCP_TLS_KEYSTORE`, certyfikatu serwera) |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | certyfikat serwera (SAN `<serverId>.internal`) i CA wewnętrzny |
| `MCP_INTERNAL_URL` | adres, pod którym gateway sięga do adaptera (`https://mcp-jira.internal:8443/mcp`) |
| `MCP_MANIFEST_OUTPUT_FILE` | opcjonalnie zapis manifestu do pliku (podgląd) |

Limity (`aiwcorpo.mcp.adapter.limits.*`) i czasy (`foreign.connect-timeout`, `request-timeout`, `max-response-size`) są w `AdapterProperties`.
Metryka `mcp_adapter_manifest_submitted` ma wartość 1, gdy rejestr zapisał manifest, a `mcp_adapter_manifest_last_status` to ostatni status HTTP rejestru (0 przed pierwszą odpowiedzią, -1 gdy rejestr nieosiągalny; 403 i 422 trwające dłużej to konfiguracja do naprawy). Gdy rejestr odmawia (`403`, `422 adapter_mode_mismatch`),
log ma `event=manifest_rejected` z kodem powodu, a adapter ponawia co najwyżej co 60 s: administrator naprawia konfigurację gatewaya bez restartu adaptera.

## Demo: sooperset/mcp-atlassian 0.23.1 dla Jiry

Obcy serwer: [sooperset/mcp-atlassian](https://github.com/sooperset/mcp-atlassian) (MIT, plik `LICENSE` sprawdzony w tagu `v0.23.1`).
Obraz przypięty po digeście indeksu (tag `0.23.1` istnieje; `latest` nie jest używany):

```
ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466
```

Konfiguracja obcego serwera (pełny fragment Compose: `deploy/docker-compose.demo.yml`):

- `--transport streamable-http --port 8000 --host 0.0.0.0`; endpoint `/mcp`.
- `ENABLED_TOOLS=jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions,jira_add_comment,jira_transition_issue`: sześć narzędzi,
  które mock Jiry z `mcp-platform-dev/mocks` obsługuje (`/myself`, `issue`, `search`, `project`, `comment`, `transitions`). `jira_delete_issue` i reszta
  z 93 narzędzi nie są włączone.
- `JIRA_URL` na system (mock), `JIRA_PERSONAL_TOKEN=placeholder-never-used` jako zapychacz. Bez niego serwer nie pokazuje narzędzi Jiry w `tools/list`
  (sprawdzone na obrazie). Wartość nigdy nie jest użyta, bo `ALLOW_GLOBAL_CRED_FALLBACK` jest wyłączone (jest domyślnie wyłączone; fragment ustawia je jawnie na `false`):
  wywołanie bez poświadczenia w nagłówku kończy się tam `401`.
- Kontener obcego serwera jest utwardzony we fragmencie Compose: `read_only`, `cap_drop: [ALL]`, `no-new-privileges`, `tmpfs /tmp`, limity pamięci (512 MB) i procesów (200); sprawdzone na przypiętym obrazie (działa jako uid 1000 `app`). Siedzi w dwóch sieciach `internal`: `adapter-foreign` (z adapterem) i `jira-foreign` (z mockiem Jiry, który dostaje w niej alias `jira.internal`); nie jest w `servers` ani w `core`, więc nie widzi Redisa, serwera polityk, innych serwerów ani administracyjnego API mocka.
- Serwer odmawia też `initialize` bez nagłówka `Authorization`, dlatego `MCP_FOREIGN_DISCOVERY_AUTHORIZATION=Token discovery-only` dla startowego `tools/list`.
- Poświadczenie użytkownika: adapter wysyła `Authorization: Token <PAT>`, serwer przekazuje go do Jiry jako `Authorization: Bearer <PAT>` (potwierdzone na prawdziwym obrazie
  z fałszywą Jirą), co zgadza się z mockiem (`Bearer alice-pat`, `bob-pat`, ...).

Sześć narzędzi po przepisaniu schematów: `get_issue`, `search`, `get_all_projects`, `get_transitions`, `add_comment`, `transition_issue` (przedrostek `jira_`
usunięty). `transition_issue` ma `destructiveHint: true`, więc lokalnie jest `DESTRUCTIVE`; administrator klasyfikuje je w portalu i może uznać je za `UPDATE`.

Sprawdzenie na prawdziwym obrazie (jednorazowy kontener na domyślnej sieci Dockera, port tylko na 127.0.0.1, brak dostępu do stosu, fałszywa Jira na hoście):

```bash
docker run -d --rm --name adapter-probe -p 127.0.0.1:18931:8000 \
  -e JIRA_URL=http://host.docker.internal:18932 -e JIRA_PERSONAL_TOKEN=placeholder-never-used -e TOOLSETS=all \
  -e ENABLED_TOOLS=jira_get_issue,jira_search,jira_get_all_projects,jira_get_transitions,jira_add_comment,jira_transition_issue,jira_delete_issue \
  ghcr.io/sooperset/mcp-atlassian@sha256:5b7c9b64d4eb3210cab74be8bf3e6aeea9ed14f3042dc59ba5c5287bd4dbe466 \
  --transport streamable-http --port 8000 --host 0.0.0.0
./mvnw -B test -Preal-image -Dtest=RealImageTest    # fałszywa Jira (Bearer alice-pat, bob-pat; issue ABC-1) na 127.0.0.1:18932
```

## Testy

`ci/run.sh verify` (94 testy, bez kontenerów i bez sieci poza pętlą zwrotną): `SchemaTightenerTest` (w tym prawdziwe schematy z `tools/list` obrazu 0.23.1, zapisane w
`src/test/resources`), `ForeignMcpClientTest` i `ForeignCatalogTest` (strumień zdarzeń i JSON, 401/403/5xx, przekierowanie, limit odpowiedzi, czekanie na serwer),
`CallToolTest` (poświadczenie, `requestId`, walidacja przed przekazaniem, wynik jako dane, stałe błędy, lokalna kontrola grup), `GatewayOnlyAccessTest`,
`ToolListTest`, `ManifestTest` i `AdapterManifestTest` (reguły rejestru, `DefinitionHash`, odtworzony hash wektora `manifests/valid-adapter` z kontraktów),
`ManifestSubmissionTest` (ścieżka, ciało, ponowienia), `ProductionConfigurationTest`. Atrapa obcego serwera (`StubForeignServer`) i rejestru (`StubRegistry`) są w katalogu testów.

## Ryzyko rezydualne

Niezmiennik 8 (brak rozróżnienia "nie ma" od "nie wolno") w adapterze zależy od obcego serwera. Adapter ujednolica błędy, które serwer zgłasza jako błędy (`isError`,
błąd JSON-RPC, HTTP 403 i 404). Jeśli serwer zwróci błąd jako zwykły wynik, np. tekst "Issue does not exist" albo "You do not have permission", tekst trafi do modelu
jako `UntrustedText` i zdradzi różnicę. Adapter tego nie wyłapie. Sprawdzenie, jak serwer zgłasza błędy, jest kryterium przeglądu obcego serwera przed podłączeniem
(dla mcp-atlassian 0.23.1: błąd Jiry wraca jako `isError`, sprawdzone na prawdziwym obrazie).

## Czego adapter nie robi

- Nie nadaje ryzyka ani akcji (robi to administrator), nie ma `@ResourceRef`: polityki działają na poziomie narzędzia.
- Nie przekazuje obrazów, zasobów ani treści strukturalnych z odpowiedzi obcego serwera.
- Nie odświeża manifestu w czasie działania: zmiana listy narzędzi obcego serwera wymaga restartu adaptera (rejestr zobaczy różnicę jako oczekujący wpis `source: adapter`).
- Nie trzyma sesji do obcego serwera między wywołaniami (koszt: cztery żądania HTTP na wywołanie zamiast jednego: `initialize`, `notifications/initialized`, `tools/call`, `DELETE`).
- Nie sprawdza, czy obcy serwer sam jest bezpieczny. Wynik jego narzędzia trafia do modelu jako niezaufany tekst; to nie zastępuje przeglądu serwera przed podłączeniem.

Odnośnik do dokumentacji platformy: `mcp-docs` (po M6, etap 4).
