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
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The register through HTTP, on the test database. The Knowledge Graph checks
 * are off here (no network in CI); RegisterChecksTest covers them.
 */
@ActiveProfiles("era-location-codes")
@TestPropertySource(properties = "era.location-codes.kg.checks-enabled=false")
@AutoConfigureTestRestTemplate
public class LocationCodeControllerIntegrationTest extends TiamatIntegrationTest {

    private static final String BASE = "/era/v1/location-codes";

    @Autowired
    private TestRestTemplate rest;

    private static String request(String id, String status, int events) {
        StringBuilder list = new StringBuilder();
        for (int index = 0; index < events; index++) {
            list.append(index == 0 ? "" : ",").append("{\"at\":\"2026-10-29T10:0").append(index)
                    .append(":00Z\",\"actor\":{\"role\":\"requester\",\"name\":\"A\"},\"action\":\"a")
                    .append(index).append("\",\"note\":\"\"}");
        }
        return "{\"id\":\"" + id + "\",\"reference\":null,\"status\":\"" + status + "\","
                + "\"data\":{\"operation\":\"newCode\",\"codeType\":\"primary\",\"country\":\"SE\"},"
                + "\"submittedAt\":\"2026-10-29T10:00:00Z\",\"submittedBy\":\"A\",\"events\":[" + list + "]}";
    }

    private static String code(String plc, String requestId) {
        return "{\"key\":\"" + plc + "\",\"codeType\":\"primary\",\"code\":\"" + plc + "\",\"parentPrimaryCode\":null,"
                + "\"country\":\"" + plc.substring(0, 2) + "\",\"name\":\"Test\",\"rinfLink\":"
                + "{\"kind\":\"operationalPoint\",\"canonicalId\":\"SE00111\"},\"validFrom\":\"2026-10-29\","
                + "\"closedFrom\":null,\"history\":[{\"at\":\"2026-10-29T12:00:00Z\",\"requestId\":\"" + requestId
                + "\",\"reference\":null,\"change\":\"assigned\"}]}";
    }

    private ResponseEntity<String> put(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(BASE + path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    public void storesARequestAndRefusesAStaleCopy() {
        String id = UUID.randomUUID().toString();
        assertThat(put("/requests/" + id, request(id, "submitted", 1)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(put("/requests/" + id, request(id, "registered", 2)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> stale = put("/requests/" + id, request(id, "rejected", 2));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("\"error\":\"staleRequest\"");

        ResponseEntity<String> stored = rest.getForEntity(BASE + "/requests/" + id, String.class);
        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored.getBody()).contains("\"registered\"");
        assertThat(rest.getForObject(BASE + "/requests", String.class)).contains(id);
    }

    @Test
    public void publishesCodesWithTheirRequestAndAssignsEachCodeOnce() {
        String first = UUID.randomUUID().toString();
        String plc = "SE" + String.format("%05d", (int) (Math.random() * 99_999));
        ResponseEntity<String> published = put("/requests/" + first + "/with-codes",
                "{\"request\":" + request(first, "published", 1) + ",\"codes\":[" + code(plc, first) + "]}");
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.getForObject(BASE + "/codes/" + plc, String.class)).contains("\"SE00111\"");

        String second = UUID.randomUUID().toString();
        ResponseEntity<String> again = put("/requests/" + second + "/with-codes",
                "{\"request\":" + request(second, "published", 1) + ",\"codes\":[" + code(plc, second) + "]}");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.getForEntity(BASE + "/requests/" + second, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    public void refusesAMalformedCode() {
        ResponseEntity<String> response = put("/codes", "[" + code("SE0001", "r") + "]");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("two letters and five digits");
    }

    @Test
    public void countsReferencesPerYear() {
        String first = rest.postForObject(BASE + "/reference-sequences/2091/next", null, String.class);
        String second = rest.postForObject(BASE + "/reference-sequences/2091/next", null, String.class);
        assertThat(first).isEqualTo("{\"year\":2091,\"value\":1}");
        assertThat(second).isEqualTo("{\"year\":2091,\"value\":2}");
    }
}
