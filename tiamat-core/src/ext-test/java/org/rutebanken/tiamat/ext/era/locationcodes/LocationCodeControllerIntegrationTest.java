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
 * Added for the ERA Location Code Manager, 2026-10-29.
 * Modified 2026-10-30: requests change through submit and steps, not raw saves.
 * Modified 2026-11-02: code versions with CRD's columns.
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.rutebanken.tiamat.TiamatIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The register through HTTP, on the test database. The Knowledge Graph checks
 * are off here (no network in CI); RegisterChecksTest covers them, and the
 * workflow tests cover each rule.
 */
@ActiveProfiles("era-location-codes")
@TestPropertySource(properties = "era.location-codes.kg.checks-enabled=false")
@AutoConfigureTestRestTemplate
public class LocationCodeControllerIntegrationTest extends TiamatIntegrationTest {

    private static final String BASE = "/era/v1/location-codes";
    private static final int UNPROCESSABLE = 422;
    private static final String ANNA = "{\"name\":\"Anna\",\"role\":\"requester\"}";
    private static final String CARL = "{\"name\":\"Carl\",\"role\":\"codeAdministrator\"}";
    private static final String RITA = "{\"name\":\"Rita\",\"role\":\"rinfCorrespondent\"}";
    private static final String VERA = "{\"name\":\"Vera\",\"role\":\"validatingOfficer\"}";

    @Autowired
    private TestRestTemplate rest;

    private static final String DATA = "{\"operation\":\"newCode\",\"codeType\":\"primary\",\"country\":\"SE\","
            + "\"proposedCode\":\"\",\"targetCode\":\"\",\"locationName\":\"Test\",\"imOrganisationCode\":\"0074\","
            + "\"rinfLink\":{\"kind\":\"operationalPoint\",\"canonicalId\":\"SE00111\",\"label\":\"Myrbacken\"},"
            + "\"parentPrimaryCode\":\"\",\"subsidiaryTypeCode\":\"\",\"effectiveDate\":\"\","
            + "\"closeDependentCodes\":false,\"legacyCodingRange\":false,\"justification\":\"\",\"language\":\"en\","
            + "\"requester\":{\"name\":\"Anna\",\"organisation\":\"X\",\"organisationCode\":\"1234\","
            + "\"category\":\"railwayUndertaking\",\"email\":\"a@x.eu\"},\"preSubmissionConfirmed\":true}";

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(BASE + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String submit() {
        ResponseEntity<String> created = post("/requests", "{\"actor\":" + ANNA + ",\"data\":" + DATA + "}");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return JsonDocuments.parse(created.getBody()).get("id").asText();
    }

    private int eventCount(String id) {
        return JsonDocuments.parse(rest.getForObject(BASE + "/requests/" + id, String.class)).get("events").size();
    }

    private ResponseEntity<String> step(String id, String action, String actor, String payload, int expectedEvents) {
        return post("/requests/" + id + "/actions", "{\"action\":\"" + action + "\",\"actor\":" + actor
                + ",\"payload\":" + payload + ",\"expectedEventCount\":" + expectedEvents + "}");
    }

    private ResponseEntity<String> step(String id, String action, String actor, String payload) {
        return step(id, action, actor, payload, eventCount(id));
    }

    /** Submitted, registered, checked, validated and positioned: awaiting a decision. */
    private String awaitingDecision() {
        String id = submit();
        step(id, "register", CARL, "{}");
        step(id, "runChecks", CARL, "{\"checks\":[{\"item\":\"noDuplicate\",\"outcome\":\"pass\"}],\"assignableCode\":null}");
        step(id, "completeValidation", CARL, "{}");
        step(id, "confirmPosition", RITA, "{}");
        return id;
    }

    @Test
    public void takesStepsAndRefusesAStaleCopy() {
        String id = submit();
        ResponseEntity<String> registered = step(id, "register", CARL, "{\"reference\":\"LC-FAKE\"}", 1);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode request = JsonDocuments.parse(registered.getBody()).get("request");
        assertThat(request.get("status").asText()).isEqualTo("registered");
        assertThat(request.get("reference").asText()).matches("LC-\\d{4}-\\d{4}");

        ResponseEntity<String> stale = step(id, "returnInadmissible", CARL, "{}", 1);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("\"error\":\"staleRequest\"");
        assertThat(rest.getForObject(BASE + "/requests", String.class)).contains(id);
    }

    @Test
    public void refusesAStepOutOfTurnOrInAnotherRole() {
        String id = submit();
        ResponseEntity<String> wrongRole = step(id, "register", VERA, "{}");
        assertThat(wrongRole.getStatusCode().value()).isEqualTo(UNPROCESSABLE);
        assertThat(wrongRole.getBody()).contains("rinf_lc_blocked_wrong_role");

        ResponseEntity<String> outOfTurn = step(id, "publish", CARL, "{}");
        assertThat(outOfTurn.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outOfTurn.getBody()).contains("\"error\":\"actionNotAvailable\"");
    }

    @Test
    public void publishesACodeWithItsRequestAndAssignsItOnce() {
        String plc = "SE" + String.format("%05d", (int) (Math.random() * 99_999));
        String first = awaitingDecision();
        assertThat(step(first, "assign", VERA, "{\"code\":\"" + plc + "\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> published = step(first, "publish", CARL, "{}");
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody()).contains("\"key\":\"" + plc + "\"");
        String versions = rest.getForObject(BASE + "/codes/" + plc, String.class);
        assertThat(versions).startsWith("[").contains("\"SE00111\"").contains("\"version\":1")
                .contains("\"countryIsoCode\":\"SE\"").contains("\"locationNameAscii\":\"Test\"");

        String second = awaitingDecision();
        ResponseEntity<String> again = step(second, "assign", VERA, "{\"code\":\"" + plc + "\"}");
        assertThat(again.getStatusCode().value()).isEqualTo(UNPROCESSABLE);
        assertThat(again.getBody()).contains("\"error\":\"codeNotAssignable\"");
    }

    @Test
    public void noLongerAcceptsADocumentComputedByTheClient() {
        String id = submit();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> put = rest.exchange(BASE + "/requests/" + id, HttpMethod.PUT,
                new HttpEntity<>("{\"id\":\"" + id + "\",\"status\":\"published\"}", headers), String.class);
        assertThat(put.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(eventCount(id)).isEqualTo(1);
    }
}
