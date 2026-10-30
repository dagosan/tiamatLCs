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
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.SUBSIDIARY;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.array;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.hasValue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.object;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.trimmed;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.*;

/**
 * The request after one step, with the step recorded in its events. Same
 * changes as the frontend's workflow/applyAction.ts and laterStepChanges.ts;
 * here they are made by the register, from what is stored, so a client cannot
 * set a status, a decision or a date of its own.
 */
public final class RequestTransitions {

    private RequestTransitions() {
    }

    /** A copy of `request` with `step` taken. The step must be allowed (StepPermission). */
    public static ObjectNode apply(JsonNode request, Step step) {
        ObjectNode updated = request.deepCopy();
        ObjectNode event = object();
        event.put("at", step.now());
        event.set("actor", step.actor().toJson());
        event.put("action", step.name());
        event.put("note", note(step));
        withArray(updated, "events").add(event);
        change(updated, step);
        return updated;
    }

    private static void change(ObjectNode request, Step step) {
        JsonNode payload = step.payload();
        String now = step.now();
        switch (step.name()) {
            case REGISTER -> {
                request.put("status", "registered");
                request.put("reference", step.reference());
                request.put("registeredAt", now);
            }
            case RETURN_INADMISSIBLE, CLOSE_WITHOUT_REPLY -> {
                request.put("status", "rejected");
                closeOpenSuspension(request, now);
                request.set("decision", decision("reject", RejectionGrounds.INADMISSIBLE,
                        RejectionGrounds.provisionOf(RejectionGrounds.INADMISSIBLE), null, step));
            }
            case REQUEST_INFORMATION -> {
                request.put("status", "informationRequested");
                ObjectNode suspension = object();
                suspension.put("from", now);
                suspension.putNull("to");
                withArray(request, "suspensions").add(suspension);
            }
            case REPLY_TO_INFORMATION_REQUEST -> replyToInformationRequest(request, payload, now);
            case RUN_CHECKS -> runChecks(request, payload, now);
            case CONFIRM_CHECK -> {
                if (hasValue(payload, "confirmedItem")) {
                    withArray(request, "confirmedChecks").add(text(payload, "confirmedItem"));
                }
            }
            case COMPLETE_VALIDATION -> completeValidation(request, step);
            case CONFIRM_POSITION -> {
                JsonNode link = hasValue(payload, "link") ? payload.get("link") : request.path("data").path("rinfLink");
                request.put("status", RequestRouting.statusAfterHarmonisation(request));
                request.set("rinfPosition", position("confirmed", link, step));
                ((ObjectNode) request.get("data")).set("rinfLink", link.isMissingNode() ? NullNode.getInstance() : link);
            }
            case RECORD_NOT_ON_NETWORK -> {
                request.put("status", "awaitingDecision");
                request.set("rinfPosition", position("notOnNetwork", NullNode.getInstance(), step));
            }
            case CONFIRM_UNIQUENESS, RECORD_UNIQUENESS_NOT_SECURED -> {
                request.put("status", "awaitingDecision");
                ObjectNode coordination = object();
                coordination.put("outcome", CONFIRM_UNIQUENESS.equals(step.name()) ? "confirmed" : "notSecured");
                coordination.put("organisation", trimmed(payload, "organisation"));
                coordination.set("by", step.actor().toJson());
                coordination.put("at", now);
                request.set("coordination", coordination);
            }
            case ASSIGN -> {
                request.put("status", "assigned");
                String provision = hasValue(payload, "provision") ? text(payload, "provision") : "";
                request.set("decision", decision("assign", null, provision, text(payload, "code"), step));
            }
            case REJECT -> {
                request.put("status", "rejected");
                String ground = text(payload, "ground");
                String provision = hasValue(payload, "provision")
                        ? text(payload, "provision") : RejectionGrounds.provisionOf(ground);
                request.set("decision", decision("reject", ground, provision, null, step));
            }
            case PUBLISH -> {
                request.put("status", "published");
                request.put("publishedAt", now);
            }
            case NOTIFY_AND_CLOSE -> {
                request.put("status", "closed");
                request.put("notifiedAt", now);
            }
            case ASK_FOR_REVIEW -> askForReview(request, step);
            case UPHOLD_DECISION, REVISE_DECISION -> decideReview(request, step);
            default -> {
                // Steps that only record something.
            }
        }
    }

