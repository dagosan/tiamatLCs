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
 * Added for the ERA Location Code Manager, 2026-11-01.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EraLoginTest {

    private final EraLogin login = new EraLogin(true, "name", "lcm_roles");

    @AfterEach
    void logOut() {
        SecurityContextHolder.clearContext();
    }

    private static void logIn(Map<String, Object> claims) {
        Jwt.Builder token = Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1");
        claims.forEach(token::claim);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token.build()));
    }

    private static int statusOf(Runnable call) {
        return assertThrows(LocationCodeException.class, call::run).status();
    }

    @Test
    void takesTheNameFromTheAccountAndARoleItHolds() {
        logIn(Map.of("name", "Carl Admin", "lcm_roles", List.of("codeAdministrator", "validatingOfficer")));
        assertEquals(new Actor("Carl Admin", Actor.VALIDATING_OFFICER), login.actorFor(Actor.VALIDATING_OFFICER));
    }

    @Test
    void refusesARoleTheAccountDoesNotHold() {
        logIn(Map.of("name", "Anna", "lcm_roles", List.of("requester")));
        assertEquals(EraLogin.FORBIDDEN, statusOf(() -> login.actorFor(Actor.CODE_ADMINISTRATOR)));
        assertEquals(EraLogin.FORBIDDEN, statusOf(() -> login.requireRole(Actor.CODE_ADMINISTRATOR)));
    }

    @Test
    void refusesWithoutALogin() {
        assertEquals(EraLogin.UNAUTHORIZED, statusOf(() -> login.actorFor(Actor.REQUESTER)));
        assertEquals(EraLogin.UNAUTHORIZED, statusOf(login::requireLogin));
    }

    @Test
    void readsKeycloakRealmRolesAndUserNameWhenTheClaimsAreMissing() {
        logIn(Map.of("preferred_username", "anna", "realm_access", Map.of("roles", List.of("requester"))));
        assertEquals(new Actor("anna", Actor.REQUESTER), login.actorFor(Actor.REQUESTER));
    }

    @Test
    void letsRequestsThroughWhenNoLoginIsRequired() {
        EraLogin open = new EraLogin(false, "name", "lcm_roles");
        assertDoesNotThrow(open::requireLogin);
        assertDoesNotThrow(() -> open.requireRole(Actor.CODE_ADMINISTRATOR));
    }
}
