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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowFixtures.*;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.WorkflowSteps.*;

class StepPermissionTest {

    private static Optional<String> refusal(ObjectNode request, String step, Actor actor) {
        return StepPermission.refusal(request, step, actor, TODAY);
    }

    @Test
    void aStepIsTakenOnlyFromItsStatuses() {
        ObjectNode submitted = newPrimaryCode("SE");
        assertEquals(Optional.empty(), refusal(submitted, REGISTER, CARL));
        assertEquals(Optional.of(StepPermission.WRONG_STATUS), refusal(submitted, ASSIGN, VERA));
        assertEquals(Optional.of(StepPermission.WRONG_STATUS), refusal(submitted, PUBLISH, CARL));
    }

    @Test
    void aStepBelongsToOneRole() {
        ObjectNode submitted = newPrimaryCode("SE");
        assertEquals(Optional.of(StepPermission.WRONG_ROLE), refusal(submitted, REGISTER, VERA));
        assertEquals(Optional.of(StepPermission.WRONG_ROLE), refusal(submitted, REGISTER, ANNA));
    }

    @Test
    void onlyTheSubmitterRepliesToARequestForInformation() {
        ObjectNode asked = take(newPrimaryCode("SE"), REQUEST_INFORMATION, CARL);
        Actor someoneElse = new Actor("Bob", Actor.REQUESTER);
        assertEquals(Optional.of(StepPermission.NOT_SUBMITTER), refusal(asked, REPLY_TO_INFORMATION_REQUEST, someoneElse));
        Actor sameNameOtherCase = new Actor("  anna requester ", Actor.REQUESTER);
        assertEquals(Optional.empty(), refusal(asked, REPLY_TO_INFORMATION_REQUEST, sameNameOtherCase));
    }

    @Test
    void validationNeedsTheChecksRunAndManualChecksConfirmed() {
        ObjectNode registered = take(newPrimaryCode("SE"), REGISTER, CARL);
        assertEquals(Optional.of(StepPermission.RUN_CHECKS), refusal(registered, COMPLETE_VALIDATION, CARL));

        ObjectNode checked = take(registered, RUN_CHECKS, CARL,
                "{'checks':[{'item':'retailDoublet','outcome':'manual'}],'assignableCode':'SE00001'}");
        assertEquals(Optional.of(StepPermission.MANUAL_CHECKS), refusal(checked, COMPLETE_VALIDATION, CARL));

        ObjectNode confirmed = take(checked, CONFIRM_CHECK, CARL, "{'confirmedItem':'retailDoublet'}");
        assertEquals(Optional.empty(), refusal(confirmed, COMPLETE_VALIDATION, CARL));
    }

    @Test
    void theAdministratorOfARequestDoesNotDecideIt() {
        ObjectNode positioned = take(validated(newPrimaryCode("SE")), CONFIRM_POSITION, RITA);
        Actor carlAsOfficer = new Actor(CARL.name(), Actor.VALIDATING_OFFICER);
        assertEquals(Optional.of(StepPermission.FOUR_EYES), refusal(positioned, ASSIGN, carlAsOfficer));
        assertEquals(Optional.empty(), refusal(positioned, ASSIGN, VERA));
    }

    @Test
    void theOfficerWhoDecidedDoesNotThenAdministerIt() {
        ObjectNode positioned = take(validated(newPrimaryCode("SE")), CONFIRM_POSITION, RITA);
        ObjectNode assigned = take(positioned, ASSIGN, VERA, "{'code':'SE00001'}");
        Actor veraAsAdministrator = new Actor(VERA.name(), Actor.CODE_ADMINISTRATOR);
        assertEquals(Optional.of(StepPermission.FOUR_EYES), refusal(assigned, PUBLISH, veraAsAdministrator));
        assertEquals(Optional.empty(), refusal(assigned, PUBLISH, CARL));
    }

