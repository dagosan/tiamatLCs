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
 * Modified 2026-11-02: code versions with CRD's columns.
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules.Extension;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RinfLink;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.AMENDED;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.ASSIGNED;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.GEMBLOUX;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.plc;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.plcDocument;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.slc;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.slcDocument;

class LocationCodeRulesTest {

    private static List<String> problemsOf(ObjectNode document) {
        return LocationCodeRules.versionProblems(JsonDocuments.version(document));
    }

    @Test
    void acceptsAWellFormedPrimaryCode() {
        assertEquals(List.of(), LocationCodeRules.versionProblems(plc("SE00001", GEMBLOUX, ASSIGNED)));
    }

    @Test
    void acceptsBorderPointsAndKgStyleSubsidiaryCodes() {
        assertEquals(List.of(), LocationCodeRules.versionProblems(plc("EU00010", GEMBLOUX, ASSIGNED)));
        assertEquals(List.of(), LocationCodeRules.versionProblems(slc("NO00922", "NO-S.1", null, ASSIGNED)));
    }

    @Test
    void refusesMalformedPrimaryCodes() {
        ObjectNode fourDigits = plcDocument("BE00438", GEMBLOUX, ASSIGNED);
        fourDigits.put("key", "BE0438");
        fourDigits.put("code", "BE0438");
        assertTrue(problemsOf(fourDigits).get(0).contains("two letters and five digits"));
        ObjectNode wrongCountry = plcDocument("BE00438", GEMBLOUX, ASSIGNED);
        wrongCountry.put("countryIsoCode", "FR");
        assertEquals(List.of("country must be the code's prefix"), problemsOf(wrongCountry));
    }

    @Test
    void refusesASubsidiaryKeyThatDoesNotMatch() {
        ObjectNode code = slcDocument("BE00438", "2", null, ASSIGNED);
        code.put("key", "BE00438/1");
        assertEquals(List.of("key of an SLC must be \"<parent PLC>/<SLC>\""), problemsOf(code));
    }

    @Test
    void aPrimaryCodeCannotSitOnAnAsset() {
        RinfLink track = new RinfLink("asset", "http://data.europa.eu/949/track/0088_BELGB_Gembloux-voie-III");
        assertEquals(1, LocationCodeRules.versionProblems(plc("BE00999", track, ASSIGNED)).size());
        assertEquals(List.of(), LocationCodeRules.versionProblems(slc("BE00438", "0000000001", track, ASSIGNED)));
    }

    @Test
    void refusesLinksOutsideTheEraNamespace() {
        RinfLink elsewhere = new RinfLink("asset", "http://example.org/x> } DROP ALL {");
        assertEquals(1, LocationCodeRules.versionProblems(slc("BE00438", "1", elsewhere, ASSIGNED)).size());
    }

    @Test
    void aCodeIsAssignedOnlyOnce() {
        ObjectNode second = plcDocument("BE00999", GEMBLOUX, ASSIGNED);
        second.put("version", 2);
        assertEquals(List.of("a later version is opened by an amendment (codes are not re-assigned)"),
                problemsOf(second));
        ObjectNode amended = plcDocument("BE00999", GEMBLOUX, AMENDED);
        assertEquals(List.of("version 1 is opened by an assignment"), problemsOf(amended));
    }

    @Test
    void refusesBadDates() {
        ObjectNode code = plcDocument("BE00999", GEMBLOUX, ASSIGNED);
        code.put("startValidity", "29/10/2026");
        assertEquals(List.of("startValidity is not a date (YYYY-MM-DD)"), problemsOf(code));
        ObjectNode backwards = plcDocument("BE00999", GEMBLOUX, ASSIGNED);
        backwards.put("endValidity", "2026-10-28");
        assertEquals(List.of("endValidity is before startValidity"), problemsOf(backwards));
    }

    @Test
    void savesOfARequestMustExtendItsEvents() {
        assertEquals(Extension.ADDS, LocationCodeRules.extension(List.of("submit"), List.of("submit", "register")));
        assertEquals(Extension.SAME, LocationCodeRules.extension(List.of("submit"), List.of("submit")));
        assertEquals(Extension.STALE, LocationCodeRules.extension(List.of("submit", "register"), List.of("submit")));
        assertEquals(Extension.STALE, LocationCodeRules.extension(List.of("submit"), List.of("register")));
    }

    @Test
    void checksRequests() {
        RequestEntry good = new RequestEntry("r1", null, "submitted", "primary", "newCode", "BE",
                "2026-10-29T10:00:00Z", "A. Requester", 1);
        assertEquals(List.of(), LocationCodeRules.requestProblems(good));
        RequestEntry bad = new RequestEntry("r1", null, "lost", "tertiary", "guess", "", null, null, 0);
        assertEquals(6, LocationCodeRules.requestProblems(bad).size());
    }

    @Test
    void aReferenceOnceGivenCannotChange() {
        RequestEntry stored = new RequestEntry("r1", "LC-2026-0001", "registered", "primary", "newCode", "BE",
                "2026-10-29T10:00:00Z", "A", 2);
        RequestEntry renumbered = new RequestEntry("r1", "LC-2026-0002", "registered", "primary", "newCode", "BE",
                "2026-10-29T10:00:00Z", "A", 3);
        assertEquals(List.of("reference LC-2026-0001 cannot change"),
                LocationCodeRules.requestChangeProblems(stored, renumbered));
    }
}
