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
 * Added for the ERA Location Code Manager, 2026-11-10.
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.auth.EraLogin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drafts, and a requester reading only their own requests, over a repository kept in memory. */
class RequestDraftServiceTest {

    private static final String ANNA = "Anna Requester";
    private static final String BEN = "Ben Requester";
    private static final String DATA = "{'operation':'newCode','codeType':'primary','country':'BE','locationName':'X'}";

    private final MemoryRepository repository = new MemoryRepository();
    private final LocationCodeService service = new LocationCodeService(repository,
            new MemoryRepository.NoTransaction(), null,
            Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC));

    private static String json(String text) {
        return text.replace('\'', '"');
    }

    private static String submission(String name, String draftId) {
        return json("{'actor':{'name':'" + name + "','role':'requester'},"
                + (draftId == null ? "" : "'draftId':'" + draftId + "',") + "'data':" + DATA + "}");
    }

    private JsonNode draftsOf(String owner) {
        return JsonDocuments.parse(service.draftsJson(owner));
    }

    private static int statusOf(Runnable call) {
        return assertThrows(LocationCodeException.class, call::run).status();
    }

    @Test
    void aDraftIsSavedForItsOwnerAndSavedAgainInPlace() {
        JsonNode saved = JsonDocuments.parse(service.saveDraft("d1", ANNA, json("{'data':" + DATA + "}")));
        assertEquals("d1", saved.path("id").asText());
        assertEquals(ANNA, saved.path("owner").asText());
        assertEquals("2026-11-10T10:00:00.000Z", saved.path("savedAt").asText());
        assertEquals("X", saved.path("data").path("locationName").asText());
        service.saveDraft("d1", " anna requester ", json("{'data':{'locationName':'Y'}}"));
        assertEquals(1, draftsOf(ANNA).size());
        assertEquals("Y", draftsOf(ANNA).get(0).path("data").path("locationName").asText());
        assertEquals(0, draftsOf(BEN).size());
    }

    @Test
    void aDraftNeedsAnIdAndData() {
        assertEquals(LocationCodeException.BAD_REQUEST,
                statusOf(() -> service.saveDraft("not an id", ANNA, json("{'data':{}}"))));
        assertEquals(LocationCodeException.BAD_REQUEST,
                statusOf(() -> service.saveDraft("d1", ANNA, json("{'data':'text'}"))));
    }

    @Test
    void anotherPersonsDraftIsAnsweredAsIfItDidNotExist() {
        service.saveDraft("d1", ANNA, json("{'data':" + DATA + "}"));
        assertEquals(LocationCodeException.NOT_FOUND,
                statusOf(() -> service.saveDraft("d1", BEN, json("{'data':{}}"))));
        assertEquals(LocationCodeException.NOT_FOUND, statusOf(() -> service.deleteDraft("d1", BEN)));
        assertEquals(LocationCodeException.NOT_FOUND, statusOf(() -> service.deleteDraft("d2", ANNA)));
        service.deleteDraft("d1", ANNA);
        assertEquals(0, draftsOf(ANNA).size());
    }

    @Test
    void submittingRemovesTheSubmittersDraftOnly() {
        service.saveDraft("d1", ANNA, json("{'data':" + DATA + "}"));
        service.saveDraft("d2", BEN, json("{'data':" + DATA + "}"));
        service.submit(submission(ANNA, "d1"));
        assertEquals(0, draftsOf(ANNA).size());
        service.submit(submission(ANNA, "d2"));
        assertEquals(1, draftsOf(BEN).size());
        service.submit(submission(ANNA, "gone"));
        assertEquals(3, JsonDocuments.parse(service.requestsJson(Optional.empty())).size());
    }

    @Test
    void aRefusedSubmissionKeepsTheDraft() {
        service.saveDraft("d1", ANNA, json("{'data':" + DATA + "}"));
        String unknownCountry = submission(ANNA, "d1").replace("'BE'".replace('\'', '"'), "\"XX\"");
        assertThrows(LocationCodeException.class, () -> service.submit(unknownCountry));
        assertEquals(1, draftsOf(ANNA).size());
    }

    @Test
    void aRequesterReadsOnlyTheirOwnRequests() {
        String annas = JsonDocuments.parse(service.submit(submission(ANNA, null))).path("id").asText();
        String bens = JsonDocuments.parse(service.submit(submission(BEN, null))).path("id").asText();
        assertEquals(2, JsonDocuments.parse(service.requestsJson(Optional.empty())).size());
        JsonNode own = JsonDocuments.parse(service.requestsJson(Optional.of("anna requester")));
        assertEquals(1, own.size());
        assertEquals(annas, own.get(0).path("id").asText());
        assertTrue(service.requestJson(annas, Optional.of(ANNA)).contains(annas));
        assertEquals(LocationCodeException.NOT_FOUND, statusOf(() -> service.requestJson(bens, Optional.of(ANNA))));
        assertEquals(LocationCodeException.NOT_FOUND,
                statusOf(() -> service.requestChangeLogJson(bens, Optional.of(ANNA))));
        assertTrue(service.requestJson(bens, Optional.empty()).contains(bens));
        assertEquals("[]", service.requestChangeLogJson(annas, Optional.of(ANNA)));
    }

    @Test
    void onlyAgencyRolesReadEveryRequest() {
        assertTrue(!EraLogin.readsEveryRequest(List.of("requester")));
        assertTrue(!EraLogin.readsEveryRequest(List.of("offline_access", "requester")));
        assertTrue(EraLogin.readsEveryRequest(List.of("requester", "codeAdministrator")));
        assertTrue(EraLogin.readsEveryRequest(List.of("validatingOfficer")));
    }
}