    @Test
    void aFailedCheckOrANegativeFindingBlocksAssignment() {
        ObjectNode registered = take(newPrimaryCode("SE"), REGISTER, CARL);
        ObjectNode failed = take(registered, RUN_CHECKS, CARL, "{'checks':[{'item':'noDuplicate','outcome':'fail'}]}");
        ObjectNode recommended = take(take(failed, COMPLETE_VALIDATION, CARL), RECORD_NOT_ON_NETWORK, RITA);
        assertEquals(Optional.of(StepPermission.FAILED_CHECKS), refusal(recommended, ASSIGN, VERA));
        assertEquals(Optional.empty(), refusal(recommended, REJECT, VERA));

        ObjectNode notOnNetwork = take(validated(newPrimaryCode("SE")), RECORD_NOT_ON_NETWORK, RITA);
        assertEquals(Optional.of(StepPermission.NOT_ON_NETWORK), refusal(notOnNetwork, ASSIGN, VERA));

        ObjectNode positioned = take(validated(newPrimaryCode("NO")), CONFIRM_POSITION, RITA);
        ObjectNode notSecured = take(positioned, RECORD_UNIQUENESS_NOT_SECURED, ERIK);
        assertEquals(Optional.of(StepPermission.UNIQUENESS), refusal(notSecured, ASSIGN, VERA));
    }

    @Test
    void aReviewIsAskedOnceWithinTwentyWorkingDays() {
        ObjectNode positioned = take(validated(newPrimaryCode("SE")), CONFIRM_POSITION, RITA);
        ObjectNode closed = take(take(positioned, REJECT, VERA, "{'ground':'duplicate'}"), NOTIFY_AND_CLOSE, CARL);
        // Notified Thursday 29 October: 26 November is the 20th working day, 27 November the 21st.
        assertEquals(Optional.empty(),
                StepPermission.refusal(closed, ASK_FOR_REVIEW, ANNA, java.time.LocalDate.parse("2026-11-26")));
        assertEquals(Optional.of(StepPermission.REVIEW_WINDOW),
                StepPermission.refusal(closed, ASK_FOR_REVIEW, ANNA, java.time.LocalDate.parse("2026-11-27")));

        ObjectNode underReview = take(closed, ASK_FOR_REVIEW, ANNA, "{'note':'The code is not a duplicate'}");
        ObjectNode upheld = take(underReview, UPHOLD_DECISION, new Actor("Olga Officer", Actor.VALIDATING_OFFICER));
        assertEquals(Optional.of(StepPermission.ALREADY_REVIEWED), refusal(upheld, ASK_FOR_REVIEW, ANNA));
    }

    @Test
    void aReviewIsNotDecidedByTheOriginalOfficerAndDoesNotReviseAnAssignment() {
        ObjectNode positioned = take(validated(newPrimaryCode("SE")), CONFIRM_POSITION, RITA);
        ObjectNode rejected = take(take(positioned, REJECT, VERA, "{'ground':'duplicate'}"), NOTIFY_AND_CLOSE, CARL);
        ObjectNode rejectionUnderReview = take(rejected, ASK_FOR_REVIEW, ANNA);
        assertEquals(Optional.of(StepPermission.FOUR_EYES), refusal(rejectionUnderReview, REVISE_DECISION, VERA));

        ObjectNode published = take(take(positioned, ASSIGN, VERA, "{'code':'SE00001'}"), PUBLISH, CARL);
        ObjectNode assignmentUnderReview = take(take(published, NOTIFY_AND_CLOSE, CARL), ASK_FOR_REVIEW, ANNA);
        Actor olga = new Actor("Olga Officer", Actor.VALIDATING_OFFICER);
        assertEquals(Optional.of(StepPermission.REVISE_ASSIGNMENT), refusal(assignmentUnderReview, REVISE_DECISION, olga));
        assertEquals(Optional.empty(), refusal(assignmentUnderReview, UPHOLD_DECISION, olga));
    }
}
