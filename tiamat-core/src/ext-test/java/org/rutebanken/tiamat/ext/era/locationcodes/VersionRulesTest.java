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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.db.LegacyCodeVersions;
import org.rutebanken.tiamat.ext.era.locationcodes.model.AsciiName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.ASSIGNED;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.CLOSED;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.GEMBLOUX;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.ended;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.plcDocument;

class VersionRulesTest {

    private static List<String> problemsOf(ObjectNode document) {
        return VersionRules.problems(JsonDocuments.version(document));
    }

    @Test
    void checksCrdsColumns() {
        ObjectNode version = plcDocument("BE00500", GEMBLOUX, ASSIGNED);
        version.put("locationNameAscii", "Grimsås");
        version.put("nutsCode", "be-352");
        version.put("latitude", 95.0);
        version.putNull("longitude");
        version.put("freeText", "x".repeat(256));
        assertEquals(List.of(
                "latitude and longitude go together",
                "latitude is not between -90 and 90",
                "locationNameAscii is not printable ASCII",
                "nutsCode is not 1 to 5 capital letters or digits",
                "freeText is longer than 255 characters"), problemsOf(version));
    }

    @Test
    void flagsAreYesOrNoAndUsesHaveDatesOnlyWhenPossible() {
        ObjectNode version = plcDocument("BE00500", GEMBLOUX, ASSIGNED);
        version.remove("handoverPointFlag");
        version.put("freightStartValidity", "2026-10-29");
        assertEquals(List.of("handoverPointFlag must be true or false",
                "freight validity dates without freightPossibleFlag"), problemsOf(version));
    }

    @Test
    void anEndedVersionHasALastDay() {
        ObjectNode version = plcDocument("BE00500", GEMBLOUX, ASSIGNED);
        ObjectNode closed = ended(version, "2026-11-30", CLOSED);
        assertEquals(List.of(), problemsOf(closed));
        closed.putNull("endValidity");
        assertEquals(List.of("an ended version has an endValidity"), problemsOf(closed));
    }

    @Test
    void aStoredVersionOnlyChangesByEndingOnce() {
        ObjectNode stored = plcDocument("BE00500", GEMBLOUX, ASSIGNED);
        ObjectNode closed = ended(stored, "2026-11-30", CLOSED);
        assertEquals(VersionRules.Update.SAME, VersionRules.update(stored, stored.deepCopy()));
        assertEquals(VersionRules.Update.ENDS, VersionRules.update(stored, closed));
        ObjectNode renamed = closed.deepCopy();
        renamed.put("locationName", "Elsewhere");
        assertEquals(VersionRules.Update.STALE, VersionRules.update(stored, renamed));
        ObjectNode closedAgain = ended(closed, "2026-12-31", CLOSED);
        assertEquals(VersionRules.Update.STALE, VersionRules.update(closed, closedAgain));
        ObjectNode changedOpen = stored.deepCopy();
        changedOpen.put("nutsCode", "BE353");
        assertEquals(VersionRules.Update.STALE, VersionRules.update(stored, changedOpen));
    }

    @Test
    void makesCrdsAsciiName() {
        assertEquals("Grimsas", AsciiName.of("Grimsås"));
        assertEquals("Hst ausser Betrieb", AsciiName.of("Hst außer Betrieb"));
        assertEquals("Poznan Glowny", AsciiName.of("Poznań Główny"));
        assertEquals("Koge Nord", AsciiName.of("Køge Nord"));
        assertTrue(AsciiName.isAscii("Dobova d.m."));
    }

    @Test
    void turnsAnEntrySavedBeforeVersionsIntoVersionOne() {
        ObjectNode legacy = (ObjectNode) JsonDocuments.parse(("{'key':'SE00001','codeType':'primary','code':'SE00001',"
                + "'parentPrimaryCode':null,'country':'SE','name':'Myrbäcken','imOrganisationCode':'0074',"
                + "'subsidiaryTypeCode':'','rinfLink':{'kind':'operationalPoint','canonicalId':'SE00111','label':'x'},"
                + "'validFrom':'2026-10-30','closedFrom':'2026-12-13','history':["
                + "{'at':'2026-10-30T10:00:00.000Z','requestId':'a','reference':'LC-2026-0001','change':'assigned'},"
                + "{'at':'2026-11-02T10:00:00.000Z','requestId':'b','reference':'LC-2026-0005','change':'closed'}]}")
                .replace('\'', '"'));
        ObjectNode version = LegacyCodeVersions.versionOf(legacy);
        assertEquals(List.of(), LocationCodeRules.versionProblems(JsonDocuments.version(version)));
        assertEquals("SE", version.get("countryIsoCode").asText());
        assertEquals("2026-12-12", version.get("endValidity").asText());
        assertEquals("Myrbacken", version.get("locationNameAscii").asText());
        assertEquals("0074", version.get("responsibleImCode").asText());
        assertEquals("a", version.get("openedBy").get("requestId").asText());
        assertEquals("b", version.get("endedBy").get("requestId").asText());
    }
}
