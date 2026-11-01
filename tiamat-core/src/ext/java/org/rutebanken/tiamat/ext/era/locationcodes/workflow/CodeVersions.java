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

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.model.AsciiName;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.AMENDED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.ASSIGNED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CLOSED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.PRIMARY;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.hasValue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.normalisedPrimaryCode;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.object;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.trimmed;

/**
 * Code versions as a published decision opens and ends them; same as the
 * frontend's workflow/codeVersionChanges.ts. A version holds CRD's columns;
 * the request's `crd` part gives those beyond name and IM, blank or null
 * meaning "as before".
 */
final class CodeVersions {

    private static final int COUNTRY_LENGTH = 2;
    private static final int COORDINATE_DECIMALS = 6;

    private CodeVersions() {
    }

    static String keyOf(JsonNode request, String code) {
        return AssignedCodes.keyOf(request, code);
    }

    /** A blank version 1 of a code the register knows only from the KG, so it can end. */
    static ObjectNode fromKg(JsonNode request, String code, String now) {
        JsonNode data = request.path("data");
        boolean primary = PRIMARY.equals(text(data, "codeType"));
        String value = primary ? normalisedPrimaryCode(code) : code.trim();
        String parent = primary ? null : normalisedPrimaryCode(text(data, "parentPrimaryCode"));
        ObjectNode version = object();
        version.put("key", primary ? value : parent + "/" + value);
        version.put("version", 1);
        version.put("codeType", primary ? PRIMARY : "subsidiary");
        version.put("code", value);
        version.put("parentPrimaryCode", parent);
        version.put("subsidiaryTypeCode", "");
        String countrySource = primary ? value : parent;
        version.put("countryIsoCode", countrySource.length() < COUNTRY_LENGTH
                ? countrySource : countrySource.substring(0, COUNTRY_LENGTH));
        version.putNull("startValidity");
        version.putNull("endValidity");
        version.put("responsibleImCode", "");
        version.put("locationName", "");
        version.put("locationNameAscii", "");
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
        version.put("addDate", now);
        version.put("modDate", now);
        version.putNull("rinfLink");
        version.putNull("openedBy");
        version.putNull("endedBy");
        return version;
    }

    /**
     * The version a request opens after `previous` (null: the first), on
     * `base` (the previous version, or a blank one), valid from `start`.
     */
    static ObjectNode opened(JsonNode request, ObjectNode base, JsonNode previous, String start, String now) {
        JsonNode data = request.path("data");
        JsonNode crd = data.path("crd");
        ObjectNode version = base.deepCopy();
        String name = textOr(trimmed(data, "locationName"), text(base, "locationName"));
        boolean nameChanged = !name.equals(text(base, "locationName"));
        version.put("version", previous == null ? 1 : previous.path("version").asInt() + 1);
        version.put("subsidiaryTypeCode", textOr(trimmed(data, "subsidiaryTypeCode"), text(base, "subsidiaryTypeCode")));
        version.put("startValidity", start);
        version.putNull("endValidity");
        version.put("responsibleImCode", textOr(trimmed(data, "imOrganisationCode").toUpperCase(Locale.ROOT),
                text(base, "responsibleImCode")));
        version.put("locationName", name);
        String baseAscii = text(base, "locationNameAscii");
        version.put("locationNameAscii", textOr(trimmed(crd, "locationNameAscii"),
                nameChanged || baseAscii == null || baseAscii.isEmpty() ? AsciiName.of(name) : baseAscii));
        version.put("nutsCode", textOr(trimmed(crd, "nutsCode").toUpperCase(Locale.ROOT), text(base, "nutsCode")));
        version.put("containerHandlingFlag", flagOr(crd, "containerHandlingFlag", base));
        version.put("handoverPointFlag", flagOr(crd, "handoverPointFlag", base));
        boolean freight = flagOr(crd, "freightPossibleFlag", base);
        boolean passenger = flagOr(crd, "passengerPossibleFlag", base);
        version.put("freightPossibleFlag", freight);
        version.put("freightStartValidity", useStart(freight, previous, "freight", start));
        version.putNull("freightEndValidity");
        version.put("passengerPossibleFlag", passenger);
        version.put("passengerStartValidity", useStart(passenger, previous, "passenger", start));
        version.putNull("passengerEndValidity");
        putCoordinate(version, "latitude", coordinate(trimmed(crd, "latitude")), base);
        putCoordinate(version, "longitude", coordinate(trimmed(crd, "longitude")), base);
        version.put("freeText", textOr(trimmed(crd, "freeText"), text(base, "freeText")));
        version.put("addDate", now);
        version.put("modDate", now);
        if (data.path("rinfLink").isObject()) {
            version.set("rinfLink", data.get("rinfLink").deepCopy());
        } else if (!base.path("rinfLink").isObject()) {
            version.set("rinfLink", NullNode.getInstance());
        }
        version.set("openedBy", change(request, previous == null ? ASSIGNED : AMENDED, now));
        version.putNull("endedBy");
        return version;
    }

    /** The version as it ends on `effectiveDate`: its last day is the day before. */
    static ObjectNode ended(JsonNode request, JsonNode current, String effectiveDate, String change, String now) {
        ObjectNode version = current.deepCopy();
        String end = LocalDate.parse(effectiveDate).minusDays(1).toString();
        boolean closing = CLOSED.equals(change);
        version.put("endValidity", end);
        if (closing && current.path("freightPossibleFlag").asBoolean(false) && !hasValue(current, "freightEndValidity")) {
            version.put("freightEndValidity", end);
        }
        if (closing && current.path("passengerPossibleFlag").asBoolean(false)
                && !hasValue(current, "passengerEndValidity")) {
            version.put("passengerEndValidity", end);
        }
        version.put("modDate", now);
        version.set("endedBy", change(request, change, now));
        return version;
    }

    static ObjectNode change(JsonNode request, String change, String now) {
        ObjectNode entry = object();
        entry.put("at", now);
        entry.put("requestId", text(request, "id"));
        entry.put("reference", hasValue(request, "reference") ? text(request, "reference") : null);
        entry.put("change", change);
        return entry;
    }

    /** Start of a use (freight or passenger): kept while it goes on, `start` when it begins. */
    private static String useStart(boolean possible, JsonNode previous, String use, String start) {
        if (!possible) {
            return null;
        }
        boolean wasPossible = previous != null && previous.path(use + "PossibleFlag").asBoolean(false);
        String previousStart = previous == null ? null : text(previous, use + "StartValidity");
        return wasPossible && previousStart != null ? previousStart : start;
    }

    private static boolean flagOr(JsonNode crd, String flag, JsonNode base) {
        JsonNode value = crd.path(flag);
        return value.isBoolean() ? value.asBoolean() : base.path(flag).asBoolean(false);
    }

    private static String textOr(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null ? "" : fallback;
    }

    /** A coordinate as typed ("50,56013" too), rounded to six decimals; null when blank or not a number. */
    static Double coordinate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim().replace(',', '.'))
                    .setScale(COORDINATE_DECIMALS, RoundingMode.HALF_UP).doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void putCoordinate(ObjectNode version, String field, Double value, JsonNode base) {
        if (value != null) {
            version.put(field, value);
        } else if (base.path(field).isNumber()) {
            version.put(field, base.path(field).asDouble());
        } else {
            version.putNull(field);
        }
    }
}
