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
 * Added for the ERA Location Code Manager, 2026-10-30.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.AMENDED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.ASSIGNED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CLOSED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.PRIMARY;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.SUBSIDIARY;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.array;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.hasValue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.normalisedPrimaryCode;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.object;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.trimmed;

/**
 * The register entries a published decision creates or changes (procedure
 * step 7); same as the frontend's workflow/publication.ts. Closing a PLC with
 * its dependants also closes its SLCs, from the register and from the KG.
 */
public final class Publication {

    private static final String DEACTIVATION = "deactivation";
    private static final String AMENDMENT = "amendment";
    private static final int COUNTRY_LENGTH = 2;
    private static final int ISO_DATE_LENGTH = 10;

    private Publication() {
    }

    /**
     * @param request              the request, with its decision, being published
     * @param registered           the register entry with a key, if any
     * @param activeSubsidiaries   SLCs the register holds under the PLC being closed, active today
     * @param kgSubsidiaryCodes    SLC values the KG links to that PLC
     * @param now                  when, as an ISO instant
     */
    public static List<ObjectNode> registerChanges(
            JsonNode request,
            Function<String, Optional<JsonNode>> registered,
            List<JsonNode> activeSubsidiaries,
            List<String> kgSubsidiaryCodes,
            String now) {
        String code = text(request.path("decision"), "code");
        if (code == null || code.isBlank()) {
            return List.of();
        }
        JsonNode data = request.path("data");
        if (RequestRouting.isCreation(text(data, "operation"))) {
            return List.of(createdEntry(request, code, now));
        }
        ObjectNode entry = registered.apply(AssignedCodes.keyOf(request, code))
                .map(stored -> (ObjectNode) stored.deepCopy())
                .orElseGet(() -> entryFromKg(request, code));
        if (AMENDMENT.equals(text(data, "operation"))) {
            return List.of(amended(request, entry, now));
        }
        List<ObjectNode> changes = new ArrayList<>();
        changes.add(closed(request, entry, now));
        parentWhoseDependantsClose(request).ifPresent(parent ->
                changes.addAll(dependants(request, parent, activeSubsidiaries, kgSubsidiaryCodes, now)));
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

    private static List<ObjectNode> dependants(JsonNode request, String parent,
            List<JsonNode> activeSubsidiaries, List<String> kgSubsidiaryCodes, String now) {
        List<ObjectNode> changes = new ArrayList<>();
        Set<String> registeredValues = new HashSet<>();
        for (JsonNode subsidiary : activeSubsidiaries) {
            registeredValues.add(text(subsidiary, "code"));
            changes.add(closed(request, (ObjectNode) subsidiary.deepCopy(), now));
        }
        for (String value : kgSubsidiaryCodes) {
            if (registeredValues.add(value)) {
                changes.add(closed(request, kgEntry(SUBSIDIARY, value, parent), now));
            }
        }
        return changes;
    }

    private static ObjectNode createdEntry(JsonNode request, String code, String now) {
        JsonNode data = request.path("data");
        ObjectNode entry = entryFromKg(request, code);
        entry.put("name", trimmed(data, "locationName"));
        entry.put("imOrganisationCode", trimmed(data, "imOrganisationCode").toUpperCase(Locale.ROOT));
        entry.put("subsidiaryTypeCode", trimmed(data, "subsidiaryTypeCode"));
        entry.set("rinfLink", data.path("rinfLink").isObject() ? data.get("rinfLink").deepCopy() : NullNode.getInstance());
        entry.put("validFrom", now.substring(0, ISO_DATE_LENGTH));
        entry.set("history", array().add(historyEntry(request, ASSIGNED, now)));
        return entry;
    }

    private static ObjectNode amended(JsonNode request, ObjectNode entry, String now) {
        JsonNode data = request.path("data");
        String name = trimmed(data, "locationName");
        String im = trimmed(data, "imOrganisationCode").toUpperCase(Locale.ROOT);
        if (!name.isEmpty()) {
            entry.put("name", name);
        }
        if (!im.isEmpty()) {
            entry.put("imOrganisationCode", im);
        }
        if (data.path("rinfLink").isObject()) {
            entry.set("rinfLink", data.get("rinfLink").deepCopy());
        }
        history(entry).add(historyEntry(request, AMENDED, now));
        return entry;
    }

    private static ObjectNode closed(JsonNode request, ObjectNode entry, String now) {
        entry.put("closedFrom", text(request.path("data"), "effectiveDate"));
        history(entry).add(historyEntry(request, CLOSED, now));
        return entry;
    }

    /** A register entry for a code known only from the KG, so it can change. */
    private static ObjectNode entryFromKg(JsonNode request, String code) {
        JsonNode data = request.path("data");
        boolean primary = PRIMARY.equals(text(data, "codeType"));
        String value = primary ? normalisedPrimaryCode(code) : code.trim();
        return kgEntry(primary ? PRIMARY : SUBSIDIARY, value,
                primary ? null : normalisedPrimaryCode(text(data, "parentPrimaryCode")));
    }

    private static ObjectNode kgEntry(String codeType, String code, String parent) {
        boolean primary = PRIMARY.equals(codeType);
        ObjectNode entry = object();
        entry.put("key", primary ? code : parent + "/" + code);
        entry.put("codeType", codeType);
        entry.put("code", code);
        entry.put("parentPrimaryCode", parent);
        entry.put("country", countryOf(primary ? code : parent));
        entry.put("name", "");
        entry.put("imOrganisationCode", "");
        entry.put("subsidiaryTypeCode", "");
        entry.putNull("rinfLink");
        entry.put("validFrom", "");
        entry.putNull("closedFrom");
        entry.set("history", array());
        return entry;
    }

    private static String countryOf(String primaryCode) {
        return primaryCode.length() < COUNTRY_LENGTH ? primaryCode : primaryCode.substring(0, COUNTRY_LENGTH);
    }

    private static ArrayNode history(ObjectNode entry) {
        if (!entry.path("history").isArray()) {
            entry.set("history", array());
        }
        return (ArrayNode) entry.get("history");
    }

    private static ObjectNode historyEntry(JsonNode request, String change, String now) {
        ObjectNode entry = object();
        entry.put("at", now);
        entry.put("requestId", text(request, "id"));
        entry.put("reference", hasValue(request, "reference") ? text(request, "reference") : null);
        entry.put("change", change);
        return entry;
    }
}
