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
 * Added for the ERA location code register, 2026-10-29.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.model;

import java.util.List;
import java.util.Set;

/**
 * The parts of the frontend's documents (abzuKG, src/rinf/locationCodes/model)
 * that the register checks. The full documents are stored as they come.
 */
public final class LocationCodeModel {

    public static final String PRIMARY = "primary";
    public static final String SUBSIDIARY = "subsidiary";
    public static final Set<String> CODE_TYPES = Set.of(PRIMARY, SUBSIDIARY);

    public static final String ASSIGNED = "assigned";
    public static final String AMENDED = "amended";
    public static final String CLOSED = "closed";
    public static final Set<String> CODE_CHANGES = Set.of(ASSIGNED, AMENDED, CLOSED);

    public static final Set<String> OPERATIONS =
            Set.of("newCode", "existingCode", "amendment", "deactivation");

    public static final Set<String> STATUSES = Set.of(
            "submitted", "registered", "informationRequested", "rinfHarmonisation",
            "externalCoordination", "awaitingDecision", "assigned", "published",
            "rejected", "closed", "underReview");

    public static final String OPERATIONAL_POINT = "operationalPoint";
    public static final String SECTION_OF_LINE = "sectionOfLine";
    public static final String ASSET = "asset";

    /** Namespace of the ERA vocabulary; canonical URIs of OPs and SoLs sit under it. */
    public static final String ERA_NAMESPACE = "http://data.europa.eu/949/";

    private LocationCodeModel() {
    }

    /** One line of a code's history. */
    public record HistoryEntry(String at, String requestId, String reference, String change) {
    }

    /** What a code or request points at in RINF. */
    public record RinfLink(String kind, String canonicalId) {

        /** OP or SoL: prefix plus canonical id. Asset: its canonical URI as given. */
        public String uri() {
            if (OPERATIONAL_POINT.equals(kind)) {
                return ERA_NAMESPACE + "operationalPoint/" + canonicalId;
            }
            if (SECTION_OF_LINE.equals(kind)) {
                return ERA_NAMESPACE + "sectionOfLine/" + canonicalId;
            }
            return canonicalId;
        }
    }

    /** A register entry (RegisteredCode in the frontend). */
    public record CodeEntry(
            String key,
            String codeType,
            String code,
            String parentPrimaryCode,
            String country,
            RinfLink rinfLink,
            String validFrom,
            String closedFrom,
            List<HistoryEntry> history) {

        public boolean isPrimary() {
            return PRIMARY.equals(codeType);
        }
    }

    /** A request (LocationCodeRequest in the frontend), as far as the register checks it. */
    public record RequestEntry(
            String id,
            String reference,
            String status,
            String codeType,
            String operation,
            String country,
            String submittedAt,
            String submittedBy,
            int eventCount) {
    }
}
