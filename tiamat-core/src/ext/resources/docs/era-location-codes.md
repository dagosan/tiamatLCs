<!-- Added for the ERA Location Code Manager, 2026-10-29. Licensed under the EUPL 1.2.
     Modified 2026-10-30: the register takes the workflow steps itself. -->

# ERA Location Code Manager: the register in Tiamat

Tiamat hosts the location code register of ERA's Location Code Manager, the
frontend built on Abzu (https://github.com/dagosan/abzuKG,
`src/rinf/locationCodes`): the requests for
primary and subsidiary location codes (PLC, SLC), the register of assigned codes
with their history, and the yearly counters behind request references
(LC-2026-0001).

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

## API

All under `/era/v1/location-codes`, JSON, the frontend's own documents.

| Method and path | |
| --- | --- |
| `GET /requests` | all requests, oldest first |
| `GET /requests/{id}` | one request |
| `POST /requests` | submit: `{"actor": {"name", "role": "requester"}, "data": …}`; 201 with the request and its id |
| `POST /requests/{id}/actions` | one step: `{"action": "register", "actor": {"name", "role"}, "payload": {…}, "expectedEventCount": 1}`; 200 with `{"request": …, "codes": [ register entries changed ]}` |
| `GET /requests/{id}/changes` | every saved version, with time and user |
| `GET /codes?country=BE&parentPrimaryCode=BE00438&activeOn=2026-10-29` | the register (filters optional) |
| `GET /codes/{plc}`, `GET /codes/{plc}/{slc}` | one code |
| `GET /codes/{plc}/changes` | every saved version of a PLC |
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
  Tiamat in this setup (`TiamatSecurityConfig` permits all requests). The
  register applies every rule above, but to the actor the frontend names: until
  there is a login, it cannot tell whether that is really who is acting. With
  OIDC, the actor will come from the token instead of the request body
  (`workflow/Actor.java`). Reading (`GET`) is not limited per role either.
- `POST /import` loads a backup without the workflow rules (it is the record
  of decisions already taken); once there is a login it should be limited to
  the register's administrators.
- The technical checks and the value to assign come from the frontend (see
  above); running the checks in the register, with SHACL, is the next step.
- The register is not exported as NeTEx and the codes are not linked to
  Tiamat stop places.

## Code

`src/ext/java/org/rutebanken/tiamat/ext/era/locationcodes/` (same pattern as
`ext/fintraffic`), tests in `src/ext-test/java/…/ext/era/locationcodes/`.
