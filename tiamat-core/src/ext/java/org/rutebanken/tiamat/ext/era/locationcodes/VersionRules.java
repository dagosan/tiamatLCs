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

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.model.AsciiName;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeVersion;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.VersionChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules.isBlank;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules.isBlankOrDate;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.AMENDED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.ASSIGNED;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.ENDING_CHANGES;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.OPENING_CHANGES;

/**
 * Rules for a code version beyond its code: CRD's columns (dates, flags,
 * position, ASCII name, NUTS code, free text), the requests that opened and
 * ended it, and how a stored version may change (only by ending, once).
 */
public final class VersionRules {

    /** NUTS_Code as CRD holds it: NUTS 3 ("AT112") or an older national code ("3099"). */
    static final Pattern NUTS_CODE = Pattern.compile("^[A-Z0-9]{1,5}$");
    static final int FREE_TEXT_MAX_LENGTH = 255;
    private static final double MAX_LATITUDE = 90;
    private static final double MAX_LONGITUDE = 180;
    /** What ending a version changes; everything else in a stored version is fixed. */
    private static final List<String> ENDING_FIELDS =
            List.of("endValidity", "endedBy", "freightEndValidity", "passengerEndValidity", "modDate");

    /** How a save of a version relates to the stored one. */
    public enum Update { SAME, ENDS, STALE }

    private VersionRules() {
    }

    public static List<String> problems(CodeVersion version) {
        List<String> problems = new ArrayList<>();
        if (version.version() < 1) {
            problems.add("version must be 1 or more");
        }
        problems.addAll(dateProblems(version));
        problems.addAll(flagProblems(version));
        problems.addAll(columnProblems(version));
        problems.addAll(changeProblems(version));
        return problems;
    }

    private static List<String> dateProblems(CodeVersion version) {
        List<String> problems = new ArrayList<>();
        Map<String, String> dates = new TreeMap<>();
        dates.put("startValidity", version.startValidity());
        dates.put("endValidity", version.endValidity());
        dates.put("freightStartValidity", version.freightStartValidity());
        dates.put("freightEndValidity", version.freightEndValidity());
        dates.put("passengerStartValidity", version.passengerStartValidity());
        dates.put("passengerEndValidity", version.passengerEndValidity());
        dates.forEach((name, value) -> {
            if (!isBlankOrDate(value)) {
                problems.add(name + " is not a date (YYYY-MM-DD)");
            }
        });
        if (!isBlank(version.startValidity()) && !isBlank(version.endValidity())
                && version.endValidity().compareTo(version.startValidity()) < 0) {
            problems.add("endValidity is before startValidity");
        }
        if (isBlank(version.startValidity()) && version.openedBy() != null) {
            problems.add("a version opened by a request has a startValidity");
        }
        return problems;
    }

    private static List<String> flagProblems(CodeVersion version) {
        List<String> problems = new ArrayList<>();
        Map<String, Boolean> flags = new TreeMap<>();
        flags.put("containerHandlingFlag", version.containerHandlingFlag());
        flags.put("handoverPointFlag", version.handoverPointFlag());
        flags.put("freightPossibleFlag", version.freightPossibleFlag());
        flags.put("passengerPossibleFlag", version.passengerPossibleFlag());
        flags.forEach((name, value) -> {
            if (value == null) {
                problems.add(name + " must be true or false");
            }
        });
        if (Boolean.FALSE.equals(version.freightPossibleFlag())
                && (!isBlank(version.freightStartValidity()) || !isBlank(version.freightEndValidity()))) {
            problems.add("freight validity dates without freightPossibleFlag");
        }
        if (Boolean.FALSE.equals(version.passengerPossibleFlag())
                && (!isBlank(version.passengerStartValidity()) || !isBlank(version.passengerEndValidity()))) {
            problems.add("passenger validity dates without passengerPossibleFlag");
        }
        return problems;
    }

    private static List<String> columnProblems(CodeVersion version) {
        List<String> problems = new ArrayList<>();
        Double latitude = version.latitude();
        Double longitude = version.longitude();
        if ((latitude == null) != (longitude == null)) {
            problems.add("latitude and longitude go together");
        }
        if (latitude != null && Math.abs(latitude) > MAX_LATITUDE) {
            problems.add("latitude is not between -90 and 90");
        }
        if (longitude != null && Math.abs(longitude) > MAX_LONGITUDE) {
            problems.add("longitude is not between -180 and 180");
        }
        if (!AsciiName.isAscii(version.locationNameAscii())) {
            problems.add("locationNameAscii is not printable ASCII");
        }
        if (!isBlank(version.nutsCode()) && !NUTS_CODE.matcher(version.nutsCode()).matches()) {
            problems.add("nutsCode is not 1 to 5 capital letters or digits");
        }
        if (version.freeText() != null && version.freeText().length() > FREE_TEXT_MAX_LENGTH) {
            problems.add("freeText is longer than " + FREE_TEXT_MAX_LENGTH + " characters");
        }
        if (isBlank(version.addDate()) || isBlank(version.modDate())) {
            problems.add("addDate and modDate are required");
        }
        return problems;
    }

    private static List<String> changeProblems(CodeVersion version) {
        List<String> problems = new ArrayList<>();
        VersionChange opened = version.openedBy();
        VersionChange ended = version.endedBy();
        if (opened != null) {
            if (!OPENING_CHANGES.contains(opened.change()) || isBlank(opened.at()) || isBlank(opened.requestId())) {
                problems.add("openedBy needs at, requestId and change assigned or amended");
            } else if (version.version() == 1 && !ASSIGNED.equals(opened.change())) {
                problems.add("version 1 is opened by an assignment");
            } else if (version.version() > 1 && !AMENDED.equals(opened.change())) {
                problems.add("a later version is opened by an amendment (codes are not re-assigned)");
            }
        } else if (version.version() > 1) {
            problems.add("a later version is opened by an amendment (codes are not re-assigned)");
        }
        if (ended != null) {
            if (!ENDING_CHANGES.contains(ended.change()) || isBlank(ended.at()) || isBlank(ended.requestId())) {
                problems.add("endedBy needs at, requestId and change amended or closed");
            }
            if (isBlank(version.endValidity())) {
                problems.add("an ended version has an endValidity");
            }
        }
        return problems;
    }

    /**
     * A stored version changes once, when a request ends it (endValidity,
     * endedBy, the uses' end dates, modDate). Anything else, or a second end,
     * means the save was based on an outdated copy.
     */
    public static Update update(JsonNode stored, JsonNode incoming) {
        if (stored.equals(incoming)) {
            return Update.SAME;
        }
        if (stored.path("endedBy").isObject() || !incoming.path("endedBy").isObject()) {
            return Update.STALE;
        }
        return withoutEnding(stored).equals(withoutEnding(incoming)) ? Update.ENDS : Update.STALE;
    }

    private static JsonNode withoutEnding(JsonNode version) {
        ObjectNode copy = (ObjectNode) version.deepCopy();
        ENDING_FIELDS.forEach(copy::remove);
        return copy;
    }
}
