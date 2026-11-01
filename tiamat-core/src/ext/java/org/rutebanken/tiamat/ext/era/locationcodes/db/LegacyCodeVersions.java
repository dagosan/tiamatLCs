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
 * Added for the ERA Location Code Manager, 2026-11-02.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;
import org.rutebanken.tiamat.ext.era.locationcodes.model.AsciiName;

import java.time.LocalDate;

/**
 * A register entry saved before 2026-11-02 (one per code) as its first and
 * only version; same as the frontend's model/legacyCodes.ts. The request
 * that assigned it opened it, a closure ended it; amendments in between are
 * in the change log, not here.
 */
public final class LegacyCodeVersions {

    private LegacyCodeVersions() {
    }

    public static ObjectNode versionOf(JsonNode legacy) {
        JsonNode history = legacy.path("history");
        JsonNode first = history.size() > 0 ? history.get(0) : null;
        JsonNode last = history.size() > 0 ? history.get(history.size() - 1) : null;
        String name = text(legacy, "name");
        String validFrom = text(legacy, "validFrom");
        String closedFrom = text(legacy, "closedFrom");
        ObjectNode version = JsonDocuments.MAPPER.createObjectNode();
        version.put("key", text(legacy, "key"));
        version.put("version", 1);
        version.put("codeType", text(legacy, "codeType"));
        version.put("code", text(legacy, "code"));
        version.set("parentPrimaryCode", legacy.path("parentPrimaryCode").isTextual()
                ? legacy.get("parentPrimaryCode") : JsonDocuments.MAPPER.nullNode());
        version.put("subsidiaryTypeCode", text(legacy, "subsidiaryTypeCode"));
        version.put("countryIsoCode", text(legacy, "country"));
        version.put("startValidity", validFrom.isEmpty() ? null : validFrom);
        version.put("endValidity", closedFrom.isEmpty() ? null : LocalDate.parse(closedFrom).minusDays(1).toString());
        version.put("responsibleImCode", text(legacy, "imOrganisationCode"));
        version.put("locationName", name);
        version.put("locationNameAscii", AsciiName.of(name));
        version.put("nutsCode", "");
        version.put("containerHandlingFlag", false);
        version.put("handoverPointFlag", false);
        version.put("freightPossibleFlag", false);
        version.putNull("freightStartValidity");
        version.putNull("freightEndValidity");
        version.put("passengerPossibleFlag", false);
        version.putNull("passengerStartValidity");
        version.putNull("passengerEndValidity");
        version.putNull("latitude");
        version.putNull("longitude");
        version.put("freeText", "");
        version.put("addDate", first == null ? "" : text(first, "at"));
        version.put("modDate", last == null ? "" : text(last, "at"));
        version.set("rinfLink", legacy.path("rinfLink").isObject()
                ? legacy.get("rinfLink").deepCopy() : JsonDocuments.MAPPER.nullNode());
        boolean opens = first != null && "assigned".equals(text(first, "change"));
        boolean closes = last != null && "closed".equals(text(last, "change"));
        version.set("openedBy", opens ? first.deepCopy() : JsonDocuments.MAPPER.nullNode());
        version.set("endedBy", closes ? last.deepCopy() : JsonDocuments.MAPPER.nullNode());
        return version;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }
}
