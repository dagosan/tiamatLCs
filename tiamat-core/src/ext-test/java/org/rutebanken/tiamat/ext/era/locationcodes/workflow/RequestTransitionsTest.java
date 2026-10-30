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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowFixtures.*;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.*;

class RequestTransitionsTest {

    @Test
    void submissionRecordsTheRequesterAndTheFirstEvent() {
        ObjectNode submitted = newPrimaryCode("SE");
        assertEquals("submitted", submitted.get("status").asText());
        assertEquals("Anna Requester", submitted.get("submittedBy").asText());
        assertEquals("submit", submitted.get("events").get(0).get("action").asText());
    }

    @Test
    void registrationGivesTheReferenceAndStartsTheClock() {
        ObjectNode registered = take(newPrimaryCode("SE"), REGISTER, CARL);
        assertEquals("registered", registered.get("status").asText());
        assertEquals("LC-2026-0001", registered.get("reference").asText());
        assertEquals(NOW, registered.get("registeredAt").asText());
        assertEquals(2, registered.get("events").size());
        assertEquals("Carl Administrator", registered.get("events").get(1).get("actor").get("name").asText());
    }

    @Test
    void aPrimaryCodeGoesThroughRinfHarmonisation() {
        ObjectNode validated = validated(newPrimaryCode("SE"));
        assertEquals("rinfHarmonisation", validated.get("status").asText());
        ObjectNode positioned = take(validated, CONFIRM_POSITION, RITA);
        assertEquals("awaitingDecision", positioned.get("status").asText());
        assertEquals("confirmed", positioned.get("rinfPosition").get("outcome").asText());
        assertEquals("SE00111", positioned.get("rinfPosition").get("link").get("canonicalId").asText());
    }

    @Test
    void aNonEuCountryGoesThroughExternalCoordination() {
        ObjectNode positioned = take(validated(newPrimaryCode("NO")), CONFIRM_POSITION, RITA);
        assertEquals("externalCoordination", positioned.get("status").asText());
        ObjectNode confirmed = take(positioned, CONFIRM_UNIQUENESS, ERIK, "{'organisation':' OTIF '}");
        assertEquals("awaitingDecision", confirmed.get("status").asText());
        assertEquals("OTIF", confirmed.get("coordination").get("organisation").asText());
    }

    @Test
    void aSubsidiaryCodeWithoutItsOwnElementInheritsItsParentsPosition() {
        ObjectNode validated = validated(newSubsidiaryCode());
        assertEquals("awaitingDecision", validated.get("status").asText());
        assertEquals("inherited", validated.get("rinfPosition").get("outcome").asText());
    }

    @Test
    void aRequestForInformationStopsTheClockAndTheReplyResetsTheChecks() {
        ObjectNode registered = take(newPrimaryCode("SE"), REGISTER, CARL);
        ObjectNode checked = take(registered, RUN_CHECKS, CARL, "{'checks':[{'item':'noDuplicate','outcome':'pass'}]}");
        ObjectNode asked = take(checked, REQUEST_INFORMATION, CARL, "{'note':'Which IM operates it?'}");
        assertEquals("informationRequested", asked.get("status").asText());
        assertTrue(asked.get("suspensions").get(0).get("to").isNull());

        ObjectNode data = asked.get("data").deepCopy();
        data.put("imOrganisationCode", "0074");
        ObjectNode payload = (ObjectNode) json("{}");
        payload.set("data", data);
        ObjectNode replied = RequestTransitions.apply(asked,
                new Step(REPLY_TO_INFORMATION_REQUEST, ANNA, payload, NOW, null));
        assertEquals("registered", replied.get("status").asText());
        assertEquals(NOW, replied.get("suspensions").get(0).get("to").asText());
        assertEquals(0, replied.get("checks").size());
        assertTrue(replied.get("checksRunAt").isNull());
    }

    @Test
    void aDecisionRecordsTheOfficerTheCodeAndTheProvision() {
        ObjectNode positioned = take(validated(newPrimaryCode("SE")), CONFIRM_POSITION, RITA);
        ObjectNode assigned = take(positioned, ASSIGN, VERA, "{'code':'SE00001','note':' Fine '}");
        assertEquals("assigned", assigned.get("status").asText());
        assertEquals("SE00001", assigned.get("decision").get("code").asText());
        assertEquals("Vera Officer", assigned.get("decision").get("by").get("name").asText());
        assertEquals("Fine", assigned.get("decision").get("note").asText());

        ObjectNode rejected = take(positioned, REJECT, VERA, "{'ground':'duplicate'}");
        assertEquals("Procedure 6.2(c) and 8.5; TD 103 4.3", rejected.get("decision").get("provision").asText());
        assertTrue(rejected.get("decision").get("code").isNull());
    }

    @Test
    void aRevisedReviewReopensTheDecision() {
        ObjectNode positioned = take(validated(newPrimaryCode("SE")), CONFIRM_POSITION, RITA);
        ObjectNode closed = take(take(positioned, REJECT, VERA, "{'ground':'duplicate'}"), NOTIFY_AND_CLOSE, CARL);
        ObjectNode underReview = take(closed, ASK_FOR_REVIEW, ANNA, "{'note':'Not a duplicate'}");
        assertEquals("Not a duplicate", underReview.get("review").get("reasons").asText());
        ObjectNode revised = take(underReview, REVISE_DECISION, new Actor("Olga Officer", Actor.VALIDATING_OFFICER));
        assertEquals("awaitingDecision", revised.get("status").asText());
        assertTrue(revised.get("decision").isNull());
        assertEquals("revised", revised.get("review").get("outcome").asText());
    }

    @Test
    void theStoredRequestIsNotChanged() {
        ObjectNode submitted = newPrimaryCode("SE");
        String before = submitted.toString();
        take(submitted, REGISTER, CARL);
        assertEquals(before, submitted.toString());
        assertEquals(List.of(), AssignedCodes.problems(submitted, "SE00001"));
    }
}
