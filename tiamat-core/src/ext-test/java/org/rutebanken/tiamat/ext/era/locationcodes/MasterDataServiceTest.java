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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.masterdata.DefaultMasterData;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The register's rules that read the master data, over a repository kept in memory. */
class MasterDataServiceTest {

    private static final String CARL = "{'name':'Carl Administrator','role':'codeAdministrator'}";
    private static final String RITA = "{'name':'Rita Correspondent','role':'rinfCorrespondent'}";
    private static final String VERA = "{'name':'Vera Officer','role':'validatingOfficer'}";

    /** The repository's requests and master data in maps; the register starts with its default lists. */
    private static final class MemoryRepository extends LocationCodeRepository {

        private final Map<String, JsonNode> requests = new HashMap<>();
        private final Map<String, JsonNode> countries = new HashMap<>();
        private final Map<String, JsonNode> types = new HashMap<>();
        private int lastReference;

        private MemoryRepository() {
            super(null);
            DefaultMasterData.countries().forEach(this::saveCountry);
            DefaultMasterData.subsidiaryTypes().forEach(this::saveSubsidiaryType);
        }

        @Override
        public Optional<JsonNode> lockRequest(String id) {
            return Optional.ofNullable(requests.get(id));
        }

        @Override
        public void insertRequest(RequestEntry request, String json) {
            requests.put(request.id(), JsonDocuments.parse(json));
        }

        @Override
        public void updateRequest(RequestEntry request, String json) {
            requests.put(request.id(), JsonDocuments.parse(json));
        }

        @Override
        public boolean hasCode(String key) {
            return false;
        }

        @Override
        public int nextReferenceSequence(int year) {
            return ++lastReference;
        }

        @Override
        public Optional<JsonNode> country(String isoCode) {
            return Optional.ofNullable(countries.get(isoCode));
        }

        @Override
        public Optional<JsonNode> subsidiaryType(String code) {
            return Optional.ofNullable(types.get(code));
        }

        @Override
        public void saveCountry(JsonNode country) {
            countries.put(country.path("isoCode").asText(), country);
        }

        @Override
        public void saveSubsidiaryType(JsonNode type) {
            types.put(type.path("code").asText(), type);
        }

        @Override
        public void log(String entity, String key, String operation, String principal, String json) {
            // Not kept.
        }
    }

    private static final class NoTransaction extends TransactionTemplate {

        private static final long serialVersionUID = 1L;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }

