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

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.BulkRequests;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bulk requests through the service, over a register kept in memory (no database, no Knowledge Graph). */
class BulkRequestServiceTest {

    private static final String ANNA = "{'name':'Anna','role':'requester'}";
    private static final String CARL = "{'name':'Carl','role':'codeAdministrator'}";
    private static final String VERA = "{'name':'Vera','role':'validatingOfficer'}";
    private static final String DATA = "{'operation':'newCode','codeType':'primary','country':'SE',"
            + "'locationName':'Test','rinfLink':null}";

    /** The requests and the year counters; a transaction that fails puts both back. */
    private static final class MemoryRegister extends LocationCodeRepository {
        private Map<String, String> requests = new LinkedHashMap<>();
        private Map<Integer, Integer> sequences = new LinkedHashMap<>();

        MemoryRegister() {
            super(null);
        }

        @Override
        public Optional<JsonNode> lockRequest(String id) {
            return Optional.ofNullable(requests.get(id)).map(JsonDocuments::parse);
        }

        @Override
        public List<JsonNode> lockBulkLines(String bulkId) {
            return requests.values().stream().map(JsonDocuments::parse)
                    .filter(request -> bulkId.equals(request.path("bulk").path("id").asText()))
                    .sorted(Comparator.comparingInt(BulkRequests::lineOf))
                    .toList();
        }

        @Override
        public void insertRequest(RequestEntry request, String json) {
            requests.put(request.id(), json);
        }

        @Override
        public void updateRequest(RequestEntry request, String json) {
            requests.put(request.id(), json);
        }

        @Override
        public int nextReferenceSequence(int year) {
            return sequences.merge(year, 1, Integer::sum);
        }

        @Override
        public void log(String entity, String key, String operation, String principal, String json) {
            // The change log is not looked at here.
        }
    }

    private static final class MemoryTransactions extends TransactionTemplate {
        private final MemoryRegister register;

        MemoryTransactions(MemoryRegister register) {
            this.register = register;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            Map<String, String> requests = new LinkedHashMap<>(register.requests);
            Map<Integer, Integer> sequences = new LinkedHashMap<>(register.sequences);
            try {
                return action.doInTransaction(null);
            } catch (RuntimeException e) {
                register.requests = requests;
                register.sequences = sequences;
                throw e;
            }
        }
    }

    private final MemoryRegister register = new MemoryRegister();
    private final LocationCodeService service = new LocationCodeService(register, new MemoryTransactions(register),
            null, Clock.fixed(Instant.parse("2026-11-14T09:00:00Z"), ZoneOffset.UTC));

    private static String json(String text) {
        return text.replace('\'', '"');
    }

    private JsonNode submitTwoLines() {
        return JsonDocuments.parse(service.submitBulk(json("{'actor':" + ANNA + ",'fileName':'stations.csv',"
                + "'lines':[{'line':3,'data':" + DATA + "},{'line':2,'data':" + DATA + "}]}")));
    }

    private LocationCodeException refusal(Runnable call) {
        return assertThrows(LocationCodeException.class, call::run);
    }

    @Test
    void theLinesOfAFileAreSubmittedTogetherAsRequestsOfOneBulkRequest() {
        JsonNode lines = submitTwoLines();
        assertEquals(2, lines.size());
        assertEquals(2, lines.get(0).path("bulk").path("line").asInt());
        assertEquals(3, lines.get(1).path("bulk").path("line").asInt());
        assertEquals(lines.get(0).path("bulk").path("id").asText(), lines.get(1).path("bulk").path("id").asText());
        assertEquals("stations.csv", lines.get(1).path("bulk").path("fileName").asText());
        assertEquals("submitted", lines.get(0).path("status").asText());
        assertEquals(lines.get(0).path("submittedAt").asText(), lines.get(1).path("submittedAt").asText());
        assertEquals(2, register.requests.size());
    }

    @Test
    void aFileWithALineThatIsNotARequestIsRefusedWhole() {
        LocationCodeException refused = refusal(() -> service.submitBulk(json("{'actor':" + ANNA
                + ",'fileName':'stations.csv','lines':[{'line':2,'data':" + DATA + "},"
                + "{'line':3,'data':{'codeType':'primary'}}]}")));
        assertEquals(LocationCodeException.BAD_REQUEST, refused.status());
        assertTrue(refused.details().stream().allMatch(detail -> detail.startsWith("line 3: ")));
        assertTrue(register.requests.isEmpty());
    }

    @Test
    void onlyARequesterSubmitsABulkRequest() {
        LocationCodeException refused = refusal(() -> service.submitBulk(json("{'actor':" + CARL
                + ",'fileName':'stations.csv','lines':[{'line':2,'data':" + DATA + "}]}")));
        assertEquals(LocationCodeException.UNPROCESSABLE, refused.status());
    }

    @Test
    void theLinesAreRegisteredTogetherUnderOneReferenceAndOneT0() {
        String bulkId = submitTwoLines().get(0).path("bulk").path("id").asText();
        JsonNode lines = JsonDocuments.parse(service.registerBulk(bulkId, json("{'actor':" + CARL + "}")));
        assertEquals("LC-2026-0001/2", lines.get(0).path("reference").asText());
        assertEquals("LC-2026-0001/3", lines.get(1).path("reference").asText());
        assertEquals("registered", lines.get(1).path("status").asText());
        assertEquals("2026-11-14T09:00:00.000Z", lines.get(0).path("registeredAt").asText());
        assertEquals(lines.get(0).path("registeredAt").asText(), lines.get(1).path("registeredAt").asText());
        assertEquals(Map.of(2026, 1), register.sequences);

        LocationCodeException again = refusal(() -> service.registerBulk(bulkId, json("{'actor':" + CARL + "}")));
        assertEquals(LocationCodeException.CONFLICT, again.status());
        assertEquals(Map.of(2026, 1), register.sequences);
    }

    @Test
    void aBulkRequestIsRegisteredByACodeAdministratorOnly() {
        String bulkId = submitTwoLines().get(0).path("bulk").path("id").asText();
        LocationCodeException wrongRole = refusal(() -> service.registerBulk(bulkId, json("{'actor':" + VERA + "}")));
        assertEquals(LocationCodeException.UNPROCESSABLE, wrongRole.status());
        assertEquals(List.of("rinf_lc_blocked_wrong_role"), wrongRole.details());
        assertTrue(register.sequences.isEmpty());
        assertEquals(LocationCodeException.NOT_FOUND,
                refusal(() -> service.registerBulk("unknown", json("{'actor':" + CARL + "}"))).status());
    }

    @Test
    void aLineIsNotRegisteredOnItsOwnAndThenFollowsTheOrdinaryWorkflow() {
        JsonNode submitted = submitTwoLines();
        String lineId = submitted.get(0).path("id").asText();
        String register = json("{'action':'register','actor':" + CARL + ",'payload':{},'expectedEventCount':1}");
        LocationCodeException alone = refusal(() -> service.takeStep(lineId, register));
        assertEquals(List.of(BulkRequests.REGISTER_WITH_BULK), alone.details());

        service.registerBulk(submitted.get(0).path("bulk").path("id").asText(), json("{'actor':" + CARL + "}"));
        JsonNode checked = JsonDocuments.parse(service.takeStep(lineId, json("{'action':'runChecks','actor':" + CARL
                + ",'payload':{'checks':[{'item':'noDuplicate','outcome':'pass'}],'assignableCode':null},"
                + "'expectedEventCount':2}")));
        assertEquals(1, checked.path("request").path("checks").size());
        assertEquals("LC-2026-0001/2", checked.path("request").path("reference").asText());
    }
}
