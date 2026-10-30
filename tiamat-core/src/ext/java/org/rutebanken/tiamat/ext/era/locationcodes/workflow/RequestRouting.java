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

import java.util.Set;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.PRIMARY;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.hasValue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;

/**
 * Where a request goes after validation, harmonisation and a revised review;
 * same rules as the frontend's workflow/routing.ts and laterStepChanges.ts.
 */
public final class RequestRouting {

    /** Prefixes outside the EU (frontend model/countries.ts): uniqueness is confirmed first (8.7). */
    static final Set<String> NON_EU_PREFIXES = Set.of(
            "AL", "BA", "BY", "CH", "GB", "LI", "MD", "ME", "MK", "NO", "RS", "TR", "UA");

    private static final String DEACTIVATION = "deactivation";

    private RequestRouting() {
    }

    public static boolean isCreation(String operation) {
        return "newCode".equals(operation) || "existingCode".equals(operation);
    }

    /** Step 5: a PLC created or amended, or an SLC the requester placed on a RINF element. */
    public static boolean needsRinfHarmonisation(JsonNode request) {
        JsonNode data = request.path("data");
        if (DEACTIVATION.equals(text(data, "operation"))) {
            return false;
        }
        return PRIMARY.equals(text(data, "codeType")) || hasValue(data, "rinfLink");
    }

    /** Non-EU country or legacy range of an international organisation (8.7, 10.3). */
    public static boolean needsExternalCoordination(JsonNode request) {
        JsonNode data = request.path("data");
        return isCreation(text(data, "operation"))
                && (NON_EU_PREFIXES.contains(text(data, "country")) || data.path("legacyCodingRange").asBoolean(false));
    }

    public static String statusAfterValidation(JsonNode request) {
        return needsRinfHarmonisation(request) ? "rinfHarmonisation" : statusAfterHarmonisation(request);
    }

    public static String statusAfterHarmonisation(JsonNode request) {
        return needsExternalCoordination(request) ? "externalCoordination" : "awaitingDecision";
    }

    /** After a review revises a decision: back to the step still open. */
    public static String statusAfterRevision(JsonNode request) {
        if (!hasValue(request, "registeredAt")) {
            return "submitted";
        }
        return hasValue(request, "checksRunAt") ? "awaitingDecision" : "registered";
    }
}
