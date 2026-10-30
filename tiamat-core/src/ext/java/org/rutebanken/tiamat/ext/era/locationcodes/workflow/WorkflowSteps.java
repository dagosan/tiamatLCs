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

import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor.CODE_ADMINISTRATOR;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor.EXTERNAL_COORDINATION_POINT;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor.REQUESTER;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor.RINF_CORRESPONDENT;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor.VALIDATING_OFFICER;

/**
 * The steps of the procedure: which role takes each, and from which statuses.
 * Same table as the frontend (abzuKG, src/rinf/locationCodes/workflow/
 * actions.ts and availableActions.ts); a change to one is a change to both.
 */
public final class WorkflowSteps {

    public static final String SUBMIT = "submit";
    public static final String REGISTER = "register";
    public static final String RETURN_INADMISSIBLE = "returnInadmissible";
    public static final String REQUEST_INFORMATION = "requestInformation";
    public static final String REPLY_TO_INFORMATION_REQUEST = "replyToInformationRequest";
    public static final String CLOSE_WITHOUT_REPLY = "closeWithoutReply";
    public static final String RUN_CHECKS = "runChecks";
    public static final String CONFIRM_CHECK = "confirmCheck";
    public static final String COMPLETE_VALIDATION = "completeValidation";
    public static final String CONFIRM_POSITION = "confirmPosition";
    public static final String RECORD_NOT_ON_NETWORK = "recordNotOnNetwork";
    public static final String CONFIRM_UNIQUENESS = "confirmUniqueness";
    public static final String RECORD_UNIQUENESS_NOT_SECURED = "recordUniquenessNotSecured";
    public static final String ASSIGN = "assign";
    public static final String REJECT = "reject";
    public static final String PUBLISH = "publish";
    public static final String NOTIFY_AND_CLOSE = "notifyAndClose";
    public static final String ASK_FOR_REVIEW = "askForReview";
    public static final String UPHOLD_DECISION = "upholdDecision";
    public static final String REVISE_DECISION = "reviseDecision";

    /** The role each step belongs to (procedure sections 5 and 8). */
    static final Map<String, String> ROLE_OF_STEP = Map.ofEntries(
            entry(SUBMIT, REQUESTER),
            entry(REGISTER, CODE_ADMINISTRATOR),
            entry(RETURN_INADMISSIBLE, CODE_ADMINISTRATOR),
            entry(REQUEST_INFORMATION, CODE_ADMINISTRATOR),
            entry(REPLY_TO_INFORMATION_REQUEST, REQUESTER),
            entry(CLOSE_WITHOUT_REPLY, CODE_ADMINISTRATOR),
            entry(RUN_CHECKS, CODE_ADMINISTRATOR),
            entry(CONFIRM_CHECK, CODE_ADMINISTRATOR),
            entry(COMPLETE_VALIDATION, CODE_ADMINISTRATOR),
            entry(CONFIRM_POSITION, RINF_CORRESPONDENT),
            entry(RECORD_NOT_ON_NETWORK, RINF_CORRESPONDENT),
            entry(CONFIRM_UNIQUENESS, EXTERNAL_COORDINATION_POINT),
            entry(RECORD_UNIQUENESS_NOT_SECURED, EXTERNAL_COORDINATION_POINT),
            entry(ASSIGN, VALIDATING_OFFICER),
            entry(REJECT, VALIDATING_OFFICER),
            entry(PUBLISH, CODE_ADMINISTRATOR),
            entry(NOTIFY_AND_CLOSE, CODE_ADMINISTRATOR),
            entry(ASK_FOR_REVIEW, REQUESTER),
            entry(UPHOLD_DECISION, VALIDATING_OFFICER),
            entry(REVISE_DECISION, VALIDATING_OFFICER));

    /** Statuses each step can be taken from; submitting creates the request. */
    static final Map<String, Set<String>> STATUSES_OF_STEP = Map.ofEntries(
            entry(SUBMIT, Set.of()),
            entry(REGISTER, Set.of("submitted")),
            entry(RETURN_INADMISSIBLE, Set.of("submitted")),
            entry(REQUEST_INFORMATION, Set.of("submitted", "registered")),
            entry(REPLY_TO_INFORMATION_REQUEST, Set.of("informationRequested")),
            entry(CLOSE_WITHOUT_REPLY, Set.of("informationRequested")),
            entry(RUN_CHECKS, Set.of("registered")),
            entry(CONFIRM_CHECK, Set.of("registered")),
            entry(COMPLETE_VALIDATION, Set.of("registered")),
            entry(CONFIRM_POSITION, Set.of("rinfHarmonisation")),
            entry(RECORD_NOT_ON_NETWORK, Set.of("rinfHarmonisation")),
            entry(CONFIRM_UNIQUENESS, Set.of("externalCoordination")),
            entry(RECORD_UNIQUENESS_NOT_SECURED, Set.of("externalCoordination")),
            entry(ASSIGN, Set.of("awaitingDecision")),
            entry(REJECT, Set.of("awaitingDecision")),
            entry(PUBLISH, Set.of("assigned")),
            entry(NOTIFY_AND_CLOSE, Set.of("published", "rejected")),
            entry(ASK_FOR_REVIEW, Set.of("closed")),
            entry(UPHOLD_DECISION, Set.of("underReview")),
            entry(REVISE_DECISION, Set.of("underReview")));

    private WorkflowSteps() {
    }

    public static boolean isStep(String step) {
        return step != null && ROLE_OF_STEP.containsKey(step);
    }

    public static String roleOf(String step) {
        return ROLE_OF_STEP.get(step);
    }

    public static boolean canBeTakenFrom(String step, String status) {
        return STATUSES_OF_STEP.getOrDefault(step, Set.of()).contains(status);
    }
}
