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
 * Modified 2026-11-02: code versions with CRD's columns.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules;
import org.rutebanken.tiamat.ext.era.locationcodes.TestVersions;
import org.rutebanken.tiamat.ext.era.locationcodes.VersionRules;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowFixtures.*;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.*;

class PublicationTest {

    private static ObjectNode decided(ObjectNode submitted, String code) {
        ObjectNode validated = validated(submitted);
        ObjectNode ready = "rinfHarmonisation".equals(validated.get("status").asText())
                ? take(validated, CONFIRM_POSITION, RITA) : validated;
        return take(take(ready, ASSIGN, VERA, "{'code':'" + code + "'}"), PUBLISH, CARL);
    }

    private static void assertValid(ObjectNode version) {
        assertEquals(List.of(), LocationCodeRules.versionProblems(JsonDocuments.version(version)));
    }

    @Test
    void aNewCodeEntersTheRegisterAsVersionOneWithCrdsColumns() {
        ObjectNode request = newPrimaryCode("SE");
        ObjectNode crd = ((ObjectNode) request.get("data")).putObject("crd");
        crd.put("latitude", "56,391180");
        crd.put("longitude", "12.783443");
        crd.put("nutsCode", "se224");
        crd.put("passengerPossibleFlag", false);
        crd.put("freightPossibleFlag", true);
        crd.put("containerHandlingFlag", true);
        crd.put("handoverPointFlag", false);
        ObjectNode published = decided(request, "SE00001");
        List<ObjectNode> changes = Publication.registerChanges(published, key -> Optional.empty(), List.of(), List.of(), NOW);
        assertEquals(1, changes.size());
        ObjectNode version = changes.get(0);
        assertEquals("SE00001", version.get("key").asText());
        assertEquals(1, version.get("version").asInt());
        assertEquals("SE", version.get("countryIsoCode").asText());
        assertEquals("2026-10-29", version.get("startValidity").asText());
        assertTrue(version.get("endValidity").isNull());
        assertEquals("Myrbacken", version.get("locationNameAscii").asText());
        assertEquals("SE224", version.get("nutsCode").asText());
        assertEquals(56.39118, version.get("latitude").asDouble());
        assertEquals("2026-10-29", version.get("freightStartValidity").asText());
        assertTrue(version.get("passengerStartValidity").isNull());
        assertEquals("SE00111", version.get("rinfLink").get("canonicalId").asText());
        assertEquals("assigned", version.get("openedBy").get("change").asText());
        assertEquals("LC-2026-0001", version.get("openedBy").get("reference").asText());
        assertValid(version);
    }

    @Test
    void aNewSubsidiaryCodeIsKeyedUnderItsParent() {
        ObjectNode published = decided(newSubsidiaryCode(), "0002");
        ObjectNode version = Publication.registerChanges(published, key -> Optional.empty(), List.of(), List.of(), NOW).get(0);
        assertEquals("BE00438/0002", version.get("key").asText());
        assertValid(version);
    }

    @Test
    void closingAPrimaryCodeClosesItsSubsidiaryCodesFromTheRegisterAndTheKg() {
        ObjectNode request = closingWithDependants();
        assertEquals(Optional.of("BE00438"), Publication.parentWhoseDependantsClose(request));
        ObjectNode published = decided(request, "BE00438");
        ObjectNode registeredSlc = TestVersions.slcDocument("BE00438", "0003", null, TestVersions.ASSIGNED);

        List<ObjectNode> changes = Publication.registerChanges(published, key -> Optional.empty(),
                List.of(registeredSlc), List.of("0001", "0003"), NOW);

        assertEquals(List.of("BE00438", "BE00438/0003", "BE00438/0001"),
                changes.stream().map(version -> version.get("key").asText()).toList());
        for (ObjectNode version : changes) {
            assertEquals("2026-11-30", version.get("endValidity").asText());
            assertEquals("closed", version.get("endedBy").get("change").asText());
            assertValid(version);
        }
        assertEquals("2026-11-30", changes.get(1).get("passengerEndValidity").asText());
        assertTrue(registeredSlc.get("endValidity").isNull());
    }

    @Test
    void anAmendmentEndsTheCurrentVersionAndOpensTheNext() {
        ObjectNode request = newPrimaryCode("BE");
        ObjectNode data = (ObjectNode) request.get("data");
        data.put("operation", "amendment");
        data.put("targetCode", "be00438 ");
        data.put("locationName", "Gembloux-Nord");
        data.put("imOrganisationCode", "");
        data.put("effectiveDate", "2026-12-13");
        data.putObject("crd").put("freightPossibleFlag", true);
        ObjectNode published = decided(request, "BE00438");
        ObjectNode stored = TestVersions.plcDocument("BE00438", TestVersions.GEMBLOUX, TestVersions.ASSIGNED);

        List<ObjectNode> changes = Publication.registerChanges(published,
                key -> "BE00438".equals(key) ? Optional.of(stored) : Optional.empty(), List.of(), List.of(), NOW);

        ObjectNode ended = changes.get(0);
        ObjectNode next = changes.get(1);
        assertEquals(1, ended.get("version").asInt());
        assertEquals("2026-12-12", ended.get("endValidity").asText());
        assertEquals("amended", ended.get("endedBy").get("change").asText());
        assertEquals("Gembloux", ended.get("locationName").asText());
        assertEquals(2, next.get("version").asInt());
        assertEquals("2026-12-13", next.get("startValidity").asText());
        assertEquals("Gembloux-Nord", next.get("locationName").asText());
        assertEquals("Gembloux-Nord", next.get("locationNameAscii").asText());
        assertEquals("0088", next.get("responsibleImCode").asText());
        assertEquals("2026-10-29", next.get("passengerStartValidity").asText());
        assertEquals("2026-12-13", next.get("freightStartValidity").asText());
        assertEquals("amended", next.get("openedBy").get("change").asText());
        assertValid(ended);
        assertValid(next);
        assertEquals(VersionRules.Update.ENDS, VersionRules.update(stored, ended));
    }

    @Test
    void aChangeBeforeTheCurrentVersionStartsIsRefused() {
        ObjectNode request = closingWithDependants();
        ObjectNode published = decided(request, "BE00438");
        ObjectNode stored = TestVersions.plcDocument("BE00438", TestVersions.GEMBLOUX, TestVersions.ASSIGNED);
        stored.put("startValidity", "2026-12-01");
        LocationCodeException refused = assertThrows(LocationCodeException.class, () ->
                Publication.registerChanges(published, key -> Optional.of(stored), List.of(), List.of(), NOW));
        assertEquals(List.of(Publication.EFFECTIVE_DATE_REFUSAL), refused.details());
    }

    @Test
    void theAssignedValueMustFitTheRequest() {
        ObjectNode request = newPrimaryCode("SE");
        assertEquals(List.of(), AssignedCodes.problems(request, " se00001"));
        assertEquals(1, AssignedCodes.problems(request, "BE00001").size());
        assertEquals(1, AssignedCodes.problems(request, "SE0001").size());
        assertEquals(1, AssignedCodes.problems(closingWithDependants(), "BE00439").size());
        assertEquals(List.of(), AssignedCodes.problems(closingWithDependants(), "BE00438"));
    }
}
