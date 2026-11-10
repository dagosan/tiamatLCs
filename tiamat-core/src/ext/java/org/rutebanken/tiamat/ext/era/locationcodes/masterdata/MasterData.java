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
 * Added for the ERA Location Code Manager, 2026-11-10.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The form of the master data documents (LCMS specification 5.2 and 5.5, FR-27):
 * a country for which codes are assigned, and a subsidiary location code type.
 * Same documents and rules as the frontend's model/masterData.ts.
 */
public final class MasterData {

    public static final int FIRST_LOCATION_NUMBER = 1;
    public static final int LAST_LOCATION_NUMBER = 99999;
    private static final int NAME_MAX_LENGTH = 100;

    private static final Pattern ISO_CODE = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern UIC_CODE = Pattern.compile("^[0-9]{2}$");
    private static final Pattern TYPE_CODE = Pattern.compile("^[0-9]{2}$");

    /** Parties TD 103, Table 6 names as entitled to request a type (frontend: EntitledParty). */
    public static final Set<String> REQUESTER_ROLES = Set.of(
            "infrastructureManager", "railwayUndertaking", "stationManager", "terminalManager", "owner");

    private MasterData() {
    }

    /** A country document with exactly the known fields, in their stored form. */
    public static ObjectNode country(JsonNode source) {
        ObjectNode country = JsonDocuments.MAPPER.createObjectNode();
        country.put("isoCode", text(source, "isoCode"));
        String uicCode = text(source, "uicCode");
        if (uicCode.isEmpty()) {
            country.putNull("uicCode");
        } else {
            country.put("uicCode", uicCode);
        }
        country.put("name", text(source, "name"));
        country.set("euMemberState", source.path("euMemberState"));
        country.set("plcRangeStart", source.path("plcRangeStart"));
        country.set("plcRangeEnd", source.path("plcRangeEnd"));
        putDate(country, "validFrom", source);
        putDate(country, "validTo", source);
        return country;
    }

    public static List<String> countryProblems(JsonNode country) {
        List<String> problems = new ArrayList<>();
        if (!ISO_CODE.matcher(text(country, "isoCode")).matches()) {
            problems.add("isoCode must be two capital letters");
        }
        String uicCode = text(country, "uicCode");
        if (!uicCode.isEmpty() && !UIC_CODE.matcher(uicCode).matches()) {
            problems.add("uicCode must be two digits, or empty");
        }
        problems.addAll(nameProblems(country));
        if (!country.path("euMemberState").isBoolean()) {
            problems.add("euMemberState must be true or false");
        }
        JsonNode start = country.path("plcRangeStart");
        JsonNode end = country.path("plcRangeEnd");
        if (!isLocationNumber(start) || !isLocationNumber(end)) {
            problems.add("plcRangeStart and plcRangeEnd must be whole numbers from " + FIRST_LOCATION_NUMBER
                    + " to " + LAST_LOCATION_NUMBER);
        } else if (start.asInt() > end.asInt()) {
            problems.add("plcRangeStart is after plcRangeEnd");
        }
        problems.addAll(validityProblems(country));
        return problems;
    }

    /** A subsidiary type document with exactly the known fields. */
    public static ObjectNode subsidiaryType(JsonNode source) {
        ObjectNode type = JsonDocuments.MAPPER.createObjectNode();
        type.put("code", text(source, "code"));
        type.put("name", text(source, "name"));
        ArrayNode roles = type.putArray("requesterRoles");
        source.path("requesterRoles").forEach(role -> roles.add(role.asText("")));
        type.set("retailRelated", source.path("retailRelated"));
        putDate(type, "validFrom", source);
        putDate(type, "validTo", source);
        return type;
    }

    public static List<String> subsidiaryTypeProblems(JsonNode type) {
        List<String> problems = new ArrayList<>();
        if (!TYPE_CODE.matcher(text(type, "code")).matches()) {
            problems.add("code must be two digits");
        }
        problems.addAll(nameProblems(type));
        JsonNode roles = type.path("requesterRoles");
        if (!roles.isArray()) {
            problems.add("requesterRoles must be a list");
        } else {
            roles.forEach(role -> {
                if (!REQUESTER_ROLES.contains(role.asText(""))) {
                    problems.add("unknown requester role \"" + role.asText("") + "\"");
                }
            });
        }
        if (!type.path("retailRelated").isBoolean()) {
            problems.add("retailRelated must be true or false");
        }
        problems.addAll(validityProblems(type));
        return problems;
    }

    /** Whether a country or type is valid on `day`: no start or started, no end or not yet passed. */
    public static boolean validOn(JsonNode document, LocalDate day) {
        String from = text(document, "validFrom");
        String to = text(document, "validTo");
        String date = day.toString();
        return (from.isEmpty() || from.compareTo(date) <= 0) && (to.isEmpty() || to.compareTo(date) >= 0);
    }

    /** Whether a location number lies in the country's range for assignment (BR-10). */
    public static boolean inRange(JsonNode country, int locationNumber) {
        return locationNumber >= country.path("plcRangeStart").asInt(FIRST_LOCATION_NUMBER)
                && locationNumber <= country.path("plcRangeEnd").asInt(LAST_LOCATION_NUMBER);
    }

    private static List<String> nameProblems(JsonNode document) {
        String name = text(document, "name");
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
            return List.of("name must have 1 to " + NAME_MAX_LENGTH + " characters");
        }
        return List.of();
    }

    private static List<String> validityProblems(JsonNode document) {
        List<String> problems = new ArrayList<>();
        String from = text(document, "validFrom");
        String to = text(document, "validTo");
        if (!isBlankOrDate(from) || !isBlankOrDate(to)) {
            problems.add("validFrom and validTo must be YYYY-MM-DD, or empty");
        } else if (!from.isEmpty() && !to.isEmpty() && to.compareTo(from) < 0) {
            problems.add("validTo is before validFrom");
        }
        return problems;
    }

    private static boolean isLocationNumber(JsonNode value) {
        return value.isIntegralNumber() && value.asInt() >= FIRST_LOCATION_NUMBER
                && value.asInt() <= LAST_LOCATION_NUMBER;
    }

    private static boolean isBlankOrDate(String value) {
        if (value.isEmpty()) {
            return true;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static void putDate(ObjectNode target, String field, JsonNode source) {
        String value = text(source, field);
        if (value.isEmpty()) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? "" : value.asText("").trim();
    }
}
