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
 * Modified 2026-10-30: requests change only through POST /requests (submit)
 * and POST /requests/{id}/actions (one step); the raw saves are gone.
 * Modified 2026-11-01: with era.location-codes.login.required, steps are taken
 * by the logged-in account (EraLogin) and requests need a login.
 * Modified 2026-11-02: code versions with CRD's columns.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeService;
import org.rutebanken.tiamat.ext.era.locationcodes.auth.EraLogin;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API of the location code register, one endpoint per method of the
 * frontend's LocationCodeStore (abzuKG, src/rinf/locationCodes/store). The
 * register takes each workflow step itself; documents are passed as text.
 */
@Profile(LocationCodeController.PROFILE)
@RestController
@RequestMapping(value = LocationCodeController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class LocationCodeController {

    public static final String PROFILE = "era-location-codes";
    public static final String BASE_PATH = "/era/v1/location-codes";

    private static final Logger logger = LoggerFactory.getLogger(LocationCodeController.class);

    private final LocationCodeService service;
    private final EraLogin login;

    public LocationCodeController(LocationCodeService service, EraLogin login) {
        this.service = service;
        this.login = login;
    }

    /**
     * With a login required, the actor of a submission or step is the
     * logged-in account: its name, and the role asked for if it holds it.
     */
    private String withAccountActor(String body) {
        if (!login.required()) {
            return body;
        }
        JsonNode parsed = JsonDocuments.parse(body);
        if (!(parsed instanceof ObjectNode object)) {
            return body;
        }
        String role = object.path("actor").path("role").asText("");
        object.set("actor", login.actorFor(role).toJson());
        return JsonDocuments.write(object);
    }

    @GetMapping("/requests")
    public String requests() {
        login.requireLogin();
        return service.requestsJson();
    }

    @GetMapping("/requests/{id}")
    public String request(@PathVariable String id) {
        login.requireLogin();
        return service.requestJson(id);
    }

    /** Submits a request: {"actor": {"name", "role": "requester"}, "data": …}; returns it with its id (201). */
    @PostMapping(value = "/requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submit(@RequestBody String body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(withAccountActor(body)));
    }

    /**
     * Takes one step: {"action", "actor", "payload", "expectedEventCount"}.
     * Returns {"request": …, "codes": […]}, the request after the step and the
     * register entries it changed (a publication).
     */
    @PostMapping(value = "/requests/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String takeStep(@PathVariable String id, @RequestBody String body) {
        return service.takeStep(id, withAccountActor(body));
    }

    @GetMapping("/requests/{id}/changes")
    public String requestChanges(@PathVariable String id) {
        login.requireLogin();
        return service.changeLogJson("request", id);
    }

    /**
     * The register, one entry per code version (CRD's columns); filters: country
     * (prefix), parentPrimaryCode, activeOn (YYYY-MM-DD: versions valid that day).
     */
    @GetMapping("/codes")
    public String codes(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String parentPrimaryCode,
            @RequestParam(required = false) String activeOn) {
        return service.codesJson(country, parentPrimaryCode, activeOn);
    }

    /** The versions of one code by key: "BE00438", or "BE00438/0000000001" (slash encoded as %2F or as a path). */
    @GetMapping("/codes/{primary}")
    public String code(@PathVariable String primary) {
        return service.codeJson(primary);
    }

    @GetMapping("/codes/{primary}/{subsidiary}")
    public String subsidiaryCode(@PathVariable String primary, @PathVariable String subsidiary) {
        return service.codeJson(primary + "/" + subsidiary);
    }

    @GetMapping("/codes/{primary}/changes")
    public String codeChanges(@PathVariable String primary) {
        login.requireLogin();
        return service.changeLogJson("code", primary);
    }

    /** Everything, in the format of the frontend's backup file. */
    @GetMapping("/snapshot")
    public String snapshot() {
        login.requireRole(Actor.CODE_ADMINISTRATOR);
        return service.snapshotJson();
    }

    /** Loads a backup file; refuses it whole if it disagrees with what is stored. */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String importSnapshot(@RequestBody String body) {
        login.requireRole(Actor.CODE_ADMINISTRATOR);
        return service.importSnapshot(body);
    }

    @ExceptionHandler(LocationCodeException.class)
    public ResponseEntity<String> refused(LocationCodeException exception) {
        if (exception.status() >= 500) {
            logger.warn("Location code register: {}", exception.getMessage());
        }
        ObjectNode body = JsonDocuments.MAPPER.createObjectNode();
        body.put("error", exception.error());
        body.put("message", exception.getMessage());
        exception.details().forEach(body.putArray("details")::add);
        return ResponseEntity.status(exception.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonDocuments.write(body));
    }
}
