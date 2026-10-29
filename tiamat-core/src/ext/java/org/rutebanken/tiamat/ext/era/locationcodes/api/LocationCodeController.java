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
 * Added for the ERA location code register, 2026-10-29.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API of the location code register, one endpoint per method of the
 * frontend's LocationCodeStore (abzuKG, src/rinf/locationCodes/store). Bodies
 * are the frontend's own JSON documents, passed through as text.
 */
@Profile(LocationCodeController.PROFILE)
@RestController
@RequestMapping(value = LocationCodeController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class LocationCodeController {

    public static final String PROFILE = "era-location-codes";
    public static final String BASE_PATH = "/era/v1/location-codes";

    private static final Logger logger = LoggerFactory.getLogger(LocationCodeController.class);

    private final LocationCodeService service;

    public LocationCodeController(LocationCodeService service) {
        this.service = service;
    }

    @GetMapping("/requests")
    public String requests() {
        return service.requestsJson();
    }

    @GetMapping("/requests/{id}")
    public String request(@PathVariable String id) {
        return service.requestJson(id);
    }

    /** Creates or replaces a request; a replacement must add events to the stored ones. */
    @PutMapping(value = "/requests/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> saveRequest(@PathVariable String id, @RequestBody String body) {
        service.saveRequest(id, body);
        return ResponseEntity.noContent().build();
    }

    /** A step that changes the register too: {"request": …, "codes": […]}, saved together. */
    @PutMapping(value = "/requests/{id}/with-codes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> saveRequestWithCodes(@PathVariable String id, @RequestBody String body) {
        service.saveRequestWithCodes(id, body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/requests/{id}/changes")
    public String requestChanges(@PathVariable String id) {
        return service.changeLogJson("request", id);
    }

    /** The register; filters: country (prefix), parentPrimaryCode, activeOn (YYYY-MM-DD). */
    @GetMapping("/codes")
    public String codes(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String parentPrimaryCode,
            @RequestParam(required = false) String activeOn) {
        return service.codesJson(country, parentPrimaryCode, activeOn);
    }

    /** One code by key: "BE00438", or "BE00438/0000000001" (slash encoded as %2F or as a path). */
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
        return service.changeLogJson("code", primary);
    }

    /** Creates or replaces each code with the same key, all or none. */
    @PutMapping(value = "/codes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> saveCodes(@RequestBody String body) {
        service.saveCodes(body);
        return ResponseEntity.noContent().build();
    }

    /** Next request reference number of a year, starting at 1. */
    @PostMapping("/reference-sequences/{year}/next")
    public String nextReferenceSequence(@PathVariable int year) {
        return "{\"year\":" + year + ",\"value\":" + service.nextReferenceSequence(year) + "}";
    }

    /** Everything, in the format of the frontend's backup file. */
    @GetMapping("/snapshot")
    public String snapshot() {
        return service.snapshotJson();
    }

    /** Loads a backup file; refuses it whole if it disagrees with what is stored. */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String importSnapshot(@RequestBody String body) {
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
