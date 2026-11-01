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

import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeException;
import org.rutebanken.tiamat.ext.era.locationcodes.api.LocationCodeController;
import org.rutebanken.tiamat.ext.era.locationcodes.workflow.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who is logged in, from the access token Tiamat has already checked
 * (signature and expiry, against the login service's keys). When
 * era.location-codes.login.required is true, every step is taken by the
 * logged-in account, under its name and in a role it holds, and requests
 * are only shown to logged-in users; the register of codes stays public.
 * Off by default, as in development and in the tests.
 */
@Profile(LocationCodeController.PROFILE)
@Component
public class EraLogin {

    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;

    /** Keycloak's own place for realm roles, used when the roles claim is absent. */
    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String REALM_ROLES = "roles";
    private static final String USERNAME_CLAIM = "preferred_username";

    /** The account behind a request: its display name and workflow roles. */
    public record LoggedIn(String name, List<String> roles) {
    }

    private final boolean required;
    private final String nameClaim;
    private final String rolesClaim;

    public EraLogin(
            @Value("${era.location-codes.login.required:false}") boolean required,
            @Value("${era.location-codes.login.name-claim:name}") String nameClaim,
            @Value("${era.location-codes.login.roles-claim:lcm_roles}") String rolesClaim) {
        this.required = required;
        this.nameClaim = nameClaim;
        this.rolesClaim = rolesClaim;
    }

    public boolean required() {
        return required;
    }

    /** The logged-in account, if the request carries a valid token. */
    public Optional<LoggedIn> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return Optional.empty();
        }
        Jwt jwt = token.getToken();
        return Optional.of(new LoggedIn(nameOf(jwt), rolesOf(jwt)));
    }

    /** The actor of a step or submission: the account, in `role`, which it must hold. */
    public Actor actorFor(String role) {
        LoggedIn user = loggedIn();
        if (!Actor.ROLES.contains(role)) {
            throw new LocationCodeException(LocationCodeException.BAD_REQUEST, "invalidActor",
                    "unknown actor.role \"" + role + "\"");
        }
        if (!user.roles().contains(role)) {
            throw new LocationCodeException(FORBIDDEN, "roleNotHeld",
                    "the account does not hold the role " + role);
        }
        return new Actor(user.name(), role);
    }

    /** Refuses a request without a login, when a login is required. */
    public void requireLogin() {
        if (required) {
            loggedIn();
        }
    }

    /** Refuses a request from an account without `role`, when a login is required. */
    public void requireRole(String role) {
        if (required && !loggedIn().roles().contains(role)) {
            throw new LocationCodeException(FORBIDDEN, "roleNotHeld",
                    "the account does not hold the role " + role);
        }
    }

    private LoggedIn loggedIn() {
        return current().orElseThrow(() -> new LocationCodeException(UNAUTHORIZED, "loginRequired",
                "log in to the Location Code Manager first"));
    }

    private String nameOf(Jwt jwt) {
        for (String candidate : new String[] {jwt.getClaimAsString(nameClaim),
                jwt.getClaimAsString(USERNAME_CLAIM), jwt.getSubject()}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private List<String> rolesOf(Jwt jwt) {
        if (jwt.hasClaim(rolesClaim)) {
            List<String> roles = jwt.getClaimAsStringList(rolesClaim);
            return roles == null ? List.of() : List.copyOf(roles);
        }
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        Object roles = realmAccess == null ? null : realmAccess.get(REALM_ROLES);
        if (!(roles instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(String::valueOf).toList();
    }
}
