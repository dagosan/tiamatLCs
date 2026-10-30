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
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules.Extension;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.HistoryEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RinfLink;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationCodeRulesTest {

    static final HistoryEntry ASSIGNED = new HistoryEntry("2026-10-29T10:00:00Z", "r1", "LC-2026-0001", "assigned");
    static final HistoryEntry AMENDED = new HistoryEntry("2026-11-02T10:00:00Z", "r2", "LC-2026-0002", "amended");
    static final RinfLink GEMBLOUX = new RinfLink("operationalPoint", "BELGB");

    static CodeEntry plc(String code, RinfLink link, List<HistoryEntry> history) {
        return new CodeEntry(code, "primary", code, null, code.substring(0, 2), link, "2026-10-29", null, history);
    }

    static CodeEntry slc(String parent, String code, RinfLink link, List<HistoryEntry> history) {
        return new CodeEntry(parent + "/" + code, "subsidiary", code, parent, parent.substring(0, 2), link,
                "2026-10-29", null, history);
    }

    @Test
    void acceptsAWellFormedPrimaryCode() {
        assertEquals(List.of(), LocationCodeRules.codeProblems(plc("SE00001", GEMBLOUX, List.of(ASSIGNED))));
    }

    @Test
    void acceptsBorderPointsAndKgStyleSubsidiaryCodes() {
        assertEquals(List.of(), LocationCodeRules.codeProblems(plc("EU00010", GEMBLOUX, List.of(ASSIGNED))));
        assertEquals(List.of(), LocationCodeRules.codeProblems(slc("NO00922", "NO-S.1", null, List.of(ASSIGNED))));
    }

    @Test
    void refusesMalformedPrimaryCodes() {
        CodeEntry fourDigits = new CodeEntry("BE0438", "primary", "BE0438", null, "BE", GEMBLOUX, "", null,
                List.of(ASSIGNED));
        assertTrue(LocationCodeRules.codeProblems(fourDigits).get(0).contains("two letters and five digits"));
        CodeEntry wrongCountry = new CodeEntry("BE00438", "primary", "BE00438", null, "FR", GEMBLOUX, "", null,
                List.of(ASSIGNED));
        assertEquals(List.of("country must be the code's prefix"), LocationCodeRules.codeProblems(wrongCountry));
    }

    @Test
    void refusesASubsidiaryKeyThatDoesNotMatch() {
        CodeEntry code = new CodeEntry("BE00438/1", "subsidiary", "2", "BE00438", "BE", null, "", null,
                List.of(ASSIGNED));
        assertEquals(List.of("key of an SLC must be \"<parent PLC>/<SLC>\""), LocationCodeRules.codeProblems(code));
    }

    @Test
    void aPrimaryCodeCannotSitOnAnAsset() {
        RinfLink track = new RinfLink("asset", "http://data.europa.eu/949/track/0088_BELGB_Gembloux-voie-III");
        assertEquals(1, LocationCodeRules.codeProblems(plc("BE00999", track, List.of(ASSIGNED))).size());
        assertEquals(List.of(), LocationCodeRules.codeProblems(slc("BE00438", "0000000001", track, List.of(ASSIGNED))));
    }

    @Test
    void refusesLinksOutsideTheEraNamespace() {
        RinfLink elsewhere = new RinfLink("asset", "http://example.org/x> } DROP ALL {");
        assertEquals(1, LocationCodeRules.codeProblems(slc("BE00438", "1", elsewhere, List.of(ASSIGNED))).size());
    }

    @Test
    void aCodeIsAssignedOnlyOnce() {
        List<String> problems = LocationCodeRules.codeProblems(plc("BE00999", GEMBLOUX,
                List.of(ASSIGNED, new HistoryEntry("2027-01-01T00:00:00Z", "r9", null, "assigned"))));
        assertEquals(List.of("a code is assigned once, as its first history entry (no re-use)"), problems);
    }

    @Test
    void refusesEmptyHistoryAndBadDates() {
        CodeEntry code = new CodeEntry("BE00999", "primary", "BE00999", null, "BE", GEMBLOUX, "29/10/2026", null,
                List.of());
        assertEquals(List.of("validFrom is not a date (YYYY-MM-DD)", "history is empty"),
                LocationCodeRules.codeProblems(code));
    }

    @Test
    void savesMustExtendTheStoredHistory() {
        assertEquals(Extension.ADDS, LocationCodeRules.extension(List.of(ASSIGNED), List.of(ASSIGNED, AMENDED)));
        assertEquals(Extension.SAME, LocationCodeRules.extension(List.of(ASSIGNED), List.of(ASSIGNED)));
        assertEquals(Extension.STALE, LocationCodeRules.extension(List.of(ASSIGNED, AMENDED), List.of(ASSIGNED)));
        assertEquals(Extension.STALE, LocationCodeRules.extension(List.of(ASSIGNED), List.of(AMENDED)));
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
