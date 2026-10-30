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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void aNewCodeEntersTheRegisterWithItsDecision() {
        ObjectNode published = decided(newPrimaryCode("SE"), "SE00001");
        List<ObjectNode> changes = Publication.registerChanges(published, key -> Optional.empty(), List.of(), List.of(), NOW);
        assertEquals(1, changes.size());
        ObjectNode entry = changes.get(0);
        assertEquals("SE00001", entry.get("key").asText());
        assertEquals("SE", entry.get("country").asText());
        assertEquals("2026-10-29", entry.get("validFrom").asText());
        assertEquals("SE00111", entry.get("rinfLink").get("canonicalId").asText());
        assertEquals("assigned", entry.get("history").get(0).get("change").asText());
        assertEquals("LC-2026-0001", entry.get("history").get(0).get("reference").asText());
        assertEquals(List.of(), LocationCodeRules.codeProblems(JsonDocuments.code(entry)));
    }

    @Test
    void aNewSubsidiaryCodeIsKeyedUnderItsParent() {
        ObjectNode published = decided(newSubsidiaryCode(), "0002");
        ObjectNode entry = Publication.registerChanges(published, key -> Optional.empty(), List.of(), List.of(), NOW).get(0);
        assertEquals("BE00438/0002", entry.get("key").asText());
        assertEquals(List.of(), LocationCodeRules.codeProblems(JsonDocuments.code(entry)));
    }

    @Test
    void closingAPrimaryCodeClosesItsSubsidiaryCodesFromTheRegisterAndTheKg() {
        ObjectNode request = closingWithDependants();
        assertEquals(Optional.of("BE00438"), Publication.parentWhoseDependantsClose(request));
        ObjectNode published = decided(request, "BE00438");
        JsonNode registeredSlc = json("{'key':'BE00438/0003','codeType':'subsidiary','code':'0003',"
                + "'parentPrimaryCode':'BE00438','country':'BE','name':'Track 3','rinfLink':null,'validFrom':'2026-01-05',"
                + "'closedFrom':null,'history':[{'at':'2026-01-05T09:00:00.000Z','requestId':'r0','reference':null,"
                + "'change':'assigned'}]}");

        List<ObjectNode> changes = Publication.registerChanges(published, key -> Optional.empty(),
                List.of(registeredSlc), List.of("0001", "0003"), NOW);

        assertEquals(List.of("BE00438", "BE00438/0003", "BE00438/0001"),
                changes.stream().map(entry -> entry.get("key").asText()).toList());
        for (ObjectNode entry : changes) {
            assertEquals("2026-12-01", entry.get("closedFrom").asText());
            JsonNode history = entry.get("history");
            assertEquals("closed", history.get(history.size() - 1).get("change").asText());
            assertEquals(List.of(), LocationCodeRules.codeProblems(JsonDocuments.code(entry)));
        }
        assertEquals(2, changes.get(1).get("history").size());
        assertTrue(registeredSlc.get("closedFrom").isNull());
    }

    @Test
    void anAmendmentKeepsWhatItDoesNotChange() {
        ObjectNode request = newPrimaryCode("BE");
        ObjectNode data = (ObjectNode) request.get("data");
        data.put("operation", "amendment");
        data.put("targetCode", "be00438 ");
        data.put("locationName", "Gembloux");
        data.put("imOrganisationCode", "");
        ObjectNode published = decided(request, "BE00438");
        JsonNode stored = json("{'key':'BE00438','codeType':'primary','code':'BE00438','parentPrimaryCode':null,"
                + "'country':'BE','name':'Gembloux station','imOrganisationCode':'0088','rinfLink':null,"
                + "'validFrom':'2026-01-05','closedFrom':null,'history':[{'at':'2026-01-05T09:00:00.000Z',"
                + "'requestId':'r0','reference':null,'change':'assigned'}]}");

        ObjectNode entry = Publication.registerChanges(published,
                key -> "BE00438".equals(key) ? Optional.of(stored) : Optional.empty(), List.of(), List.of(), NOW).get(0);

        assertEquals("Gembloux", entry.get("name").asText());
        assertEquals("0088", entry.get("imOrganisationCode").asText());
        assertEquals("amended", entry.get("history").get(1).get("change").asText());
        assertFalse(entry.get("closedFrom").isTextual());
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
