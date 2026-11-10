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
 * Modified 2026-11-10: master data (countries, subsidiary types; V3); a
 * requester's own requests; request drafts (V4).
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

/** SQL for the ext_era_lc_* tables (V1, V2 for code versions, V3 for master data). Callers hold the transaction. */
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

    /** The requests one person submitted, oldest first; names compare without case or outer spaces. */
    public String requestsJsonOf(String submittedBy) {
        return jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(document ORDER BY submitted_at, id), '[]'::jsonb)::text "
                        + "FROM ext_era_lc_request WHERE lower(btrim(document->>'submittedBy')) = lower(btrim(?))",
                String.class, submittedBy);
    }

    /** One person's drafts, last saved first, as a JSON array. */
    public String draftsJson(String owner) {
        return jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(document ORDER BY updated_at DESC, id), '[]'::jsonb)::text "
                        + "FROM ext_era_lc_draft WHERE lower(btrim(owner)) = lower(btrim(?))", String.class, owner);
    }

    public int draftCount(String owner) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ext_era_lc_draft WHERE lower(btrim(owner)) = lower(btrim(?))",
                Integer.class, owner);
        return count == null ? 0 : count;
    }

    /** The stored draft, locked until the transaction ends. */
    public Optional<JsonNode> lockDraft(String id) {
        return first(jdbc.queryForList(
                "SELECT document::text FROM ext_era_lc_draft WHERE id = ? FOR UPDATE", String.class, id));
    }

    /** Adds the draft, or replaces the stored one with the same id. */
    public void saveDraft(String id, String owner, String json) {
        jdbc.update("INSERT INTO ext_era_lc_draft (id, owner, document) VALUES (?, ?, CAST(? AS jsonb)) "
                + "ON CONFLICT (id) DO UPDATE SET document = EXCLUDED.document, updated_at = now()",
                id, owner, json);
    }

    public void deleteDraft(String id) {
        jdbc.update("DELETE FROM ext_era_lc_draft WHERE id = ?", id);
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

    private static final String VALID_ON = " WHERE (valid_from IS NULL OR valid_from <= CAST(? AS date))"
            + " AND (valid_to IS NULL OR valid_to >= CAST(? AS date))";

    /** The countries as a JSON array, by ISO code; with validOn, those valid that day. */
    public String countriesJson(String validOn) {
        return masterDataJson("ext_era_lc_country", "iso_code", validOn);
    }

    /** The subsidiary types as a JSON array, by code; with validOn, those valid that day. */
    public String subsidiaryTypesJson(String validOn) {
        return masterDataJson("ext_era_lc_subsidiary_type", "code", validOn);
    }

    private String masterDataJson(String table, String key, String validOn) {
        String select = "SELECT coalesce(jsonb_agg(document ORDER BY " + key + "), '[]'::jsonb)::text FROM " + table;
        if (validOn == null) {
            return jdbc.queryForObject(select, String.class);
        }
        return jdbc.queryForObject(select + VALID_ON, String.class, validOn, validOn);
    }

    public Optional<JsonNode> country(String isoCode) {
        return first(jdbc.queryForList(
                "SELECT document::text FROM ext_era_lc_country WHERE iso_code = ?", String.class, isoCode));
    }

    public Optional<JsonNode> subsidiaryType(String code) {
        return first(jdbc.queryForList(
                "SELECT document::text FROM ext_era_lc_subsidiary_type WHERE code = ?", String.class, code));
    }

    /** Adds the country, or replaces the stored one with the same ISO code. */
    public void saveCountry(JsonNode country) {
        jdbc.update("INSERT INTO ext_era_lc_country (iso_code, uic_code, name, eu_member_state, plc_range_start, "
                        + "plc_range_end, valid_from, valid_to, document) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS date), CAST(? AS date), CAST(? AS jsonb)) "
                        + "ON CONFLICT (iso_code) DO UPDATE SET uic_code = EXCLUDED.uic_code, name = EXCLUDED.name, "
                        + "eu_member_state = EXCLUDED.eu_member_state, plc_range_start = EXCLUDED.plc_range_start, "
                        + "plc_range_end = EXCLUDED.plc_range_end, valid_from = EXCLUDED.valid_from, "
                        + "valid_to = EXCLUDED.valid_to, document = EXCLUDED.document, "
                        + "revision = ext_era_lc_country.revision + 1, updated_at = now()",
                country.path("isoCode").asText(), blankToNull(country.path("uicCode").asText("")),
                country.path("name").asText(), country.path("euMemberState").asBoolean(),
                country.path("plcRangeStart").asInt(), country.path("plcRangeEnd").asInt(),
                blankToNull(country.path("validFrom").asText("")), blankToNull(country.path("validTo").asText("")),
                JsonDocuments.write(country));
    }

    /** Adds the subsidiary type, or replaces the stored one with the same code. */
    public void saveSubsidiaryType(JsonNode type) {
        jdbc.update("INSERT INTO ext_era_lc_subsidiary_type (code, name, retail_related, valid_from, valid_to, "
                        + "document) VALUES (?, ?, ?, CAST(? AS date), CAST(? AS date), CAST(? AS jsonb)) "
                        + "ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, "
                        + "retail_related = EXCLUDED.retail_related, valid_from = EXCLUDED.valid_from, "
                        + "valid_to = EXCLUDED.valid_to, document = EXCLUDED.document, "
                        + "revision = ext_era_lc_subsidiary_type.revision + 1, updated_at = now()",
                type.path("code").asText(), type.path("name").asText(), type.path("retailRelated").asBoolean(),
                blankToNull(type.path("validFrom").asText("")), blankToNull(type.path("validTo").asText("")),
                JsonDocuments.write(type));
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
