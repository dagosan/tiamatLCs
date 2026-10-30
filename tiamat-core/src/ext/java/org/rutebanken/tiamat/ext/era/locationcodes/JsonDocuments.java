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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.HistoryEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RinfLink;

import java.util.ArrayList;
import java.util.List;

/** Reads the frontend's JSON documents into the records the checks use. */
public final class JsonDocuments {

    /** Jackson 2, which tiamat-core declares itself; kept separate from Spring's own mapper. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonDocuments() {
    }

    public static JsonNode parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw new LocationCodeException(LocationCodeException.BAD_REQUEST, "invalidJson", "empty body");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new LocationCodeException(LocationCodeException.BAD_REQUEST, "invalidJson",
                    e.getOriginalMessage());
        }
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static CodeEntry code(JsonNode node) {
        requireObject(node, "code");
        List<HistoryEntry> history = new ArrayList<>();
        for (JsonNode entry : node.path("history")) {
            history.add(new HistoryEntry(
                    text(entry, "at"), text(entry, "requestId"), text(entry, "reference"), text(entry, "change")));
        }
        return new CodeEntry(
                text(node, "key"),
                text(node, "codeType"),
                text(node, "code"),
                text(node, "parentPrimaryCode"),
                text(node, "country"),
                rinfLink(node.path("rinfLink")),
                text(node, "validFrom"),
                text(node, "closedFrom"),
                history);
    }

    public static RequestEntry request(JsonNode node) {
        requireObject(node, "request");
        JsonNode data = node.path("data");
        return new RequestEntry(
                text(node, "id"),
                text(node, "reference"),
                text(node, "status"),
                text(data, "codeType"),
                text(data, "operation"),
                text(data, "country"),
                text(node, "submittedAt"),
                text(node, "submittedBy"),
                node.path("events").size());
    }

    public static List<JsonNode> events(JsonNode request) {
        List<JsonNode> events = new ArrayList<>();
        request.path("events").forEach(events::add);
        return events;
    }

    static RinfLink rinfLink(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new RinfLink(text(node, "kind"), text(node, "canonicalId"));
    }

    /** A field's text, or null when it is missing or null. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw new LocationCodeException(LocationCodeException.BAD_REQUEST, "invalidJson",
                    "a " + what + " must be a JSON object");
        }
    }
}
