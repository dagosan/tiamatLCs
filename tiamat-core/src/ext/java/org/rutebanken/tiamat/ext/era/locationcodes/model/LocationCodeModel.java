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
 * Added for the ERA Location Code Manager, 2026-10-29.
 * Modified 2026-11-02: code versions with CRD's columns.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.model;

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
    /** A version is opened by an assignment (version 1) or an amendment, ended by an amendment or a closure. */
    public static final Set<String> OPENING_CHANGES = Set.of(ASSIGNED, AMENDED);
    public static final Set<String> ENDING_CHANGES = Set.of(AMENDED, CLOSED);

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

    /** The request that opened or ended a code version. */
    public record VersionChange(String at, String requestId, String reference, String change) {
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

    /**
     * One version of a location code: one row of CRD's location table (its
     * columns in camelCase) plus what the workflow keeps; the frontend's
     * model/CodeVersion.ts. Flags are null when the document lacks them,
     * coordinates when not given. Changed 2026-11-02 (was one entry per code).
     */
    public record CodeVersion(
            String key,
            int version,
            String codeType,
            String code,
            String parentPrimaryCode,
            String subsidiaryTypeCode,
            String countryIsoCode,
            String startValidity,
            String endValidity,
            String responsibleImCode,
            String locationName,
            String locationNameAscii,
            String nutsCode,
            Boolean containerHandlingFlag,
            Boolean handoverPointFlag,
            Boolean freightPossibleFlag,
            String freightStartValidity,
            String freightEndValidity,
            Boolean passengerPossibleFlag,
            String passengerStartValidity,
            String passengerEndValidity,
            Double latitude,
            Double longitude,
            String freeText,
            String addDate,
            String modDate,
            RinfLink rinfLink,
            VersionChange openedBy,
            VersionChange endedBy) {

        public boolean isPrimary() {
            return PRIMARY.equals(codeType);
        }
    }

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
