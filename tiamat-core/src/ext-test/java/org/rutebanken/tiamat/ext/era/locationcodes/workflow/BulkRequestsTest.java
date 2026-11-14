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
 * Added for the ERA Location Code Manager, 2026-11-14.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowFixtures.*;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.*;

class BulkRequestsTest {

    private static final String DATA = "{'operation':'newCode','codeType':'primary','country':'SE'}";

    private static ObjectNode line(int number) {
        return BulkRequests.asLine(newPrimaryCode("SE"), "bulk-1", number, "stations.csv");
    }

    private static List<String> problems(String body) {
        return BulkRequests.submissionProblems(json(body));
    }

    @Test
    void aSubmissionNeedsAFileNameAndLinesWithTheirNumberAndData() {
        assertEquals(List.of(), problems(
                "{'fileName':'stations.csv','lines':[{'line':2,'data':" + DATA + "},{'line':5,'data':" + DATA + "}]}"));
        assertEquals(List.of("fileName must have 1 to 255 characters", "lines must be an array with at least one line"),
                problems("{'fileName':' ','lines':[]}"));
        assertEquals(List.of("each line needs its line number in the file, 2 or more"),
                problems("{'fileName':'a.csv','lines':[{'line':1,'data':" + DATA + "}]}"));
        assertEquals(List.of("line 3 appears twice", "line 4: data must be an object"), problems(
                "{'fileName':'a.csv','lines':[{'line':3,'data':" + DATA + "},{'line':3,'data':" + DATA + "},"
                        + "{'line':4,'data':'x'}]}"));
    }

    @Test
    void aSubmissionHasAtMostFiveHundredLines() {
        StringBuilder lines = new StringBuilder();
        for (int number = 2; number <= BulkRequests.MAX_LINES + 2; number++) {
            lines.append(number == 2 ? "" : ",").append("{'line':").append(number).append(",'data':{}}");
        }
        assertEquals(List.of("a bulk request has at most 500 lines, not 501"),
                problems("{'fileName':'a.csv','lines':[" + lines + "]}"));
    }

    @Test
    void aLineCarriesItsBulkRequestAndItsReferenceEndsWithItsLine() {
        ObjectNode request = line(3);
        assertTrue(BulkRequests.isLine(request));
        assertFalse(BulkRequests.isLine(newPrimaryCode("SE")));
        assertEquals(3, BulkRequests.lineOf(request));
        assertEquals("stations.csv", request.path("bulk").path("fileName").asText());
        assertEquals("LC-2026-0007/3", BulkRequests.lineReference("LC-2026-0007", 3));
    }

    @Test
    void aLineIsNotRegisteredOnItsOwn() {
        assertEquals(Optional.of(BulkRequests.REGISTER_WITH_BULK),
                StepPermission.refusal(line(2), REGISTER, CARL, TODAY));
        assertEquals(Optional.empty(), StepPermission.refusal(line(2), REQUEST_INFORMATION, CARL, TODAY));
    }

    @Test
    void aBulkRequestIsRegisteredByACodeAdministratorWhileEveryLineIsAsSubmitted() {
        List<JsonNode> lines = List.of(line(2), line(3));
        assertEquals(Optional.empty(), BulkRequests.registrationRefusal(lines, CARL));
        assertEquals(Optional.of(StepPermission.WRONG_ROLE), BulkRequests.registrationRefusal(lines, VERA));
        assertEquals(Optional.of(StepPermission.WRONG_STATUS), BulkRequests.registrationRefusal(List.of(), CARL));
        List<JsonNode> oneAskedBack = List.of(line(2), take(line(3), REQUEST_INFORMATION, CARL));
        assertEquals(Optional.of(StepPermission.WRONG_STATUS), BulkRequests.registrationRefusal(oneAskedBack, CARL));
    }
}
