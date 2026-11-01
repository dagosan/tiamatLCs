<!-- Added for the ERA Location Code Manager, 2026-10-29. Licensed under the EUPL 1.2.
     Modified 2026-10-30: the register takes the workflow steps itself; Docker image (Dockerfile.era).
     Modified 2026-11-02: the register holds code versions with CRD's columns (migration V2). -->

# ERA Location Code Manager: the register in Tiamat

Tiamat hosts the location code register of ERA's Location Code Manager, the
frontend built on Abzu (https://github.com/dagosan/abzuKG,
`src/rinf/locationCodes`): the requests for
primary and subsidiary location codes (PLC, SLC), the register of assigned codes
as versions in the shape of CRD's location table, and the yearly counters behind
request references (LC-2026-0001).

## Codes are kept as CRD keeps them (since 2026-11-02)

One row per version of a code, with CRD's columns (`Country_ISO_code` …
`Mod_Date`, as `countryIsoCode` … `modDate` in the JSON and snake case in
the table; `Active_Flag` follows from the dates), plus the version number,
the SLC fields, the RINF element and the requests that opened and ended the
version. A new code is version 1, valid from its publication. An amendment
ends the current version the day before its effective date and opens the
next with the changed values; a deactivation ends the current version.
`End_Validity` is the last valid day, as in CRD. Passenger and freight use
start with the version that makes them possible and end with a closure. The
CRD columns of a new PLC come from the request's `crd` part (ASCII name, NUTS
code, position, the four flags, free text); an amendment changes only what it
gives. `workflow/CodeVersions.java` and `workflow/Publication.java` match the
frontend's `workflow/codeVersionChanges.ts` and `workflow/publication.ts`.
Migration V2 turned each entry of the old one-row-per-code table into version
1 of its code and kept the old table as `ext_era_lc_code_before_v2`.

## The workflow is the register's

A request changes only through two calls: a requester **submits** it, and each
later step is an **action** the register takes itself. For each action the
register checks, in this order, and refuses with the reason:

| Check | Answer when it fails |
| --- | --- |
| The action exists | 400 `unknownAction` |
| The actor has a name and a known role | 400 `invalidActor` |
| The caller's copy is current (`expectedEventCount` = events stored) | 409 `staleRequest` |
| The action can be taken from the request's status | 409 `actionNotAvailable`, detail `rinf_lc_blocked_wrong_status` |
| The action belongs to the actor's role (procedure sections 5 and 8) | 422 `actionNotAllowed`, `rinf_lc_blocked_wrong_role` |
| A requester's action is taken by the person who submitted | 422 `actionNotAllowed`, `rinf_lc_blocked_not_submitter` |
| Four eyes: the code administrator and the validating officer of a request are different people; a review is not decided by the original officer (Table 3, section 14) | 422 `actionNotAllowed`, `rinf_lc_blocked_four_eyes` |
| Validation: checks run, manual checks confirmed | 422 `actionNotAllowed`, `rinf_lc_blocked_run_checks` / `_manual_checks` |
| Assignment: no failed check, not "not on the network", uniqueness secured | 422 `actionNotAllowed`, `rinf_lc_blocked_failed_checks` / `_not_on_network` / `_uniqueness` |
| A review is asked once, within 20 working days of the notification; an assignment is not revised (a published code changes by amendment or deactivation) | 422 `actionNotAllowed`, `rinf_lc_blocked_already_reviewed` / `_review_window` / `_revise_assignment` |
| The payload fits the action (check results, a ground of Annex B, a RINF link of the right kind, …) | 400 `invalidPayload` |
| The value assigned fits the request (form, country, the code a change is about) and a new code is not in the register | 422 `codeNotAssignable` |

The register then works out what the step changes, from what it stores:
status, dates (its own clock), the request reference (`LC-2026-0001`, at
registration), the decision, and, at publication, the register entries
(closing a PLC with its SLCs also closes those the KG links to it). A value a
client sends for any of these is ignored. The `rinf_lc_…` details are the
frontend's text keys, so it shows them as they are.

The rules are the same as the frontend's (abzuKG `src/rinf/locationCodes/workflow/`),
which uses them to show only the steps that can be taken. **A change to a rule
is a change to both.** The Java side is in `workflow/`; `WorkflowSteps` holds the
table of steps, roles and statuses.

Two facts still come from the frontend, because the register cannot work them
out yet: the results of the technical checks (they need the frontend's KG
lookups) and the value to assign. Their form is checked here, and the value is
checked again against the register and the KG when it is published.

## The register's own checks

Whatever the step, a save is refused if it would break the register:

