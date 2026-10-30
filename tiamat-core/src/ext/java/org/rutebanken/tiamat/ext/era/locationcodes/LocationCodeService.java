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
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules.Extension;
import org.rutebanken.tiamat.ext.era.locationcodes.RegisterChecks.ParentState;
import org.rutebanken.tiamat.ext.era.locationcodes.kg.KnowledgeGraphUnavailableException;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.AssignedCodes;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.BAD_REQUEST;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.CONFLICT;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.NOT_FOUND;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.UNAVAILABLE;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException.UNPROCESSABLE;

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

    public String requestsJson() {
        return repository.requestsJson();
    }

    public String requestJson(String id) {
        return repository.requestDocument(id).orElseThrow(() ->
                new LocationCodeException(NOT_FOUND, "requestNotFound", "no request " + id));
    }

    public String codesJson(String country, String parentPrimaryCode, String activeOn) {
        if (activeOn != null) {
            requireDate(activeOn, "activeOn");
        }
        return repository.codesJson(country, parentPrimaryCode, activeOn);
    }

    public String codeJson(String key) {
        return repository.codeDocument(key).orElseThrow(() ->
                new LocationCodeException(NOT_FOUND, "codeNotFound", "no code " + key));
    }

    public String changeLogJson(String entity, String key) {
        return repository.changeLogJson(entity, key);
    }

    /**
     * Procedure step 2: {"actor": …, "data": …}. The register gives the
     * request its id and records the submission; returns the request.
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
        ObjectNode request = NewRequests.create(UUID.randomUUID().toString(), body.get("data"), actor, now());
        RequestEntry entry = checkedRequest(null, request);
        inTransaction(() -> writeRequest(entry, request));
        return JsonDocuments.write(request);
    }

    /**
     * One step on a request: {"action", "actor", "payload", "expectedEventCount"}.
     * expectedEventCount is how many events the actor's copy has; if someone
     * else took a step meanwhile, the step is refused (409 staleRequest).
     * Returns {"request": …, "codes": [ register entries the step changed ]}.
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
            ObjectNode updated = RequestTransitions.apply(stored, new Step(step, actor, payload, now, reference));
            RequestEntry entry = checkedRequest(pathId, updated);
            ArrayNode codes = JsonDocuments.MAPPER.createArrayNode();
            if (WorkflowSteps.PUBLISH.equals(step)) {
                codes.addAll(publicationChanges(updated, today, now));
            }
            if (!codes.isEmpty()) {
                writeCodes(codeDocuments(codes), Map.of(entry.id(), entry.operation()));
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
        List<JsonNode> codes = codeDocuments(snapshot.path("codes"));
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
                CodeEntry code = JsonDocuments.code(document);
                Optional<JsonNode> stored = repository.lockCode(code.key());
                if (stored.isEmpty()) {
                    repository.insertCode(code, document);
                    repository.log("code", code.key(), "import", principal(), JsonDocuments.write(document));
                    codesAdded++;
                } else if (!stored.get().equals(document)) {
                    conflicts.add("code " + code.key() + " is stored with other content");
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
        if (problems.isEmpty() && creation && repository.lockCode(key).isPresent()) {
            problems.add(key + " is already in the register");
        }
        if (!problems.isEmpty()) {
            throw new LocationCodeException(UNPROCESSABLE, "codeNotAssignable", problems);
        }
    }

    /** The register entries a publication creates or changes, with the SLCs it closes. */
    private List<ObjectNode> publicationChanges(JsonNode request, LocalDate today, String now) {
        Optional<String> parent = Publication.parentWhoseDependantsClose(request);
        List<JsonNode> activeSubsidiaries = new ArrayList<>();
        List<String> kgSubsidiaryCodes = List.of();
        if (parent.isPresent()) {
            JsonDocuments.parse(repository.codesJson(null, parent.get(), today.toString()))
                    .forEach(activeSubsidiaries::add);
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
        return Publication.registerChanges(request, repository::lockCode, activeSubsidiaries, kgSubsidiaryCodes, now);
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

    private void writeCodes(List<JsonNode> documents, Map<String, String> operationsInThisSave) {
        List<String> problems = new ArrayList<>();
        List<Runnable> writes = new ArrayList<>();
        for (JsonNode document : documents) {
            CodeEntry code = JsonDocuments.code(document);
            JsonNode storedDocument = repository.lockCode(code.key()).orElse(null);
            CodeEntry stored = storedDocument == null ? null : JsonDocuments.code(storedDocument);
            if (stored != null) {
                List<String> changeProblems = LocationCodeRules.codeChangeProblems(stored, code);
                if (!changeProblems.isEmpty()) {
                    throw new LocationCodeException(UNPROCESSABLE, "codeChangeNotAllowed", changeProblems);
                }
                Extension extension = LocationCodeRules.extension(stored.history(), code.history());
                if (extension == Extension.SAME && storedDocument.equals(document)) {
                    continue;
                }
                if (extension != Extension.ADDS) {
                    throw new LocationCodeException(CONFLICT, "staleCode", code.key()
                            + " was changed by someone else, or is already assigned; reload and check it again");
                }
            }
            if (registerChecks != null) {
                try {
                    registerChecks.problems(stored, code,
                                    requestId -> Optional.ofNullable(operationsInThisSave.get(requestId))
                                            .or(() -> repository.requestOperation(requestId)),
                                    this::parentState)
                            .forEach(problem -> problems.add(code.key() + ": " + problem));
                } catch (KnowledgeGraphUnavailableException e) {
                    throw new LocationCodeException(UNAVAILABLE, "knowledgeGraphUnavailable",
                            "the Knowledge Graph did not answer, so " + code.key()
                                    + " could not be checked; nothing was saved (" + e.getMessage() + ")");
                }
            }
            boolean create = stored == null;
            writes.add(() -> {
                try {
                    if (create) {
                        repository.insertCode(code, document);
                    } else {
                        repository.updateCode(code, document);
                    }
                } catch (DuplicateKeyException e) {
                    throw new LocationCodeException(CONFLICT, "staleCode", code.key() + " was registered meanwhile");
                }
                repository.log("code", code.key(), create ? "create" : "update", principal(),
                        JsonDocuments.write(document));
            });
        }
        if (!problems.isEmpty()) {
            throw new LocationCodeException(UNPROCESSABLE, "codeRejected", problems);
        }
        writes.forEach(Runnable::run);
    }

    private ParentState parentState(String primaryCode) {
        return repository.closedFrom(primaryCode)
                .map(closedFrom -> closedFrom.isEmpty() || closedFrom.compareTo(LocalDate.now().toString()) > 0
                        ? ParentState.ACTIVE
                        : ParentState.CLOSED)
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

    private static List<JsonNode> codeDocuments(JsonNode array) {
        if (!array.isArray()) {
            throw new LocationCodeException(BAD_REQUEST, "invalidJson", "codes must be a JSON array");
        }
        List<JsonNode> documents = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode document : array) {
            CodeEntry code = JsonDocuments.code(document);
            LocationCodeRules.codeProblems(code).forEach(problem -> problems.add(code.key() + ": " + problem));
            if (!keys.add(String.valueOf(code.key()))) {
                problems.add(code.key() + ": appears twice in one save");
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
            if (reference != null && reference.matches("^LC-\\d{4}-\\d+$")) {
                String[] parts = reference.split("-");
                highest.merge(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Math::max);
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
