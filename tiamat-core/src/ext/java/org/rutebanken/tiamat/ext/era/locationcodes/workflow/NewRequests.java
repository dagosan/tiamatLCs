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

import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.array;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.object;

/** A request as submitted (procedure step 2); same shape as the frontend's createRequest.ts. */
public final class NewRequests {

    private static final String REFERENCE_PREFIX = "LC";

    private NewRequests() {
    }

    public static ObjectNode create(String id, JsonNode data, Actor requester, String now) {
        ObjectNode request = object();
        request.put("id", id);
        request.putNull("reference");
        request.put("status", "submitted");
        request.set("data", data.deepCopy());
        request.put("submittedAt", now);
        request.put("submittedBy", requester.name());
        request.putNull("registeredAt");
        request.set("suspensions", array());
        request.set("checks", array());
        request.putNull("checksRunAt");
        request.putNull("assignableCode");
        request.set("confirmedChecks", array());
        request.putNull("recommendation");
        request.putNull("rinfPosition");
        request.putNull("coordination");
        request.putNull("decision");
        request.putNull("publishedAt");
        request.putNull("notifiedAt");
        request.putNull("review");
        ObjectNode event = object();
        event.put("at", now);
        event.set("actor", requester.toJson());
        event.put("action", WorkflowSteps.SUBMIT);
        event.put("note", "");
        request.set("events", array().add(event));
        return request;
    }

    /** Reference given at registration, e.g. "LC-2026-0007". */
    public static String reference(int year, int sequence) {
        return String.format("%s-%d-%04d", REFERENCE_PREFIX, year, sequence);
    }
}
