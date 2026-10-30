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

import java.time.LocalDate;
import java.util.Optional;

import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor.CODE_ADMINISTRATOR;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor.REQUESTER;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor.VALIDATING_OFFICER;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.hasValue;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.ASK_FOR_REVIEW;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.ASSIGN;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.COMPLETE_VALIDATION;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.REJECT;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.REVISE_DECISION;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.UPHOLD_DECISION;

/**
 * Whether an actor may take a step on a request now, and if not, why: the
 * status, the role, the four-eyes rule, and what must be done first. Same
 * rules as the frontend's workflow/availableActions.ts and fourEyes.ts; the
 * reasons are the frontend's text keys, so it can show them as they are.
 */
public final class StepPermission {

    /** Text keys of the reasons (abzuKG, static/lang/*.json). */
    public static final String WRONG_STATUS = "rinf_lc_blocked_wrong_status";
    public static final String WRONG_ROLE = "rinf_lc_blocked_wrong_role";
    static final String NOT_SUBMITTER = "rinf_lc_blocked_not_submitter";
    static final String FOUR_EYES = "rinf_lc_blocked_four_eyes";
    static final String RUN_CHECKS = "rinf_lc_blocked_run_checks";
    static final String MANUAL_CHECKS = "rinf_lc_blocked_manual_checks";
    static final String FAILED_CHECKS = "rinf_lc_blocked_failed_checks";
    static final String NOT_ON_NETWORK = "rinf_lc_blocked_not_on_network";
    static final String UNIQUENESS = "rinf_lc_blocked_uniqueness";
    static final String REVISE_ASSIGNMENT = "rinf_lc_blocked_revise_assignment";
    static final String ALREADY_REVIEWED = "rinf_lc_blocked_already_reviewed";
    static final String REVIEW_WINDOW = "rinf_lc_blocked_review_window";

    /** A review may be asked within 20 working days of the notification (procedure 14). */
    static final int REVIEW_REQUEST_DAYS = 20;

    private static final String FAIL = "fail";
    private static final String MANUAL = "manual";

    private StepPermission() {
    }

    /** Why `actor` may not take `step` on `request` today; empty when they may. */
    public static Optional<String> refusal(JsonNode request, String step, Actor actor, LocalDate today) {
        if (!WorkflowSteps.canBeTakenFrom(step, text(request, "status"))) {
            return Optional.of(WRONG_STATUS);
        }
        String role = WorkflowSteps.roleOf(step);
        if (!role.equals(actor.role())) {
            return Optional.of(WRONG_ROLE);
        }
        if (REQUESTER.equals(role) && !Actor.isSamePerson(actor.name(), text(request, "submittedBy"))) {
            return Optional.of(NOT_SUBMITTER);
        }
        if (CODE_ADMINISTRATOR.equals(role) && !mayAdminister(request, actor.name())) {
            return Optional.of(FOUR_EYES);
        }
        return Optional.ofNullable(stepCondition(request, step, actor, today));
    }

    private static String stepCondition(JsonNode request, String step, Actor actor, LocalDate today) {
        switch (step) {
            case COMPLETE_VALIDATION:
                if (!hasValue(request, "checksRunAt")) {
                    return RUN_CHECKS;
                }
                return hasUnconfirmedManualChecks(request) ? MANUAL_CHECKS : null;
            case ASSIGN:
            case REJECT:
            case UPHOLD_DECISION:
            case REVISE_DECISION:
                if (!mayDecide(request, actor.name())) {
                    return FOUR_EYES;
                }
                if (ASSIGN.equals(step)) {
                    return assignCondition(request);
                }
                // A published code is changed by an amendment or deactivation request.
                boolean reviewedAssignment = "assign".equals(
                        text(request.path("review").path("originalDecision"), "outcome"));
                return REVISE_DECISION.equals(step) && reviewedAssignment ? REVISE_ASSIGNMENT : null;
            case ASK_FOR_REVIEW:
                if (hasValue(request, "review")) {
                    return ALREADY_REVIEWED;
                }
                return reviewWindowOpen(request, today) ? null : REVIEW_WINDOW;
            default:
                return null;
        }
    }

    /** A code is assigned only if nothing fatal was found on the way. */
    private static String assignCondition(JsonNode request) {
        for (JsonNode result : request.path("checks")) {
            if (FAIL.equals(text(result, "outcome"))) {
                return FAILED_CHECKS;
            }
        }
        if ("notOnNetwork".equals(text(request.path("rinfPosition"), "outcome"))) {
            return NOT_ON_NETWORK;
        }
        if ("notSecured".equals(text(request.path("coordination"), "outcome"))) {
            return UNIQUENESS;
        }
        return null;
    }

    private static boolean hasUnconfirmedManualChecks(JsonNode request) {
        for (JsonNode result : request.path("checks")) {
            if (MANUAL.equals(text(result, "outcome")) && !isConfirmed(request, text(result, "item"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConfirmed(JsonNode request, String item) {
        for (JsonNode confirmed : request.path("confirmedChecks")) {
            if (confirmed.asText().equals(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The code administrator and the validating officer of a request are
     * different people (procedure Table 3); a review is not decided by the
     * person who took the original decision (section 14).
     */
    static boolean mayDecide(JsonNode request, String name) {
        for (JsonNode event : request.path("events")) {
            Actor recorded = Actor.recorded(event.path("actor"));
            if (CODE_ADMINISTRATOR.equals(recorded.role()) && Actor.isSamePerson(recorded.name(), name)) {
                return false;
            }
        }
        JsonNode original = request.path("review").path("originalDecision");
        return !(original.isObject() && Actor.isSamePerson(text(original.path("by"), "name"), name));
    }

    /**
     * The validating officer may not then administer the same request.
     * Closures as inadmissible are taken by the code administrator and do not count.
     */
    static boolean mayAdminister(JsonNode request, String name) {
        for (JsonNode decision : new JsonNode[]{request.path("decision"), request.path("review").path("originalDecision")}) {
            Actor by = Actor.recorded(decision.path("by"));
            if (decision.isObject() && VALIDATING_OFFICER.equals(by.role()) && Actor.isSamePerson(by.name(), name)) {
                return false;
            }
        }
        return true;
    }

    static boolean reviewWindowOpen(JsonNode request, LocalDate today) {
        if (!hasValue(request, "notifiedAt") || !hasValue(request, "decision")) {
            return false;
        }
        LocalDate notified = WorkingDays.dateOf(text(request, "notifiedAt"));
        return WorkingDays.between(notified, today) <= REVIEW_REQUEST_DAYS;
    }
}
