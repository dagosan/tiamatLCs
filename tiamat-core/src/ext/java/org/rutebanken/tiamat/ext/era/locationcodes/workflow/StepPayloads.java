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
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.PRIMARY;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.hasValue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.ASSIGN;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.COMPLETE_VALIDATION;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.CONFIRM_CHECK;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.CONFIRM_POSITION;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.REJECT;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.REPLY_TO_INFORMATION_REQUEST;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.RUN_CHECKS;

/**
 * What a step's payload must hold. Facts the register cannot work out itself
 * yet, the results of the technical checks and the value to assign, still
 * come from the frontend; their form is checked here, and the value is checked
 * again against the register and the Knowledge Graph when it is published.
 */
public final class StepPayloads {

    static final Set<String> CHECK_OUTCOMES = Set.of("pass", "fail", "warning", "manual", "notApplicable");
    private static final Set<String> RECOMMENDATIONS = Set.of("assign", "reject");

    private StepPayloads() {
    }

    /** Problems with `payload` for `step` on `request`; empty when it is fine. */
    public static List<String> problems(JsonNode request, String step, JsonNode payload) {
        List<String> problems = new ArrayList<>();
        if (!payload.isObject()) {
            problems.add("payload must be an object");
            return problems;
        }
        if (hasValue(payload, "note") && !payload.get("note").isTextual()) {
            problems.add("payload.note must be text");
        }
        switch (step) {
            case RUN_CHECKS -> checkResults(payload, problems);
            case CONFIRM_CHECK -> confirmedItem(request, payload, problems);
            case COMPLETE_VALIDATION -> recommendation(payload, problems);
            case CONFIRM_POSITION -> link(request, payload, problems);
            case REPLY_TO_INFORMATION_REQUEST -> {
                if (!payload.path("data").isObject()) {
                    problems.add("payload.data must be the request's data");
                }
            }
            case ASSIGN -> {
                if (text(payload, "code") == null || text(payload, "code").isBlank()) {
                    problems.add("payload.code is empty");
                }
            }
            case REJECT -> {
                if (!RejectionGrounds.isGround(text(payload, "ground"))) {
                    problems.add("payload.ground must be a ground of Annex B, not \"" + text(payload, "ground") + "\"");
                }
            }
            default -> {
                // No payload beyond a note.
            }
        }
        return problems;
    }

    private static void checkResults(JsonNode payload, List<String> problems) {
        if (!payload.path("checks").isArray()) {
            problems.add("payload.checks must be a list of check results");
            return;
        }
        for (JsonNode result : payload.get("checks")) {
            if (text(result, "item") == null || !CHECK_OUTCOMES.contains(text(result, "outcome"))) {
                problems.add("each check result needs an item and an outcome of " + CHECK_OUTCOMES);
                return;
            }
        }
        JsonNode code = payload.path("assignableCode");
        if (!code.isMissingNode() && !code.isNull() && !code.isTextual()) {
            problems.add("payload.assignableCode must be text or null");
        }
    }

    /** Only a check the last run marked for manual review, and only once. */
    private static void confirmedItem(JsonNode request, JsonNode payload, List<String> problems) {
        String item = text(payload, "confirmedItem");
        boolean isManual = false;
        for (JsonNode result : request.path("checks")) {
            isManual |= "manual".equals(text(result, "outcome")) && result.path("item").asText().equals(item);
        }
        boolean alreadyConfirmed = false;
        for (JsonNode confirmed : request.path("confirmedChecks")) {
            alreadyConfirmed |= confirmed.asText().equals(item);
        }
        if (!isManual || alreadyConfirmed) {
            problems.add("check \"" + item + "\" is not waiting for a manual confirmation");
        }
    }

    private static void recommendation(JsonNode payload, List<String> problems) {
        JsonNode recommendation = payload.path("recommendation");
        if (recommendation.isMissingNode() || recommendation.isNull()) {
            return;
        }
        String ground = text(recommendation, "ground");
        if (!RECOMMENDATIONS.contains(text(recommendation, "outcome"))
                || (ground != null && !RejectionGrounds.isGround(ground))) {
            problems.add("payload.recommendation needs an outcome assign or reject, and a ground of Annex B or null");
        }
    }

    private static void link(JsonNode request, JsonNode payload, List<String> problems) {
        if (!hasValue(payload, "link")) {
            return;
        }
        boolean primary = PRIMARY.equals(text(request.path("data"), "codeType"));
        LocationCodeRules.linkProblems(JsonDocuments.rinfLink(payload.get("link")), primary)
                .forEach(problem -> problems.add("payload.link: " + problem));
    }
}