    private static void replyToInformationRequest(ObjectNode request, JsonNode payload, String now) {
        request.put("status", hasValue(request, "registeredAt") ? "registered" : "submitted");
        closeOpenSuspension(request, now);
        if (payload.path("data").isObject()) {
            request.set("data", payload.get("data").deepCopy());
        }
        // The data may have changed, so the checks are run again.
        request.set("checks", array());
        request.putNull("checksRunAt");
        request.putNull("assignableCode");
        request.set("confirmedChecks", array());
    }

    private static void runChecks(ObjectNode request, JsonNode payload, String now) {
        ArrayNode checks = payload.path("checks").isArray() ? payload.get("checks").deepCopy() : array();
        ArrayNode stillManual = array();
        for (JsonNode confirmed : request.path("confirmedChecks")) {
            for (JsonNode result : checks) {
                if (confirmed.asText().equals(text(result, "item")) && "manual".equals(text(result, "outcome"))) {
                    stillManual.add(confirmed.asText());
                    break;
                }
            }
        }
        request.set("checks", checks);
        request.put("checksRunAt", now);
        request.put("assignableCode", text(payload, "assignableCode"));
        request.set("confirmedChecks", stillManual);
    }

    private static void completeValidation(ObjectNode request, Step step) {
        request.put("status", RequestRouting.statusAfterValidation(request));
        JsonNode recommendation = step.payload().path("recommendation");
        request.set("recommendation", recommendation.isObject() ? recommendation.deepCopy() : NullNode.getInstance());
        JsonNode data = request.path("data");
        if (SUBSIDIARY.equals(text(data, "codeType")) && !hasValue(data, "rinfLink")) {
            request.set("rinfPosition", position("inherited", NullNode.getInstance(), step));
        }
    }

    private static void askForReview(ObjectNode request, Step step) {
        if (!hasValue(request, "decision")) {
            return;
        }
        ObjectNode review = object();
        review.put("askedAt", step.now());
        review.put("reasons", note(step));
        review.putNull("outcome");
        review.set("originalDecision", request.get("decision").deepCopy());
        review.putNull("by");
        request.put("status", "underReview");
        request.set("review", review);
    }

    private static void decideReview(ObjectNode request, Step step) {
        if (!request.path("review").isObject()) {
            return;
        }
        ObjectNode review = (ObjectNode) request.get("review");
        review.set("by", step.actor().toJson());
        if (UPHOLD_DECISION.equals(step.name())) {
            review.put("outcome", "upheld");
            request.put("status", "closed");
            return;
        }
        review.put("outcome", "revised");
        request.put("status", RequestRouting.statusAfterRevision(request));
        request.putNull("decision");
        request.putNull("notifiedAt");
    }

    private static ObjectNode decision(String outcome, String ground, String provision, String code, Step step) {
        ObjectNode decision = object();
        decision.put("outcome", outcome);
        decision.put("ground", ground);
        decision.put("provision", provision);
        decision.put("code", code);
        decision.put("note", note(step));
        decision.set("by", step.actor().toJson());
        decision.put("at", step.now());
        return decision;
    }

    private static ObjectNode position(String outcome, JsonNode link, Step step) {
        ObjectNode position = object();
        position.put("outcome", outcome);
        position.set("link", link.isMissingNode() ? NullNode.getInstance() : link.deepCopy());
        position.set("by", step.actor().toJson());
        position.put("at", step.now());
        return position;
    }

    private static void closeOpenSuspension(ObjectNode request, String now) {
        for (JsonNode suspension : request.path("suspensions")) {
            if (suspension.isObject() && suspension.path("to").isNull()) {
                ((ObjectNode) suspension).put("to", now);
            }
        }
    }

    private static ArrayNode withArray(ObjectNode node, String field) {
        if (!node.path(field).isArray()) {
            node.set(field, array());
        }
        return (ArrayNode) node.get(field);
    }

    private static String note(Step step) {
        return trimmed(step.payload(), "note");
    }
}
