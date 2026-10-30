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
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules;

import java.util.ArrayList;
import java.util.List;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.PRIMARY;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.normalisedPrimaryCode;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.trimmed;

/** The value a decision assigns: its form, and the register key it would take. */
public final class AssignedCodes {

    private static final String KEY_SEPARATOR = "/";

    private AssignedCodes() {
    }

    /** The register key of `code` for this request: the PLC, or "<parent PLC>/<SLC>". */
    public static String keyOf(JsonNode request, String code) {
        JsonNode data = request.path("data");
        if (PRIMARY.equals(text(data, "codeType"))) {
            return normalisedPrimaryCode(code);
        }
        return normalisedPrimaryCode(text(data, "parentPrimaryCode")) + KEY_SEPARATOR + code.trim();
    }

    /**
     * Whether `code` can be what this request's decision assigns: a well-formed
     * code of the request's country, or, for a change, the code the request is about.
     */
    public static List<String> problems(JsonNode request, String code) {
        List<String> problems = new ArrayList<>();
        JsonNode data = request.path("data");
        boolean primary = PRIMARY.equals(text(data, "codeType"));
        String value = primary ? normalisedPrimaryCode(code) : code.trim();
        if (!RequestRouting.isCreation(text(data, "operation"))) {
            String target = primary ? normalisedPrimaryCode(text(data, "targetCode")) : trimmed(data, "targetCode");
            if (!value.equals(target)) {
                problems.add("a change concerns " + target + ", not " + value);
            }
            return problems;
        }
        if (primary) {
            if (!LocationCodeRules.PRIMARY_CODE.matcher(value).matches()) {
                problems.add("PLC \"" + value + "\" is not two letters and five digits");
            } else if (!value.startsWith(trimmed(data, "country"))) {
                problems.add("PLC " + value + " does not start with the request's country " + trimmed(data, "country"));
            }
            return problems;
        }
        if (!LocationCodeRules.SUBSIDIARY_CODE.matcher(value).matches()) {
            problems.add("SLC \"" + value + "\" is not 1 to 10 letters, digits, dots or hyphens");
        }
        return problems;
    }
}
