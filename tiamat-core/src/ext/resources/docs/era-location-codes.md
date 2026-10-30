<!-- Added for the ERA Location Code Manager, 2026-10-29. Licensed under the EUPL 1.2. -->

# ERA Location Code Manager: the register in Tiamat

Tiamat hosts the location code register of ERA's Location Code Manager, the
frontend built on Abzu (https://github.com/dagosan/abzuKG,
`src/rinf/locationCodes`): the requests for
primary and subsidiary location codes (PLC, SLC), the register of assigned codes
with their history, and the yearly counters behind request references
(LC-2026-0001).

The workflow itself (steps, roles, deadlines, checklist) stays in the frontend.
Tiamat stores what the frontend saves and refuses a save that would break the
register, whatever the frontend checked before:

| Check | Answer when it fails |
| --- | --- |
| Codes have the right form: PLC = two letters + five digits, key = code; SLC = 1–10 letters, digits, dots, hyphens, key = `<PLC>/<SLC>`; country = prefix | 400 `invalidCode` |
| A code is assigned once, as the first line of its history; later saves only add lines | 409 `staleCode` |
| A request's events only grow; its reference, once given, never changes | 409 `staleRequest`, 422 `requestChangeNotAllowed` |
| Request references are unique | 409 `duplicateReference` |
| A new PLC is positioned on an operational point or section of line (Art. 9(6) TEL TSI, procedure 8.6) | 422 `codeRejected` |
| The RINF element a code links to exists in the Knowledge Graph | 422 `codeRejected` |
| A new code is not already carried by a location in the KG (first population, `existingCode`, may register the code the same element carries) | 422 `codeRejected` |
| A new SLC has a parent PLC in force (in the register, or in the KG) | 422 `codeRejected` |
| A code changed or closed but not in the register is in the KG | 422 `codeRejected` |
| The KG did not answer | 503 `knowledgeGraphUnavailable`; nothing is saved |

The "is stale" rules also stop lost updates: if two people act on the same
request, the second save is based on an old copy and is refused, so they reload
instead of overwriting each other. A publication saves its codes and its request
in one transaction (`PUT /requests/{id}/with-codes`).

Errors come back as `{"error": "...", "message": "...", "details": ["..."]}`.

## Turning it on

Add the Spring profile `era-location-codes`, e.g. locally:

```
--spring.profiles.active=local,local-blobstore,local-changelog,era-location-codes
```

On start, the profile creates its tables (`ext_era_lc_*`) with its own Flyway
history table, `ext_era_schema_version`, so it never mixes with Tiamat's own
migrations. Without the profile, nothing of this is loaded.

| Property | Default | |
| --- | --- | --- |
| `era.location-codes.kg.checks-enabled` | `true` | `false` skips every Knowledge Graph check (tests, offline) |
| `era.location-codes.kg.endpoint` | `https://graph.data.era.europa.eu/repositories/rinf-plus` | SPARQL endpoint |
| `era.location-codes.kg.timeout-seconds` | `20` | per question; each takes about 0.5 s |

The KG client uses the JVM's proxy settings (`-Dhttps.proxyHost=…`).

## API

All under `/era/v1/location-codes`, JSON, the frontend's own documents.

| Method and path | |
| --- | --- |
| `GET /requests` | all requests, oldest first |
| `GET /requests/{id}` | one request |
| `PUT /requests/{id}` | create or save a request (204) |
| `PUT /requests/{id}/with-codes` | `{"request": …, "codes": [ … ]}`, saved together (204) |
| `GET /requests/{id}/changes` | every saved version, with time and user |
| `GET /codes?country=BE&parentPrimaryCode=BE00438&activeOn=2026-10-29` | the register (filters optional) |
| `GET /codes/{plc}`, `GET /codes/{plc}/{slc}` | one code |
| `GET /codes/{plc}/changes` | every saved version of a PLC |
| `PUT /codes` | create or save codes, all or none (204) |
| `POST /reference-sequences/{year}/next` | `{"year": 2026, "value": 1}`, atomic across instances |
| `GET /snapshot` | everything, in the frontend's backup-file format |
| `POST /import` | load a backup file; entries stored identically are skipped, a difference refuses the whole file (409 `importConflict`); history and KG checks do not apply to a backup |

## Tables

| Table | Holds |
| --- | --- |
| `ext_era_lc_request` | one row per request: the JSON document plus status, code type, operation, country, reference (unique) |
| `ext_era_lc_code` | one row per code: the JSON document plus code, parent, country, RINF link, validity; the database itself checks the code forms |
| `ext_era_lc_reference_sequence` | last reference number per year |
| `ext_era_lc_change_log` | every save, in order, with the authenticated user when there is one |

## Not yet

- No authentication of its own: the endpoints are as open as the rest of
  Tiamat in this setup (`TiamatSecurityConfig` permits all requests). Roles
  are still chosen in the frontend.
- The register is not exported as NeTEx and the codes are not linked to
  Tiamat stop places.

## Code

`src/ext/java/org/rutebanken/tiamat/ext/era/locationcodes/` (same pattern as
`ext/fintraffic`), tests in `src/ext-test/java/…/ext/era/locationcodes/`.
