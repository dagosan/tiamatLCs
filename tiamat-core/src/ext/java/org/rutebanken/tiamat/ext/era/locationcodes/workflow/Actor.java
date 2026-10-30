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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException;

import java.util.Locale;
import java.util.Set;

/**
 * Who takes a step: a person's name and the role they act in (procedure
 * section 5). Until the register has a login of its own, the frontend says
 * who is acting; the rules below are applied to that actor all the same.
 */
public record Actor(String name, String role) {

    public static final String REQUESTER = "requester";
    public static final String CODE_ADMINISTRATOR = "codeAdministrator";
    public static final String RINF_CORRESPONDENT = "rinfCorrespondent";
    public static final String EXTERNAL_COORDINATION_POINT = "externalCoordinationPoint";
    public static final String VALIDATING_OFFICER = "validatingOfficer";

    public static final Set<String> ROLES = Set.of(
            REQUESTER, CODE_ADMINISTRATOR, RINF_CORRESPONDENT, EXTERNAL_COORDINATION_POINT, VALIDATING_OFFICER);

    /** Reads {"name": …, "role": …}; refuses a missing name or an unknown role. */
    public static Actor fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("actor must be an object with name and role");
        }
        String name = node.path("name").asText("").trim();
        String role = node.path("role").asText("");
        if (name.isEmpty()) {
            throw invalid("actor.name is empty");
        }
        if (!ROLES.contains(role)) {
            throw invalid("unknown actor.role \"" + role + "\"");
        }
        return new Actor(name, role);
    }

    /** The actor as the request file records it. */
    public ObjectNode toJson() {
        ObjectNode node = JsonDocuments.MAPPER.createObjectNode();
        node.put("name", name);
        node.put("role", role);
        return node;
    }

    /** The actor recorded in a request file, e.g. a decision's "by". */
    public static Actor recorded(JsonNode node) {
        return new Actor(node.path("name").asText(""), node.path("role").asText(""));
    }

    /** Names are compared without case or surrounding spaces, as in the frontend. */
    public static boolean isSamePerson(String first, String second) {
        return normalised(first).equals(normalised(second));
    }

    private static String normalised(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static LocationCodeException invalid(String detail) {
        return new LocationCodeException(LocationCodeException.BAD_REQUEST, "invalidActor", detail);
    }
}
