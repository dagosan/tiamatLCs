/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * Added for the ERA Location Code Manager, 2026-10-29.
 * Modified 2026-10-30: the register takes the workflow steps itself (submit,
 * takeStep) instead of storing documents the frontend computed.
 * Modified 2026-11-02: code versions with CRD's columns.
 * Modified 2026-11-10: master data (countries, subsidiary types) and the
 * rules that read them; a requester's own requests; request drafts.
 * Modified 2026-11-14: bulk requests (submitBulk, registerBulk).
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules.Extension;
import org.rutebanken.tiamat.ext.era.locationcodes.RegisterChecks.ParentState;
import org.rutebanken.tiamat.ext.era.locationcodes.kg.KnowledgeGraphUnavailableException;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeVersion;
import org.rutebanken.tiamat.ext.era.locationcodes.masterdata.MasterData;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.AssignedCodes;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.BulkRequests;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.NewRequests;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.Publication;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestRouting;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestTransitions;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.Step;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.StepPayloads;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.StepPermission;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.BAD_REQUEST;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.CONFLICT;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.NOT_FOUND;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.UNAVAILABLE;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.UNPROCESSABLE;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.PRIMARY;

/**
 * The register. It takes each workflow step itself: it checks that the step
 * may be taken now, by this actor (workflow.StepPermission), works out what
 * the step changes (workflow.RequestTransitions, workflow.Publication), and
 * saves the result after the checks in {@link LocationCodeRules} and
 * {@link RegisterChecks}. Each step is one transaction; a publication saves
 * its codes and its request together, so a code is never registered without
 * the decision behind it.
 */
public class LocationCodeService {

    /** Same form as JavaScript's toISOString(), which the frontend writes. */
    private static final DateTimeFormatter ISO_INSTANT_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final LocationCodeRepository repository;
    private final TransactionTemplate transactions;
    /** Null when the Knowledge Graph checks are switched off. */
    private final RegisterChecks registerChecks;
    private final Clock clock;

    private static final int COUNTRY_PREFIX_LENGTH = 2;
    private static final Pattern DRAFT_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final int MAX_DRAFTS_PER_OWNER = 100;

    public LocationCodeService(
            LocationCodeRepository repository, TransactionTemplate transactions, RegisterChecks registerChecks) {
        this(repository, transactions, registerChecks, Clock.systemUTC());
    }

    public LocationCodeService(LocationCodeRepository repository, TransactionTemplate transactions,
            RegisterChecks registerChecks, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.registerChecks = registerChecks;
        this.clock = clock;
    }

    /** Every request, or with `onlyOf` the requests that person submitted. */
    public String requestsJson(Optional<String> onlyOf) {
        return onlyOf.map(repository::requestsJsonOf).orElseGet(repository::requestsJson);
    }

    /** One request; with `onlyOf`, another person's request is answered as if it did not exist. */
    public String requestJson(String id, Optional<String> onlyOf) {
        String document = repository.requestDocument(id).orElseThrow(() -> noRequest(id));
        boolean readable = onlyOf.isEmpty()
                || Actor.isSamePerson(JsonDocuments.text(JsonDocuments.parse(document), "submittedBy"), onlyOf.get());
        if (!readable) {
            throw noRequest(id);
        }
        return document;
    }

    /** The saved versions of a request, readable as the request itself is. */
    public String requestChangeLogJson(String id, Optional<String> onlyOf) {
        requestJson(id, onlyOf);
        return repository.changeLogJson("request", id);
    }

    private static LocationCodeException noRequest(String id) {
        return new LocationCodeException(NOT_FOUND, "requestNotFound", "no request " + id);
    }

    /** One person's drafts, last saved first. */
    public String draftsJson(String owner) {
        return repository.draftsJson(owner);
    }