    private final MemoryRepository repository = new MemoryRepository();
    private final LocationCodeService service = new LocationCodeService(repository, new NoTransaction(), null,
            Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC));

    private static String json(String text) {
        return text.replace('\'', '"');
    }

    private static String newCode(String codeType, String country, String typeCode) {
        return json("{'actor':{'name':'Anna Requester','role':'requester'},'data':{'operation':'newCode',"
                + "'codeType':'" + codeType + "','country':'" + country + "','proposedCode':'','targetCode':'',"
                + "'locationName':'Myrbacken','imOrganisationCode':'0074',"
                + "'rinfLink':{'kind':'operationalPoint','canonicalId':'SE00111','label':'Myrbacken'},"
                + "'parentPrimaryCode':'" + ("subsidiary".equals(codeType) ? country + "00438" : "") + "',"
                + "'subsidiaryTypeCode':'" + typeCode + "','effectiveDate':'','closeDependentCodes':false,"
                + "'legacyCodingRange':false,'justification':'','language':'en',"
                + "'requester':{'name':'Anna Requester','organisation':'X','organisationCode':'1234',"
                + "'category':'railwayUndertaking','email':'a@x.eu'},'preSubmissionConfirmed':true}}");
    }

    private JsonNode submit(String body) {
        return JsonDocuments.parse(service.submit(body));
    }

    private JsonNode step(JsonNode request, String action, String actor, String payload) {
        String body = json("{'action':'" + action + "','actor':" + actor + ",'payload':" + payload
                + ",'expectedEventCount':" + request.path("events").size() + "}");
        return JsonDocuments.parse(service.takeStep(request.path("id").asText(), body)).path("request");
    }

    /** Submitted, registered, checked, validated and positioned: at the step after RINF harmonisation. */
    private JsonNode positioned(String country) {
        JsonNode request = submit(newCode("primary", country, ""));
        request = step(request, "register", CARL, "{}");
        request = step(request, "runChecks", CARL,
                "{'checks':[{'item':'noDuplicate','outcome':'pass'}],'assignableCode':'" + country + "00001'}");
        request = step(request, "completeValidation", CARL, "{'recommendation':{'outcome':'assign','ground':null}}");
        return step(request, "confirmPosition", RITA, "{}");
    }

    private void saveCountry(String isoCode, String changes) {
        ObjectNode country = repository.country(isoCode).orElseThrow().deepCopy();
        country.setAll((ObjectNode) JsonDocuments.parse(json(changes)));
        service.saveCountry(isoCode, JsonDocuments.write(country));
    }

    @Test
    void aRequestForACountryNotListedIsRefused() {
        LocationCodeException refused = assertThrows(LocationCodeException.class,
                () -> service.submit(newCode("primary", "XX", "")));
        assertEquals(LocationCodeException.UNPROCESSABLE, refused.status());
        assertEquals("codes are not assigned for country \"XX\"", refused.details().get(0));
    }

    @Test
    void aRequestForACountryNoLongerValidIsRefused() {
        saveCountry("LI", "{'validTo':'2026-11-09'}");
        assertThrows(LocationCodeException.class, () -> service.submit(newCode("primary", "LI", "")));
        saveCountry("LI", "{'validTo':'2026-11-10'}");
        assertEquals("submitted", submit(newCode("primary", "LI", "")).path("status").asText());
    }

    @Test
    void aNewSubsidiaryCodeNeedsATypeInUse() {
        LocationCodeException refused = assertThrows(LocationCodeException.class,
                () -> service.submit(newCode("subsidiary", "BE", "02")));
        assertEquals("\"02\" is not a subsidiary location code type in use", refused.details().get(0));
        assertEquals("submitted", submit(newCode("subsidiary", "BE", "21")).path("status").asText());
        ObjectNode building = repository.subsidiaryType("21").orElseThrow().deepCopy();
        building.put("validTo", "2026-10-31");
        service.saveSubsidiaryType("21", JsonDocuments.write(building));
        assertThrows(LocationCodeException.class, () -> service.submit(newCode("subsidiary", "BE", "21")));
    }

    @Test
    void aNewCodeTakesANumberFromTheCountrysRange() {
        saveCountry("BE", "{'plcRangeStart':100,'plcRangeEnd':200}");
        JsonNode positioned = positioned("BE");
        assertEquals("awaitingDecision", positioned.path("status").asText());
        LocationCodeException refused = assertThrows(LocationCodeException.class,
                () -> step(positioned, "assign", VERA, "{'code':'BE00050'}"));
        assertEquals("codeNotAssignable", refused.error());
        assertEquals("BE00050 is outside the range for new codes of BE (100 to 200)", refused.details().get(0));
        assertEquals("assigned", step(positioned, "assign", VERA, "{'code':'BE00150'}").path("status").asText());
    }

    @Test
    void externalCoordinationFollowsTheCountrysEuFlag() {
        assertEquals("externalCoordination", positioned("NO").path("status").asText());
        saveCountry("NO", "{'euMemberState':true}");
        assertEquals("awaitingDecision", positioned("NO").path("status").asText());
        saveCountry("BE", "{'euMemberState':false}");
        assertEquals("externalCoordination", positioned("BE").path("status").asText());
    }

    @Test
    void masterDataWithAWrongFormIsNotSaved() {
        LocationCodeException refused = assertThrows(LocationCodeException.class,
                () -> saveCountry("BE", "{'plcRangeStart':0}"));
        assertEquals("invalidMasterData", refused.error());
        assertEquals(1, repository.country("BE").orElseThrow().path("plcRangeStart").asInt());
        LocationCodeException mismatch = assertThrows(LocationCodeException.class,
                () -> service.saveCountry("FR", JsonDocuments.write(repository.country("BE").orElseThrow())));
        assertTrue(mismatch.details().get(0).contains("differs from the URL"));
    }
}
