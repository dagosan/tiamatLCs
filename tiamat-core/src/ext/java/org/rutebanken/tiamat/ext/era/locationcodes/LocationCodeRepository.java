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

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.JsonNode;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQL for the ext_era_lc_* tables (V1__CreateEraLocationCodeTables). Callers hold the transaction. */
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

    /** The stored code, locked until the transaction ends. */
    public Optional<JsonNode> lockCode(String key) {
        return first(jdbc.queryForList(
                "SELECT document::text FROM ext_era_lc_code WHERE key = ? FOR UPDATE", String.class, key));
    }

    public Optional<String> codeDocument(String key) {
        return firstText(jdbc.queryForList(
                "SELECT document::text FROM ext_era_lc_code WHERE key = ?", String.class, key));
    }

    /** Codes as one JSON array, by key; each filter applies when not null. */
    public String codesJson(String country, String parentPrimaryCode, String activeOn) {
        StringBuilder sql = new StringBuilder(
                "SELECT coalesce(jsonb_agg(document ORDER BY key), '[]'::jsonb)::text FROM ext_era_lc_code WHERE true");
        List<Object> arguments = new ArrayList<>();
        if (country != null) {
            sql.append(" AND country = ?");
            arguments.add(country);
        }
        if (parentPrimaryCode != null) {
            sql.append(" AND parent_primary_code = ?");
            arguments.add(parentPrimaryCode);
        }
        if (activeOn != null) {
            sql.append(" AND (closed_from IS NULL OR closed_from > CAST(? AS date))");
            arguments.add(activeOn);
        }
        return jdbc.queryForObject(sql.toString(), String.class, arguments.toArray());
    }

    /** closed_from of a registered code: empty when not registered, "" when not closed. */
    public Optional<String> closedFrom(String key) {
        List<String> rows = jdbc.queryForList(
                "SELECT coalesce(closed_from::text, '') FROM ext_era_lc_code WHERE key = ?", String.class, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insertCode(CodeEntry code, JsonNode document) {
        jdbc.update("INSERT INTO ext_era_lc_code (key, code_type, code, parent_primary_code, country, name, "
                        + "im_organisation_code, subsidiary_type_code, rinf_kind, rinf_uri, valid_from, closed_from, "
                        + "document) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS date), CAST(? AS date), "
                        + "CAST(? AS jsonb))",
                codeArguments(code, document, false));
    }

    public void updateCode(CodeEntry code, JsonNode document) {
        jdbc.update("UPDATE ext_era_lc_code SET code_type = ?, code = ?, parent_primary_code = ?, country = ?, "
                        + "name = ?, im_organisation_code = ?, subsidiary_type_code = ?, rinf_kind = ?, rinf_uri = ?, "
                        + "valid_from = CAST(? AS date), closed_from = CAST(? AS date), document = CAST(? AS jsonb), "
                        + "revision = revision + 1, updated_at = now() WHERE key = ?",
                codeArguments(code, document, true));
    }

    private static Object[] codeArguments(CodeEntry code, JsonNode document, boolean keyLast) {
        List<Object> arguments = new ArrayList<>();
        if (!keyLast) {
            arguments.add(code.key());
        }
        arguments.add(code.codeType());
        arguments.add(code.code());
        arguments.add(blankToNull(code.parentPrimaryCode()));
        arguments.add(code.country());
        arguments.add(textOrEmpty(document, "name"));
        arguments.add(textOrEmpty(document, "imOrganisationCode"));
        arguments.add(textOrEmpty(document, "subsidiaryTypeCode"));
        arguments.add(code.rinfLink() == null ? null : code.rinfLink().kind());
        arguments.add(code.rinfLink() == null ? null : code.rinfLink().uri());
        arguments.add(blankToNull(code.validFrom()));
        arguments.add(blankToNull(code.closedFrom()));
        arguments.add(JsonDocuments.write(document));
        if (keyLast) {
            arguments.add(code.key());
        }
        return arguments.toArray();
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

    private static String textOrEmpty(JsonNode node, String field) {
        String value = JsonDocuments.text(node, field);
        return value == null ? "" : value;
    }
}
