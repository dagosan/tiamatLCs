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
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.rutebanken.tiamat.ext.era.locationcodes.masterdata.DefaultMasterData;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The repository's requests, drafts and master data kept in maps, for tests
 * of the service's rules without a database. The master data starts with the
 * lists migration V3 loads.
 */
final class MemoryRepository extends LocationCodeRepository {

    /** Runs the work at once: nothing to roll back in memory. */
    static final class NoTransaction extends TransactionTemplate {

        private static final long serialVersionUID = 1L;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }

    private final Map<String, JsonNode> requests = new LinkedHashMap<>();
    private final Map<String, JsonNode> drafts = new LinkedHashMap<>();
    private final Map<String, JsonNode> countries = new LinkedHashMap<>();
    private final Map<String, JsonNode> types = new LinkedHashMap<>();
    private int lastReference;

    MemoryRepository() {
        super(null);
        DefaultMasterData.countries().forEach(this::saveCountry);
        DefaultMasterData.subsidiaryTypes().forEach(this::saveSubsidiaryType);
    }

    private static String array(Iterable<JsonNode> documents) {
        ArrayNode array = JsonDocuments.MAPPER.createArrayNode();
        documents.forEach(array::add);
        return JsonDocuments.write(array);
    }

    @Override
    public Optional<JsonNode> lockRequest(String id) {
        return Optional.ofNullable(requests.get(id));
    }

    @Override
    public Optional<String> requestDocument(String id) {
        return lockRequest(id).map(JsonDocuments::write);
    }

    @Override
    public String requestsJson() {
        return array(requests.values());
    }

    @Override
    public String requestsJsonOf(String submittedBy) {
        return array(requests.values().stream()
                .filter(request -> Actor.isSamePerson(request.path("submittedBy").asText(), submittedBy)).toList());
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
    public String draftsJson(String owner) {
        return array(drafts.values().stream()
                .filter(draft -> Actor.isSamePerson(draft.path("owner").asText(), owner)).toList());
    }

    @Override
    public int draftCount(String owner) {
        return JsonDocuments.parse(draftsJson(owner)).size();
    }

    @Override
    public Optional<JsonNode> lockDraft(String id) {
        return Optional.ofNullable(drafts.get(id));
    }

    @Override
    public void saveDraft(String id, String owner, String json) {
        drafts.put(id, JsonDocuments.parse(json));
    }

    @Override
    public void deleteDraft(String id) {
        drafts.remove(id);
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
    public String changeLogJson(String entity, String key) {
        return "[]";
    }

    @Override
    public void log(String entity, String key, String operation, String principal, String json) {
        // Not kept.
    }
}
