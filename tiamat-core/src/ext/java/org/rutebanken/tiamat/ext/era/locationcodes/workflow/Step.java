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

/**
 * One step as the register takes it.
 *
 * @param name      the step, e.g. "register" (WorkflowSteps)
 * @param actor     who takes it
 * @param payload   what the frontend sends with it (ActionPayload in the frontend)
 * @param now       when, as an ISO instant from the server's clock
 * @param reference the request reference the register gave, for "register"; else null
 */
public record Step(String name, Actor actor, JsonNode payload, String now, String reference) {
}
