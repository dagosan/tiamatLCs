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
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;

import java.time.LocalDate;

/** Requests walked through the workflow, for the workflow tests. */
final class WorkflowFixtures {

    static final Actor ANNA = new Actor("Anna Requester", Actor.REQUESTER);
    static final Actor CARL = new Actor("Carl Administrator", Actor.CODE_ADMINISTRATOR);
    static final Actor RITA = new Actor("Rita Correspondent", Actor.RINF_CORRESPONDENT);
    static final Actor ERIK = new Actor("Erik Coordination", Actor.EXTERNAL_COORDINATION_POINT);
    static final Actor VERA = new Actor("Vera Officer", Actor.VALIDATING_OFFICER);

    /** A Thursday; T0 of the requests below. */
    static final String NOW = "2026-10-29T10:00:00.000Z";
    static final LocalDate TODAY = LocalDate.parse("2026-10-29");

    private WorkflowFixtures() {
    }

    static JsonNode json(String text) {
        return JsonDocuments.parse(text.replace('\'', '"'));
    }

    /** A new PLC for SE00111 in Sweden, positioned on it. */
    static ObjectNode newPrimaryCode(String country) {
        JsonNode data = json("{'operation':'newCode','codeType':'primary','country':'" + country + "',"
                + "'proposedCode':'','targetCode':'','locationName':'Myrbacken','imOrganisationCode':'0074',"
                + "'rinfLink':{'kind':'operationalPoint','canonicalId':'SE00111','label':'Myrbacken'},"
                + "'parentPrimaryCode':'','subsidiaryTypeCode':'','effectiveDate':'','closeDependentCodes':false,"
                + "'legacyCodingRange':false,'justification':'','language':'en',"
                + "'requester':{'name':'Anna Requester','organisation':'X','organisationCode':'1234',"
                + "'category':'railwayUndertaking','email':'a@x.eu'},'preSubmissionConfirmed':true}");
        return NewRequests.create("r1", data, ANNA, NOW);
    }

    /** A new SLC under BE00438 with no RINF element of its own. */
    static ObjectNode newSubsidiaryCode() {
        ObjectNode request = newPrimaryCode("BE");
        ObjectNode data = (ObjectNode) request.get("data");
        data.put("codeType", "subsidiary");
        data.put("parentPrimaryCode", "BE00438");
        data.put("subsidiaryTypeCode", "01");
        data.putNull("rinfLink");
        return request;
    }

    /** Closing BE00438 together with its SLCs. */
    static ObjectNode closingWithDependants() {
        ObjectNode request = newPrimaryCode("BE");
        ObjectNode data = (ObjectNode) request.get("data");
        data.put("operation", "deactivation");
        data.put("targetCode", "BE00438");
        data.put("effectiveDate", "2026-12-01");
        data.put("closeDependentCodes", true);
        data.putNull("rinfLink");
        return request;
    }

    static ObjectNode take(JsonNode request, String step, Actor actor, String payload) {
        String reference = WorkflowSteps.REGISTER.equals(step) ? "LC-2026-0001" : null;
        return RequestTransitions.apply(request, new Step(step, actor, json(payload), NOW, reference));
    }

    static ObjectNode take(JsonNode request, String step, Actor actor) {
        return take(request, step, actor, "{}");
    }

    /** Registered, checked (all pass) and validated: waiting for RINF harmonisation or a decision. */
    static ObjectNode validated(ObjectNode submitted) {
        ObjectNode registered = take(submitted, WorkflowSteps.REGISTER, CARL);
        ObjectNode checked = take(registered, WorkflowSteps.RUN_CHECKS, CARL,
                "{'checks':[{'item':'noDuplicate','outcome':'pass'}],'assignableCode':'SE00001'}");
        return take(checked, WorkflowSteps.COMPLETE_VALIDATION, CARL,
                "{'recommendation':{'outcome':'assign','ground':null}}");
    }
}