    /**
     * Saves a request form not yet submitted: {"data": …}. The draft keeps its
     * owner; another person's draft is answered as if it did not exist.
     * Returns {"id", "owner", "savedAt", "data"}.
     */
    public String saveDraft(String id, String owner, String json) {
        JsonNode body = JsonDocuments.parse(json);
        if (!DRAFT_ID.matcher(id).matches()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidDraft",
                    "a draft's id is 1 to 64 letters, digits or hyphens");
        }
        if (!body.path("data").isObject()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidDraft", "data must be an object");
        }
        ObjectNode draft = JsonDocuments.MAPPER.createObjectNode();
        draft.put("id", id);
        draft.put("owner", owner);
        draft.put("savedAt", now());
        draft.set("data", body.get("data"));
        String document = JsonDocuments.write(draft);
        inTransaction(() -> {
            Optional<JsonNode> stored = repository.lockDraft(id);
            if (stored.isPresent()) {
                requireOwner(stored.get(), owner);
            } else if (repository.draftCount(owner) >= MAX_DRAFTS_PER_OWNER) {
                throw new LocationCodeException(UNPROCESSABLE, "tooManyDrafts",
                        "at most " + MAX_DRAFTS_PER_OWNER + " drafts; submit or discard one first");
            }
            repository.saveDraft(id, owner, document);
        });
        return document;
    }

    /** Discards a draft of `owner`. */
    public void deleteDraft(String id, String owner) {
        inTransaction(() -> removeDraft(id, owner));
    }

    private void removeDraft(String id, String owner) {
        requireOwner(repository.lockDraft(id).orElseThrow(() -> noDraft(id)), owner);
        repository.deleteDraft(id);
    }

    private static void requireOwner(JsonNode draft, String owner) {
        if (!Actor.isSamePerson(JsonDocuments.text(draft, "owner"), owner)) {
            throw noDraft(JsonDocuments.text(draft, "id"));
        }
    }

    private static LocationCodeException noDraft(String id) {
        return new LocationCodeException(NOT_FOUND, "draftNotFound", "no draft " + id);
    }

    public String codesJson(String country, String parentPrimaryCode, String activeOn) {
        if (activeOn != null) {
            requireDate(activeOn, "activeOn");
        }
        return repository.codesJson(country, parentPrimaryCode, activeOn);
    }

    /** Every version of one code, oldest first. */
    public String codeJson(String key) {
        return repository.versionsJson(key).orElseThrow(() ->
                new LocationCodeException(NOT_FOUND, "codeNotFound", "no code " + key));
    }

    public String changeLogJson(String entity, String key) {
        return repository.changeLogJson(entity, key);
    }

    /**
     * Procedure step 2: {"actor": …, "data": …, "draftId": …}. The register
     * gives the request its id and records the submission, and removes the
     * submitter's draft it was written from, if one is named; returns the request.
     */
    public String submit(String json) {
        JsonNode body = JsonDocuments.parse(json);
        Actor actor = Actor.fromJson(body.path("actor"));
        if (!Actor.REQUESTER.equals(actor.role())) {
            throw refused(StepPermission.WRONG_ROLE);
        }
        if (!body.path("data").isObject()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidRequest", "data must be an object");
        }
        ObjectNode request = NewRequests.create(UUID.randomUUID().toString(), withCountry(body.get("data")), actor,
                now());
        RequestEntry entry = checkedRequest(null, request);
        requireMasterData(request.path("data"));
        String draftId = JsonDocuments.text(body, "draftId");
        inTransaction(() -> {
            writeRequest(entry, request);
            // The draft the request was written from has done its work.
            if (draftId != null && repository.lockDraft(draftId)
                    .filter(draft -> Actor.isSamePerson(JsonDocuments.text(draft, "owner"), actor.name()))
                    .isPresent()) {
                repository.deleteDraft(draftId);
            }
        });
        return JsonDocuments.write(request);
    }

    /**
     * A bulk request (LCMS specification FR-08): {"actor", "fileName",
     * "lines": [{"line", "data"}]}. Every line becomes a request of its own,
     * all with the same bulk id and submission moment; either all are
     * recorded or none. Returns the lines, in the order of the file.
     */
    public String submitBulk(String json) {
        JsonNode body = JsonDocuments.parse(json);
        Actor actor = Actor.fromJson(body.path("actor"));
        if (!Actor.REQUESTER.equals(actor.role())) {
            throw refused(StepPermission.WRONG_ROLE);
        }
        List<String> problems = BulkRequests.submissionProblems(body);
        if (!problems.isEmpty()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidRequest", problems);
        }
        String now = now();
        String bulkId = UUID.randomUUID().toString();
        String fileName = JsonDocuments.text(body, "fileName").trim();
        List<JsonNode> lines = new ArrayList<>();
        body.get("lines").forEach(lines::add);
        lines.sort((first, second) -> Integer.compare(first.get("line").asInt(), second.get("line").asInt()));
        List<ObjectNode> requests = new ArrayList<>();
        List<RequestEntry> entries = new ArrayList<>();
        for (JsonNode line : lines) {
            int number = line.get("line").asInt();
            ObjectNode request = BulkRequests.asLine(
                    NewRequests.create(UUID.randomUUID().toString(), withCountry(line.get("data")), actor, now),
                    bulkId, number, fileName);
            RequestEntry entry = JsonDocuments.request(request);
            List<String> lineProblems = new ArrayList<>(LocationCodeRules.requestProblems(entry));
            if (lineProblems.isEmpty()) {
                try {
                    requireMasterData(request.path("data"));
                } catch (LocationCodeException refusal) {
                    lineProblems.addAll(refusal.details());
                }
            }
            lineProblems.forEach(problem -> problems.add("line " + number + ": " + problem));
            requests.add(request);
            entries.add(entry);
        }
        if (!problems.isEmpty()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidRequest", problems);
        }
        inTransaction(() -> {
            for (int index = 0; index < requests.size(); index++) {
                writeRequest(entries.get(index), requests.get(index));
            }
        });
        return JsonDocuments.write(JsonDocuments.MAPPER.createArrayNode().addAll(requests));
    }

    /**
     * Registers a bulk request: {"actor"}. Every line gets the same number
     * from the year's sequence, ending with its line in the file
     * ("LC-2026-0007/3"), and the same T0. All lines must still be as
     * submitted; a line is never registered on its own. Returns the lines.
     */
    public String registerBulk(String bulkId, String json) {
        JsonNode body = JsonDocuments.parse(json);
        Actor actor = Actor.fromJson(body.path("actor"));
        JsonNode noPayload = JsonDocuments.MAPPER.createObjectNode();
        return inTransactionReturning(() -> {
            List<JsonNode> stored = repository.lockBulkLines(bulkId);
            if (stored.isEmpty()) {
                throw new LocationCodeException(NOT_FOUND, "bulkRequestNotFound", "no bulk request " + bulkId);
            }
            BulkRequests.registrationRefusal(stored, actor).ifPresent(reason -> {
                throw refused(reason);
            });
            Instant instant = clock.instant();
            String now = ISO_INSTANT_MILLIS.format(instant);
            int year = LocalDate.ofInstant(instant, ZoneOffset.UTC).getYear();
            String reference = NewRequests.reference(year, repository.nextReferenceSequence(year));
            ArrayNode registered = JsonDocuments.MAPPER.createArrayNode();
            for (JsonNode line : stored) {
                String lineReference = BulkRequests.lineReference(reference, BulkRequests.lineOf(line));
                ObjectNode updated = RequestTransitions.apply(
                        line, new Step(WorkflowSteps.REGISTER, actor, noPayload, now, lineReference, isOutsideEu(line)));
                writeRequest(checkedRequest(JsonDocuments.text(line, "id"), updated), updated);
                registered.add(updated);
            }
            return JsonDocuments.write(registered);
        });
    }

    /**
     * One step on a request: {"action", "actor", "payload", "expectedEventCount"}.
     * expectedEventCount is how many events the actor's copy has; if someone
     * else took a step meanwhile, the step is refused (409 staleRequest).
     * Returns {"request": …, "codes": [ code versions the step created or ended ]}.
     */
    public String takeStep(String pathId, String json) {
        JsonNode body = JsonDocuments.parse(json);
        String step = JsonDocuments.text(body, "action");
        if (!WorkflowSteps.isStep(step) || WorkflowSteps.SUBMIT.equals(step)) {
            throw new LocationCodeException(BAD_REQUEST, "unknownAction", "unknown action \"" + step + "\"");
        }
        Actor actor = Actor.fromJson(body.path("actor"));
        JsonNode payload = body.path("payload").isObject() ? body.get("payload") : JsonDocuments.MAPPER.createObjectNode();
        JsonNode expectedEvents = body.path("expectedEventCount");
        if (!expectedEvents.isIntegralNumber()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidAction", "expectedEventCount must be a whole number");
        }
        return inTransactionReturning(() -> {
            JsonNode stored = repository.lockRequest(pathId).orElseThrow(() ->
                    new LocationCodeException(NOT_FOUND, "requestNotFound", "no request " + pathId));
            if (stored.path("events").size() != expectedEvents.asInt()) {
                throw new LocationCodeException(CONFLICT, "staleRequest",
                        "request " + pathId + " was changed by someone else; reload it and take the step again");
            }
            Instant instant = clock.instant();
            String now = ISO_INSTANT_MILLIS.format(instant);
            LocalDate today = LocalDate.ofInstant(instant, ZoneOffset.UTC);
            StepPermission.refusal(stored, step, actor, today).ifPresent(reason -> {
                throw refused(reason);
            });
            List<String> payloadProblems = StepPayloads.problems(stored, step, payload);
            if (!payloadProblems.isEmpty()) {
                throw new LocationCodeException(BAD_REQUEST, "invalidPayload", payloadProblems);
            }
            if (WorkflowSteps.ASSIGN.equals(step)) {
                requireAssignable(stored, JsonDocuments.text(payload, "code"));
            }
            String reference = WorkflowSteps.REGISTER.equals(step)
                    ? NewRequests.reference(today.getYear(), repository.nextReferenceSequence(today.getYear()))
                    : null;
            ObjectNode updated = RequestTransitions.apply(stored,
                    new Step(step, actor, payload, now, reference, isOutsideEu(stored)));
            RequestEntry entry = checkedRequest(pathId, updated);
            ArrayNode codes = JsonDocuments.MAPPER.createArrayNode();
            if (WorkflowSteps.PUBLISH.equals(step)) {
                codes.addAll(publicationChanges(updated, now));
            }
            if (!codes.isEmpty()) {
                writeVersions(versionDocuments(codes), Map.of(entry.id(), entry.operation()));
            }
            writeRequest(entry, updated);
            ObjectNode result = JsonDocuments.MAPPER.createObjectNode();
            result.set("request", updated);
            result.set("codes", codes);
            return JsonDocuments.write(result);
        });
    }

    /** Everything the register holds, in the frontend's backup format. */
    public String snapshotJson() {
        return transactions.execute(status -> "{\"requests\":" + repository.requestsJson()
                + ",\"codes\":" + repository.codesJson(null, null, null)
                + ",\"sequences\":" + repository.sequencesJson() + "}");
    }

    /**
     * Loads a backup (e.g. the file the browser-only version exported).
     * Entries already stored identically are skipped; an entry stored
     * differently stops the whole import, so nothing is overwritten.
     * History rules and Knowledge Graph checks do not apply: the backup is
     * the record of decisions already taken.
     */
    public String importSnapshot(String json) {
        JsonNode snapshot = JsonDocuments.parse(json);
        if (!snapshot.path("requests").isArray() || !snapshot.path("codes").isArray()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidBackup", "needs \"requests\" and \"codes\" arrays");
        }
        List<RequestEntry> requests = new ArrayList<>();
        List<JsonNode> requestDocuments = new ArrayList<>();
        for (JsonNode document : snapshot.path("requests")) {
            requests.add(checkedRequest(null, document));
            requestDocuments.add(document);
        }
        List<JsonNode> codes = versionDocuments(snapshot.path("codes"));
        Map<Integer, Integer> sequences = sequences(snapshot.path("sequences"));
        return inTransactionReturning(() -> {
            int requestsAdded = 0;
            int codesAdded = 0;
            List<String> conflicts = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) {
                RequestEntry request = requests.get(index);
                JsonNode document = requestDocuments.get(index);
                Optional<JsonNode> stored = repository.lockRequest(request.id());
                if (stored.isEmpty()) {
                    repository.insertRequest(request, JsonDocuments.write(document));
                    repository.log("request", request.id(), "import", principal(), JsonDocuments.write(document));
                    requestsAdded++;
                } else if (!stored.get().equals(document)) {
                    conflicts.add("request " + request.id() + " is stored with other content");
                }
            }
            for (JsonNode document : codes) {
                CodeVersion version = JsonDocuments.version(document);
                Optional<JsonNode> stored = repository.lockVersion(version.key(), version.version());
                if (stored.isEmpty()) {
                    repository.insertVersion(version, document);
                    repository.log("code", version.key(), "import", principal(), JsonDocuments.write(document));
                    codesAdded++;
                } else if (!stored.get().equals(document)) {
                    conflicts.add("code " + version.key() + " version " + version.version()
                            + " is stored with other content");
                }
            }
            if (!conflicts.isEmpty()) {
                throw new LocationCodeException(CONFLICT, "importConflict", conflicts);
            }
            sequences.forEach((year, value) -> repository.raiseReferenceSequence(year, value));
            raiseSequencesToReferences(requests);
            return "{\"requestsAdded\":" + requestsAdded + ",\"requestsSkipped\":" + (requests.size() - requestsAdded)
                    + ",\"codesAdded\":" + codesAdded + ",\"codesSkipped\":" + (codes.size() - codesAdded) + "}";
        });
    }

    /** A value assigned once: well formed, and for a new code not in the register already. */
    private void requireAssignable(JsonNode request, String code) {
        List<String> problems = new ArrayList<>(AssignedCodes.problems(request, code));
        boolean creation = RequestRouting.isCreation(JsonDocuments.text(request.path("data"), "operation"));
        String key = AssignedCodes.keyOf(request, code);
        if (problems.isEmpty() && creation && repository.hasCode(key)) {
            problems.add(key + " is already in the register");
        }
        if (problems.isEmpty()) {
            problems.addAll(rangeProblems(request.path("data"), key));
        }
        if (!problems.isEmpty()) {
            throw new LocationCodeException(UNPROCESSABLE, "codeNotAssignable", problems);
        }
    }

    /** A new PLC takes a number from its country's range (BR-10); an existing code is registered as it is. */
    private List<String> rangeProblems(JsonNode data, String key) {
        boolean newPrimary = "newCode".equals(JsonDocuments.text(data, "operation"))
                && PRIMARY.equals(JsonDocuments.text(data, "codeType"));
        Optional<JsonNode> country = repository.country(JsonDocuments.text(data, "country"));
        if (!newPrimary || country.isEmpty()) {
            return List.of();
        }
        int locationNumber = Integer.parseInt(key.substring(COUNTRY_PREFIX_LENGTH));
        if (MasterData.inRange(country.get(), locationNumber)) {
            return List.of();
        }
        return List.of(key + " is outside the range for new codes of " + JsonDocuments.text(data, "country") + " ("
                + country.get().path("plcRangeStart").asInt() + " to " + country.get().path("plcRangeEnd").asInt()
                + ")");
    }

    /**
     * A request names a country the register assigns codes for, and a new SLC a
     * subsidiary type, both valid today (BR-08).
     */
    private void requireMasterData(JsonNode data) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<String> problems = new ArrayList<>();
        String country = JsonDocuments.text(data, "country");
        if (repository.country(country).filter(stored -> MasterData.validOn(stored, today)).isEmpty()) {
            problems.add("codes are not assigned for country \"" + country + "\"");
        }
        boolean newSubsidiary = RequestRouting.isCreation(JsonDocuments.text(data, "operation"))
                && !PRIMARY.equals(JsonDocuments.text(data, "codeType"));
        String type = JsonDocuments.text(data, "subsidiaryTypeCode");
        if (newSubsidiary
                && repository.subsidiaryType(type).filter(stored -> MasterData.validOn(stored, today)).isEmpty()) {
            problems.add("\"" + type + "\" is not a subsidiary location code type in use");
        }
        if (!problems.isEmpty()) {
            throw new LocationCodeException(UNPROCESSABLE, "invalidRequest", problems);
        }
    }

    /**
     * A request for an SLC, or to change a PLC, may leave the country out (the
     * frontend's form asks for it only when a PLC is created): it is then the
     * prefix of the PLC concerned, the SLC's parent or the PLC to change.
     */
    private static JsonNode withCountry(JsonNode data) {
        String country = JsonDocuments.text(data, "country");
        if (country != null && !country.isBlank()) {
            return data;
        }
        String concerned = JsonDocuments.text(data, PRIMARY.equals(JsonDocuments.text(data, "codeType"))
                ? "targetCode" : "parentPrimaryCode");
        String primaryCode = concerned == null ? "" : concerned.trim().toUpperCase(Locale.ROOT);
        if (!LocationCodeRules.PRIMARY_CODE.matcher(primaryCode).matches()) {
            return data;
        }
        ObjectNode completed = data.deepCopy();
        completed.put("country", primaryCode.substring(0, COUNTRY_PREFIX_LENGTH));
        return completed;
    }

    /** Whether the request's country is not an EU Member State; a country no longer listed counts as outside. */
    private boolean isOutsideEu(JsonNode request) {
        return repository.country(JsonDocuments.text(withCountry(request.path("data")), "country"))
                .map(country -> !country.path("euMemberState").asBoolean())
                .orElse(true);
    }

    public String countriesJson(String validOn) {
        if (validOn != null) {
            requireDate(validOn, "validOn");
        }
        return repository.countriesJson(validOn);
    }

    public String subsidiaryTypesJson(String validOn) {
        if (validOn != null) {
            requireDate(validOn, "validOn");
        }
        return repository.subsidiaryTypesJson(validOn);
    }

    /** Adds or replaces a country (FR-27); returns it as stored. */
    public String saveCountry(String isoCode, String json) {
        ObjectNode country = MasterData.country(JsonDocuments.parse(json));
        List<String> problems = new ArrayList<>(MasterData.countryProblems(country));
        if (!isoCode.equals(country.path("isoCode").asText())) {
            problems.add("isoCode in the body differs from the URL (" + isoCode + ")");
        }
        return savedMasterData("country", isoCode, country, problems, () -> repository.saveCountry(country));
    }

    /** Adds or replaces a subsidiary location code type (FR-27); returns it as stored. */
    public String saveSubsidiaryType(String code, String json) {
        ObjectNode type = MasterData.subsidiaryType(JsonDocuments.parse(json));
        List<String> problems = new ArrayList<>(MasterData.subsidiaryTypeProblems(type));
        if (!code.equals(type.path("code").asText())) {
            problems.add("code in the body differs from the URL (" + code + ")");
        }
        return savedMasterData("subsidiaryType", code, type, problems, () -> repository.saveSubsidiaryType(type));
    }

    private String savedMasterData(String entity, String key, ObjectNode document, List<String> problems,
            Runnable save) {
        if (!problems.isEmpty()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidMasterData", problems);
        }
        String json = JsonDocuments.write(document);
        inTransaction(() -> {
            try {
                save.run();
            } catch (DuplicateKeyException e) {
                throw new LocationCodeException(CONFLICT, "duplicateUicCode",
                        "another country already has UIC code " + document.path("uicCode").asText());
            }
            repository.log(entity, key, "save", principal(), json);
        });
        return json;
    }

    /** The code versions a publication creates or ends, with the SLCs it closes. */
    private List<ObjectNode> publicationChanges(JsonNode request, String now) {
        Optional<String> parent = Publication.parentWhoseDependantsClose(request);
        List<JsonNode> subsidiaries = new ArrayList<>();
        List<String> kgSubsidiaryCodes = List.of();
        if (parent.isPresent()) {
            JsonDocuments.parse(repository.currentSubsidiariesJson(parent.get())).forEach(subsidiaries::add);
            if (registerChecks != null) {
                try {
                    kgSubsidiaryCodes = registerChecks.subsidiaryCodesInKg(parent.get());
                } catch (KnowledgeGraphUnavailableException e) {
                    throw new LocationCodeException(UNAVAILABLE, "knowledgeGraphUnavailable",
                            "the Knowledge Graph did not answer, so the SLCs of " + parent.get()
                                    + " could not be listed; nothing was saved (" + e.getMessage() + ")");
                }
            }
        }
        return Publication.registerChanges(request, repository::lockCurrentVersion, subsidiaries, kgSubsidiaryCodes,
                now);
    }

    /** A step the actor may not take now: 409 when the request has moved on, 422 otherwise. */
    private static LocationCodeException refused(String reason) {
        if (StepPermission.WRONG_STATUS.equals(reason)) {
            return new LocationCodeException(CONFLICT, "actionNotAvailable", reason);
        }
        return new LocationCodeException(UNPROCESSABLE, "actionNotAllowed", reason);
    }

    private String now() {
        return ISO_INSTANT_MILLIS.format(clock.instant());
    }

    private void writeRequest(RequestEntry request, JsonNode document) {
        String json = JsonDocuments.write(document);
        Optional<JsonNode> stored = repository.lockRequest(request.id());
        if (stored.isEmpty()) {
            withUniqueReference(request, () -> repository.insertRequest(request, json));
            repository.log("request", request.id(), "create", principal(), json);
            return;
        }
        Extension extension = LocationCodeRules.extension(
                JsonDocuments.events(stored.get()), JsonDocuments.events(document));
        if (extension == Extension.SAME && stored.get().equals(document)) {
            return;
        }
        if (extension != Extension.ADDS) {
            throw new LocationCodeException(CONFLICT, "staleRequest",
                    "request " + request.id() + " was changed by someone else; reload it and take the step again");
        }
        List<String> problems = LocationCodeRules.requestChangeProblems(JsonDocuments.request(stored.get()), request);
        if (!problems.isEmpty()) {
            throw new LocationCodeException(UNPROCESSABLE, "requestChangeNotAllowed", problems);
        }
        withUniqueReference(request, () -> repository.updateRequest(request, json));
        repository.log("request", request.id(), "update", principal(), json);
    }

    private static void withUniqueReference(RequestEntry request, Runnable write) {
        try {
            write.run();
        } catch (DuplicateKeyException e) {
            if (request.reference() != null && String.valueOf(e.getMessage()).contains("ext_era_lc_request_reference_key")) {
                throw new LocationCodeException(CONFLICT, "duplicateReference",
                        "reference " + request.reference() + " is already used by another request");
            }
            throw new LocationCodeException(CONFLICT, "staleRequest",
                    "request " + request.id() + " was created by someone else meanwhile; reload it");
        }
    }

    /**
     * Saves the versions a step created or ended. A stored version may only be
     * ended, once (VersionRules.update); a new version 1 needs a code the
     * register does not hold, a later one the previous version ended (in this
     * save or before) and nothing newer stored.
     */
    private void writeVersions(List<JsonNode> documents, Map<String, String> operationsInThisSave) {
        List<String> problems = new ArrayList<>();
        List<Runnable> writes = new ArrayList<>();
        Map<String, JsonNode> inThisSave = new HashMap<>();
        documents.forEach(document -> {
            CodeVersion version = JsonDocuments.version(document);
            inThisSave.put(version.key() + "#" + version.version(), document);
        });
        for (JsonNode document : documents) {
            CodeVersion version = JsonDocuments.version(document);
            JsonNode storedDocument = repository.lockVersion(version.key(), version.version()).orElse(null);
            if (storedDocument != null) {
                VersionRules.Update update = VersionRules.update(storedDocument, document);
                if (update == VersionRules.Update.SAME) {
                    continue;
                }
                if (update == VersionRules.Update.STALE) {
                    throw staleVersion(version);
                }
            } else {
                requireNextVersion(version, inThisSave);
            }
            CodeVersion stored = storedDocument == null ? null : JsonDocuments.version(storedDocument);
            if (registerChecks != null) {
                try {
                    registerChecks.problems(stored, version,
                                    requestId -> Optional.ofNullable(operationsInThisSave.get(requestId))
                                            .or(() -> repository.requestOperation(requestId)),
                                    this::parentState)
                            .forEach(problem -> problems.add(version.key() + ": " + problem));
                } catch (KnowledgeGraphUnavailableException e) {
                    throw new LocationCodeException(UNAVAILABLE, "knowledgeGraphUnavailable",
                            "the Knowledge Graph did not answer, so " + version.key()
                                    + " could not be checked; nothing was saved (" + e.getMessage() + ")");
                }
            }
            boolean create = storedDocument == null;
            writes.add(() -> {
                try {
                    if (create) {
                        repository.insertVersion(version, document);
                    } else {
                        repository.updateVersion(version, document);
                    }
                } catch (DuplicateKeyException e) {
                    throw staleVersion(version);
                }
                repository.log("code", version.key(), create ? "create" : "update", principal(),
                        JsonDocuments.write(document));
            });
        }
        if (!problems.isEmpty()) {
            throw new LocationCodeException(UNPROCESSABLE, "codeRejected", problems);
        }
        writes.forEach(Runnable::run);
    }

    /** A new version follows the latest one: none for version 1, an ended version n - 1 otherwise. */
    private void requireNextVersion(CodeVersion version, Map<String, JsonNode> inThisSave) {
        int latestStored = repository.lockCurrentVersion(version.key())
                .map(stored -> stored.path("version").asInt()).orElse(0);
        if (latestStored >= version.version()) {
            throw staleVersion(version);
        }
        if (version.version() == 1) {
            if (latestStored > 0) {
                throw staleVersion(version);
            }
            return;
        }
        String previousId = version.key() + "#" + (version.version() - 1);
        JsonNode previous = Optional.ofNullable(inThisSave.get(previousId))
                .or(() -> repository.lockVersion(version.key(), version.version() - 1))
                .orElse(null);
        if (previous == null || !previous.path("endedBy").isObject()) {
            throw staleVersion(version);
        }
    }

    private static LocationCodeException staleVersion(CodeVersion version) {
        return new LocationCodeException(CONFLICT, "staleCode", version.key() + " version " + version.version()
                + " was changed by someone else, or is already registered; reload and check it again");
    }

    /** A PLC is closed once the last day of its latest version has passed. */
    private ParentState parentState(String primaryCode) {
        String today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
        return repository.currentEndValidity(primaryCode)
                .map(end -> end.isEmpty() || end.compareTo(today) >= 0 ? ParentState.ACTIVE : ParentState.CLOSED)
                .orElse(ParentState.NOT_REGISTERED);
    }

    private static RequestEntry checkedRequest(String pathId, JsonNode document) {
        RequestEntry request = JsonDocuments.request(document);
        List<String> problems = new ArrayList<>(LocationCodeRules.requestProblems(request));
        if (pathId != null && !pathId.equals(request.id())) {
            problems.add("id in the body (" + request.id() + ") differs from the URL (" + pathId + ")");
        }
        if (!problems.isEmpty()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidRequest", problems);
        }
        return request;
    }

    private static List<JsonNode> versionDocuments(JsonNode array) {
        if (!array.isArray()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidJson", "codes must be a JSON array");
        }
        List<JsonNode> documents = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode document : array) {
            CodeVersion version = JsonDocuments.version(document);
            String id = version.key() + "#" + version.version();
            LocationCodeRules.versionProblems(version).forEach(problem -> problems.add(id + ": " + problem));
            if (!ids.add(id)) {
                problems.add(id + ": appears twice in one save");
            }
            documents.add(document);
        }
        if (!problems.isEmpty()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidCode", problems);
        }
        return documents;
    }

    private static Map<Integer, Integer> sequences(JsonNode node) {
        Map<Integer, Integer> sequences = new LinkedHashMap<>();
        if (node.isMissingNode() || node.isNull()) {
            return sequences;
        }
        if (!node.isObject()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidBackup", "sequences must be an object");
        }
        Iterator<String> years = node.fieldNames();
        while (years.hasNext()) {
            String year = years.next();
            JsonNode value = node.get(year);
            if (!year.matches("^\\d{4}$") || !value.canConvertToInt() || value.asInt() < 0) {
                throw new LocationCodeException(BAD_REQUEST, "invalidBackup", "sequence " + year + " is not valid");
            }
            sequences.put(Integer.parseInt(year), value.asInt());
        }
        return sequences;
    }

    private void raiseSequencesToReferences(List<RequestEntry> requests) {
        Map<Integer, Integer> highest = new HashMap<>();
        for (RequestEntry request : requests) {
            String reference = request.reference();
            if (reference != null && reference.matches("^LC-\\d{4}-\\d+(/\\d+)?$")) {
                String[] parts = reference.split("-");
                // A bulk line's reference ends with its line, "LC-2026-0007/3".
                highest.merge(Integer.parseInt(parts[1]), Integer.parseInt(parts[2].split("/")[0]), Math::max);
            }
        }
        highest.forEach(repository::raiseReferenceSequence);
    }

    private <T> T inTransactionReturning(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private void inTransaction(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    private static void requireDate(String value, String name) {
        try {
            LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new LocationCodeException(BAD_REQUEST, "invalidDate", name + " must be YYYY-MM-DD");
        }
    }

    private static String principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }
}