| Check | Answer when it fails |
| --- | --- |
| Codes have the right form: PLC = two letters + five digits, key = code; SLC = 1–10 letters, digits, dots, hyphens, key = `<PLC>/<SLC>`; country = prefix | 400 `invalidCode` |
| A code is assigned once (version 1, opened by an assignment); a later version is opened by an amendment and follows an ended version | 409 `staleCode`, 400 `invalidCode` |
| A stored version changes only once, when a request ends it (end date, `endedBy`, end of its uses) | 409 `staleCode` |
| A change takes effect after the current version starts, and the current version is not already ended | 422 `actionNotAllowed` (`rinf_lc_blocked_effective_date`); nothing is saved |
| CRD's columns are well formed: dates, end not before start, flags true or false, use dates only with the flag, latitude and longitude together and in range, ASCII name printable ASCII, NUTS code 1–5 capitals or digits, free text up to 255 characters | 400 `invalidCode` (the database checks most of them too) |
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
in one transaction (the `publish` action).

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

### In Docker

`Dockerfile.era` (repository root) builds Tiamat from source and starts it with
this profile, local blob storage and the local changelog, so a server with only
Docker can run it; the database comes from `SPRING_DATASOURCE_URL`,
`_USERNAME` and `_PASSWORD`. abzuKG's `deploy/` runs it together with
PostGIS and the Location Code Manager, which reaches the register through its
own web server at `/era/v1/location-codes` (abzuKG `deploy/README.md`).

## API

All under `/era/v1/location-codes`, JSON, the frontend's own documents.

| Method and path | |
| --- | --- |
| `GET /requests` | all requests, oldest first |
| `GET /requests/{id}` | one request |
| `POST /requests` | submit: `{"actor": {"name", "role": "requester"}, "data": …}`; 201 with the request and its id |
| `POST /requests/{id}/actions` | one step: `{"action": "register", "actor": {"name", "role"}, "payload": {…}, "expectedEventCount": 1}`; 200 with `{"request": …, "codes": [ code versions created or ended ]}` |
| `GET /requests/{id}/changes` | every saved version, with time and user |
| `GET /codes?country=BE&parentPrimaryCode=BE00438&activeOn=2026-10-29` | the register, every version (filters optional; `activeOn` keeps the versions valid that day) |
| `GET /codes/{plc}`, `GET /codes/{plc}/{slc}` | the versions of one code, oldest first |
| `GET /codes/{plc}/changes` | every saved version of a PLC |
| `GET /snapshot` | everything, in the frontend's backup-file format |
| `POST /import` | load a backup file; entries stored identically are skipped, a difference refuses the whole file (409 `importConflict`); history and KG checks do not apply to a backup |

## Tables

| Table | Holds |
| --- | --- |
| `ext_era_lc_request` | one row per request: the JSON document plus status, code type, operation, country, reference (unique) |
| `ext_era_lc_code_version` | one row per code version (key, version): CRD's columns, code type, parent, SLC type, RINF link, the requests that opened and ended it, and the JSON document; the database itself checks the code forms and most CRD rules |
| `ext_era_lc_code_before_v2` | the table before V2 (one row per code), kept as it was |
| `ext_era_lc_reference_sequence` | last reference number per year |
| `ext_era_lc_change_log` | every save, in order, with the authenticated user when there is one |

## Login (added 2026-11-01)

With `era.location-codes.login.required=true`, the register trusts the login,
not the request body (`auth/EraLogin.java`):

| Endpoint | Needs |
| --- | --- |
| `GET /codes…` (the register) | nothing: it is public |
| `GET /requests…`, `GET …/changes` | a login (401 `loginRequired`) |
| `POST /requests`, `POST /requests/{id}/actions` | a login; the actor's name is the account's, its role must be one the account holds (403 `roleNotHeld`) |
| `GET /snapshot`, `POST /import` | the code administrator role |

Tiamat checks the token's signature and expiry against the login service's
keys (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`, e.g. Keycloak's
`…/realms/era/protocol/openid-connect/certs`). The name is read from the
`name` claim (`era.location-codes.login.name-claim`, then
`preferred_username`), the roles from `lcm_roles`
(`era.location-codes.login.roles-claim`, then Keycloak's `realm_access.roles`).
Roles are the workflow's own names: `requester`, `codeAdministrator`,
`rinfCorrespondent`, `externalCoordinationPoint`, `validatingOfficer`.

Without the setting (development, tests) the register still applies every
rule, but to the actor the frontend names.

## Not yet

- A requester can read every request once logged in; the frontend shows only
  their own, but the API does not filter them yet.
- The technical checks and the value to assign come from the frontend (see
  above); running the checks in the register, with SHACL, is the next step.
- The register is not exported as NeTEx and the codes are not linked to
  Tiamat stop places.

## Code

`src/ext/java/org/rutebanken/tiamat/ext/era/locationcodes/` (same pattern as
`ext/fintraffic`), tests in `src/ext-test/java/…/ext/era/locationcodes/`.
