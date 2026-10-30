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
 * Added for the ERA Location Code Manager, 2026-10-30.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;

import java.util.Locale;

/** Small readers of the frontend's request documents (LocationCodeRequest). */
final class RequestDocuments {

    private RequestDocuments() {
    }

    /** A field's text, or null when it is missing or null. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** A field's text without surrounding spaces; "" when missing. */
    static String trimmed(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? "" : value.trim();
    }

    /** The field is there and not null. */
    static boolean hasValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return !value.isMissingNode() && !value.isNull();
    }

    /** A PLC as the frontend writes it: no spaces, upper case. */
    static String normalisedPrimaryCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    static ObjectNode object() {
        return JsonDocuments.MAPPER.createObjectNode();
    }

    static ArrayNode array() {
        return JsonDocuments.MAPPER.createArrayNode();
    }
}
