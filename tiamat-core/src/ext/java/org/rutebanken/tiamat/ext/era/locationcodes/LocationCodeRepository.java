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

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.JsonNode;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeVersion;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQL for the ext_era_lc_* tables (V1, and V2 for code versions). Callers hold the transaction. */
public class LocationCodeRepository {

    private final JdbcTemplate jdbc;

    public LocationCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored request, locked until the transaction ends. */
    public Optional<JsonNode> lockRequest(String id) {
        return first(jdbc.queryForList(
                "SELECT document::text FROM ext_era_lc_request WHERE id = ? FOR UPDATE", String.class, id));
    }

    public Optional<String> requestDocument(String id) {
        return firstText(jdbc.queryForList(
                "SELECT document::text FROM ext_era_lc_request WHERE id = ?", String.class, id));
    }

    public Optional<String> requestOperation(String id) {
        return firstText(jdbc.queryForList(
                "SELECT operation FROM ext_era_lc_request WHERE id = ?", String.class, id));
    }

    /** All requests as one JSON array, oldest first. */
    public String requestsJson() {
        return jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(document ORDER BY submitted_at, id), '[]'::jsonb)::text "
                        + "FROM ext_era_lc_request", String.class);
    }

    public void insertRequest(RequestEntry request, String json) {
        jdbc.update("INSERT INTO ext_era_lc_request "
                        + "(id, reference, status, code_type, operation, country, submitted_at, document) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS jsonb))",
                request.id(), request.reference(), request.status(), request.codeType(), request.operation(),
                request.country(), request.submittedAt(), json);
    }

    public void updateRequest(RequestEntry request, String json) {
        jdbc.update("UPDATE ext_era_lc_request SET reference = ?, status = ?, code_type = ?, operation = ?, "
                        + "country = ?, document = CAST(? AS jsonb), revision = revision + 1, updated_at = now() "
                        + "WHERE id = ?",
                request.reference(), request.status(), request.codeType(), request.operation(),
                request.country(), json, request.id());
    }

    /** Columns of ext_era_lc_code_version (V2) filled from a version, in this order. */
    private static final List<String> VERSION_COLUMNS = List.of(
            "code_type", "code", "parent_primary_code", "subsidiary_type_code", "country_iso_code",
            "start_validity", "end_validity", "responsible_im_code", "location_name", "location_name_ascii",
            "nuts_code", "container_handling_flag", "handover_point_flag", "freight_possible_flag",
            "freight_start_validity", "freight_end_validity", "passenger_possible_flag",
            "passenger_start_validity", "passenger_end_validity", "latitude", "longitude", "free_text",
            "add_date", "mod_date", "rinf_kind", "rinf_uri", "opened_by_request", "ended_by_request", "document");
    /** The SQL placeholder of each column: dates, timestamps and JSON are cast. */
    private static final Map<String, String> CASTS = Map.ofEntries(
            Map.entry("start_validity", "CAST(? AS date)"),
            Map.entry("end_validity", "CAST(? AS date)"),
            Map.entry("freight_start_validity", "CAST(? AS date)"),
            Map.entry("freight_end_validity", "CAST(? AS date)"),
            Map.entry("passenger_start_validity", "CAST(? AS date)"),
            Map.entry("passenger_end_validity", "CAST(? AS date)"),
            Map.entry("add_date", "CAST(? AS timestamptz)"),
            Map.entry("mod_date", "CAST(? AS timestamptz)"),
            Map.entry("document", "CAST(? AS jsonb)"));
    private static final String VERSIONS = "ext_era_lc_code_version";

    /** One stored version, locked until the transaction ends. */
    public Optional<JsonNode> lockVersion(String key, int version) {
        return first(jdbc.queryForList("SELECT document::text FROM " + VERSIONS
                + " WHERE key = ? AND version = ? FOR UPDATE", String.class, key, version));
    }

    /** The latest version of a code, locked until the transaction ends. */
    public Optional<JsonNode> lockCurrentVersion(String key) {
        return first(jdbc.queryForList("SELECT document::text FROM " + VERSIONS
                + " WHERE key = ? ORDER BY version DESC LIMIT 1 FOR UPDATE", String.class, key));
    }

    /** Whether the register holds any version of the code. */
    public boolean hasCode(String key) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + VERSIONS + " WHERE key = ?",
                Integer.class, key);
        return count != null && count > 0;
    }

    /** All versions of one code, oldest first, as a JSON array; empty when unknown. */
    public Optional<String> versionsJson(String key) {
        String json = jdbc.queryForObject("SELECT coalesce(jsonb_agg(document ORDER BY version), '[]'::jsonb)::text "
                + "FROM " + VERSIONS + " WHERE key = ?", String.class, key);
        return "[]".equals(json) ? Optional.empty() : Optional.ofNullable(json);
    }

    /**
     * Versions as one JSON array, by key and version; each filter applies when
     * not null. activeOn keeps the versions valid that day (CRD's Active_Flag).
     */
    public String codesJson(String country, String parentPrimaryCode, String activeOn) {
        StringBuilder sql = new StringBuilder("SELECT coalesce(jsonb_agg(document ORDER BY key, version), "
                + "'[]'::jsonb)::text FROM " + VERSIONS + " WHERE true");
        List<Object> arguments = new ArrayList<>();
        if (country != null) {
            sql.append(" AND country_iso_code = ?");
            arguments.add(country);
        }
        if (parentPrimaryCode != null) {
            sql.append(" AND parent_primary_code = ?");
            arguments.add(parentPrimaryCode);
        }
        if (activeOn != null) {
            sql.append(" AND (start_validity IS NULL OR start_validity <= CAST(? AS date))"
                    + " AND (end_validity IS NULL OR end_validity >= CAST(? AS date))");
            arguments.add(activeOn);
            arguments.add(activeOn);
        }
        return jdbc.queryForObject(sql.toString(), String.class, arguments.toArray());
    }

    /** The latest version of every SLC under a PLC, as a JSON array. */
    public String currentSubsidiariesJson(String parentPrimaryCode) {
        return jdbc.queryForObject("SELECT coalesce(jsonb_agg(document ORDER BY key), '[]'::jsonb)::text FROM "
                + "(SELECT DISTINCT ON (key) key, document FROM " + VERSIONS + " WHERE parent_primary_code = ? "
                + "ORDER BY key, version DESC) latest", String.class, parentPrimaryCode);
    }

    /** The last day of a code's latest version: empty when not registered, "" when open. */
    public Optional<String> currentEndValidity(String key) {
        List<String> rows = jdbc.queryForList("SELECT coalesce(end_validity::text, '') FROM " + VERSIONS
                + " WHERE key = ? ORDER BY version DESC LIMIT 1", String.class, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insertVersion(CodeVersion version, JsonNode document) {
        List<String> columns = new ArrayList<>(List.of("key", "version"));
        columns.addAll(VERSION_COLUMNS);
        String placeholders = String.join(", ", columns.stream().map(column -> CASTS.getOrDefault(column, "?"))
                .toList());
        List<Object> arguments = new ArrayList<>(List.of(version.key(), version.version()));
        arguments.addAll(versionValues(version, document));
        jdbc.update("INSERT INTO " + VERSIONS + " (" + String.join(", ", columns) + ") VALUES (" + placeholders
                + ")", arguments.toArray());
    }

    public void updateVersion(CodeVersion version, JsonNode document) {
        String assignments = String.join(", ", VERSION_COLUMNS.stream()
                .map(column -> column + " = " + CASTS.getOrDefault(column, "?")).toList());
        List<Object> arguments = new ArrayList<>(versionValues(version, document));
        arguments.add(version.key());
        arguments.add(version.version());
        jdbc.update("UPDATE " + VERSIONS + " SET " + assignments + ", revision = revision + 1, updated_at = now() "
                + "WHERE key = ? AND version = ?", arguments.toArray());
    }

    private static List<Object> versionValues(CodeVersion version, JsonNode document) {
        List<Object> values = new ArrayList<>();
        values.add(version.codeType());
        values.add(version.code());
        values.add(blankToNull(version.parentPrimaryCode()));
        values.add(orEmpty(version.subsidiaryTypeCode()));
        values.add(version.countryIsoCode());
        values.add(blankToNull(version.startValidity()));
        values.add(blankToNull(version.endValidity()));
        values.add(orEmpty(version.responsibleImCode()));
        values.add(orEmpty(version.locationName()));
        values.add(orEmpty(version.locationNameAscii()));
        values.add(orEmpty(version.nutsCode()));
        values.add(version.containerHandlingFlag());
        values.add(version.handoverPointFlag());
        values.add(version.freightPossibleFlag());
        values.add(blankToNull(version.freightStartValidity()));
        values.add(blankToNull(version.freightEndValidity()));
        values.add(version.passengerPossibleFlag());
        values.add(blankToNull(version.passengerStartValidity()));
        values.add(blankToNull(version.passengerEndValidity()));
        values.add(version.latitude());
        values.add(version.longitude());
        values.add(orEmpty(version.freeText()));
        values.add(version.addDate());
        values.add(version.modDate());
        values.add(version.rinfLink() == null ? null : version.rinfLink().kind());
        values.add(version.rinfLink() == null ? null : version.rinfLink().uri());
        values.add(version.openedBy() == null ? null : version.openedBy().requestId());
        values.add(version.endedBy() == null ? null : version.endedBy().requestId());
        values.add(JsonDocuments.write(document));
        return values;
    }

    /** The next reference number of a year, starting at 1; atomic across instances. */
    public int nextReferenceSequence(int year) {
        Integer value = jdbc.queryForObject("INSERT INTO ext_era_lc_reference_sequence (year, last_value) "
                + "VALUES (?, 1) ON CONFLICT (year) DO UPDATE "
                + "SET last_value = ext_era_lc_reference_sequence.last_value + 1 RETURNING last_value",
                Integer.class, year);
        return value == null ? 1 : value;
    }

    /** Raises a year's counter to at least `value` (restoring a backup). */
    public void raiseReferenceSequence(int year, int value) {
        jdbc.update("INSERT INTO ext_era_lc_reference_sequence (year, last_value) VALUES (?, ?) "
                + "ON CONFLICT (year) DO UPDATE SET last_value = "
                + "GREATEST(ext_era_lc_reference_sequence.last_value, EXCLUDED.last_value)", year, value);
    }

    /** The counters as a JSON object, {"2026": 12}. */
    public String sequencesJson() {
        return jdbc.queryForObject("SELECT coalesce(jsonb_object_agg(year::text, last_value), '{}'::jsonb)::text "
                + "FROM ext_era_lc_reference_sequence", String.class);
    }

    public void log(String entity, String key, String operation, String principal, String json) {
        jdbc.update("INSERT INTO ext_era_lc_change_log (entity, entity_key, operation, principal, document) "
                + "VALUES (?, ?, ?, ?, CAST(? AS jsonb))", entity, key, operation, principal, json);
    }

    /** A code's or request's saved versions, oldest first, as a JSON array. */
    public String changeLogJson(String entity, String key) {
        return jdbc.queryForObject("SELECT coalesce(jsonb_agg(jsonb_build_object('at', at, 'operation', operation, "
                + "'principal', principal, 'document', document) ORDER BY id), '[]'::jsonb)::text "
                + "FROM ext_era_lc_change_log WHERE entity = ? AND entity_key = ?", String.class, entity, key);
    }

    private static Optional<JsonNode> first(List<String> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(JsonDocuments.parse(rows.get(0)));
    }

    private static Optional<String> firstText(List<String> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
