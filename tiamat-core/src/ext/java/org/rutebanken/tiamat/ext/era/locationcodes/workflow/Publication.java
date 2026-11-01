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
 * Added for the ERA Location Code Manager, 2026-10-30; code versions as in CRD, 2026-11-02.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.AMENDED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CLOSED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.PRIMARY;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.normalisedPrimaryCode;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;

/**
 * The code versions a published decision creates or changes (procedure
 * step 7); same as the frontend's workflow/publication.ts. A new code gets
 * version 1. An amendment ends the current version the day before its
 * effective date and opens the next; a deactivation ends it. Closing a PLC
 * with its dependants also closes its SLCs, from the register and the KG.
 */
public final class Publication {

    /** The frontend's text key for a change that cannot apply to the current version. */
    public static final String EFFECTIVE_DATE_REFUSAL = "rinf_lc_blocked_effective_date";
    private static final String DEACTIVATION = "deactivation";
    private static final String AMENDMENT = "amendment";
    private static final int ISO_DATE_LENGTH = 10;

    private Publication() {
    }

    /**
     * @param request             the request, with its decision, being published
     * @param currentVersion      the latest stored version of a code, by key
     * @param subsidiaries        latest versions of the SLCs the register holds under the PLC being closed
     * @param kgSubsidiaryCodes   SLC values the KG links to that PLC
     * @param now                 when, as an ISO instant
     * @throws LocationCodeException 422 when the change cannot apply to the current version
     */
    public static List<ObjectNode> registerChanges(
            JsonNode request,
            Function<String, Optional<JsonNode>> currentVersion,
            List<JsonNode> subsidiaries,
            List<String> kgSubsidiaryCodes,
            String now) {
        String code = text(request.path("decision"), "code");
        if (code == null || code.isBlank()) {
            return List.of();
        }
        JsonNode data = request.path("data");
        if (RequestRouting.isCreation(text(data, "operation"))) {
            ObjectNode base = CodeVersions.fromKg(request, code, now);
            return List.of(CodeVersions.opened(request, base, null, now.substring(0, ISO_DATE_LENGTH), now));
        }
        ObjectNode current = currentVersion.apply(CodeVersions.keyOf(request, code))
                .map(stored -> (ObjectNode) stored.deepCopy())
                .orElseGet(() -> CodeVersions.fromKg(request, code, now));
        String effectiveDate = text(data, "effectiveDate");
        requireChangeable(current, effectiveDate);
        if (AMENDMENT.equals(text(data, "operation"))) {
            return List.of(
                    CodeVersions.ended(request, current, effectiveDate, AMENDED, now),
                    CodeVersions.opened(request, current, current, effectiveDate, now));
        }
        List<ObjectNode> changes = new ArrayList<>();
        changes.add(CodeVersions.ended(request, current, effectiveDate, CLOSED, now));
        parentWhoseDependantsClose(request).ifPresent(parent ->
                changes.addAll(dependants(request, parent, subsidiaries, kgSubsidiaryCodes, now)));
        return changes;
    }

    /** Whether publishing this request closes the SLCs of a PLC; the PLC, or empty. */
    public static Optional<String> parentWhoseDependantsClose(JsonNode request) {
        JsonNode data = request.path("data");
        boolean closes = PRIMARY.equals(text(data, "codeType"))
                && DEACTIVATION.equals(text(data, "operation"))
                && data.path("closeDependentCodes").asBoolean(false);
        return closes ? Optional.of(normalisedPrimaryCode(text(data, "targetCode"))) : Optional.empty();
    }

    /** A change applies to an open version, and its new version starts after the current one did. */
    private static void requireChangeable(JsonNode current, String effectiveDate) {
        String start = text(current, "startValidity");
        boolean valid = effectiveDate != null && effectiveDate.matches("^\\d{4}-\\d{2}-\\d{2}$");
        boolean startsLater = start == null || (valid && effectiveDate.compareTo(start) > 0);
        if (!valid || current.path("endedBy").isObject() || !startsLater) {
            throw new LocationCodeException(LocationCodeException.UNPROCESSABLE, "actionNotAllowed",
                    EFFECTIVE_DATE_REFUSAL);
        }
    }

    private static List<ObjectNode> dependants(JsonNode request, String parent,
            List<JsonNode> subsidiaries, List<String> kgSubsidiaryCodes, String now) {
        String effectiveDate = text(request.path("data"), "effectiveDate");
        String today = now.substring(0, ISO_DATE_LENGTH);
        JsonNode subsidiaryRequest = subsidiaryRequest(request, parent);
        List<ObjectNode> changes = new ArrayList<>();
        Set<String> registeredValues = new HashSet<>();
        for (JsonNode subsidiary : subsidiaries) {
            registeredValues.add(text(subsidiary, "code"));
            if (isOpen(subsidiary, today)) {
                changes.add(CodeVersions.ended(request, subsidiary, effectiveDate, CLOSED, now));
            }
        }
        for (String value : kgSubsidiaryCodes) {
            if (registeredValues.add(value)) {
                ObjectNode fromKg = CodeVersions.fromKg(subsidiaryRequest, value, now);
                changes.add(CodeVersions.ended(request, fromKg, effectiveDate, CLOSED, now));
            }
        }
        return changes;
    }

    /** Not ended by a request, and not past its last day. */
    private static boolean isOpen(JsonNode version, String today) {
        String end = text(version, "endValidity");
        return !version.path("endedBy").isObject() && (end == null || end.compareTo(today) >= 0);
    }

    /** The request read as one about an SLC under `parent`, to build its KG version. */
    private static JsonNode subsidiaryRequest(JsonNode request, String parent) {
        ObjectNode copy = request.deepCopy();
        ObjectNode data = (ObjectNode) copy.path("data");
        data.put("codeType", "subsidiary");
        data.put("parentPrimaryCode", parent);
        return copy;
    }
}
