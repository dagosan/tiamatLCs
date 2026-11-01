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
 * Added for the ERA Location Code Manager, 2026-11-02.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRepository;
import org.rutebanken.tiamat.ext.era.locationcodes.db.LegacyCodeVersions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.List;

/**
 * The register becomes CRD's location table: one row per version of a code,
 * with CRD's columns. Each entry of the old table becomes version 1 of its
 * code (db.LegacyCodeVersions); the old table is kept, renamed, as it was.
 */
public class V2__CodeVersionsLikeCrd extends BaseJavaMigration {

    public static final String SQL = """
            CREATE TABLE ext_era_lc_code_version (
                key                      TEXT NOT NULL,
                version                  INT NOT NULL CHECK (version >= 1),
                code_type                TEXT NOT NULL CHECK (code_type IN ('primary', 'subsidiary')),
                code                     TEXT NOT NULL,
                parent_primary_code      TEXT,
                subsidiary_type_code     TEXT NOT NULL DEFAULT '',
                country_iso_code         TEXT NOT NULL,
                start_validity           DATE,
                end_validity             DATE,
                responsible_im_code      TEXT NOT NULL DEFAULT '',
                location_name            TEXT NOT NULL DEFAULT '',
                location_name_ascii      TEXT NOT NULL DEFAULT '',
                nuts_code                TEXT NOT NULL DEFAULT '',
                container_handling_flag  BOOLEAN NOT NULL,
                handover_point_flag      BOOLEAN NOT NULL,
                freight_possible_flag    BOOLEAN NOT NULL,
                freight_start_validity   DATE,
                freight_end_validity     DATE,
                passenger_possible_flag  BOOLEAN NOT NULL,
                passenger_start_validity DATE,
                passenger_end_validity   DATE,
                latitude                 DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
                longitude                DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
                free_text                TEXT NOT NULL DEFAULT '',
                add_date                 TIMESTAMPTZ NOT NULL,
                mod_date                 TIMESTAMPTZ NOT NULL,
                rinf_kind                TEXT,
                rinf_uri                 TEXT,
                opened_by_request        TEXT,
                ended_by_request         TEXT,
                document                 JSONB NOT NULL,
                revision                 INT NOT NULL DEFAULT 1,
                created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (key, version),
                CONSTRAINT ext_era_lc_code_version_primary_form CHECK (
                    code_type <> 'primary' OR (
                        code ~ '^[A-Z]{2}[0-9]{5}$'
                        AND parent_primary_code IS NULL
                        AND key = code
                        AND country_iso_code = substr(code, 1, 2))),
                CONSTRAINT ext_era_lc_code_version_subsidiary_form CHECK (
                    code_type <> 'subsidiary' OR (
                        code ~ '^[A-Za-z0-9.-]{1,10}$'
                        AND parent_primary_code ~ '^[A-Z]{2}[0-9]{5}$'
                        AND key = parent_primary_code || '/' || code
                        AND country_iso_code = substr(parent_primary_code, 1, 2))),
                CONSTRAINT ext_era_lc_code_version_validity CHECK (
                    end_validity IS NULL OR start_validity IS NULL OR end_validity >= start_validity),
                CONSTRAINT ext_era_lc_code_version_freight CHECK (
                    freight_possible_flag OR (freight_start_validity IS NULL AND freight_end_validity IS NULL)),
                CONSTRAINT ext_era_lc_code_version_passenger CHECK (
                    passenger_possible_flag OR (passenger_start_validity IS NULL AND passenger_end_validity IS NULL)),
                CONSTRAINT ext_era_lc_code_version_position CHECK ((latitude IS NULL) = (longitude IS NULL)),
                CONSTRAINT ext_era_lc_code_version_nuts CHECK (nuts_code ~ '^[A-Z0-9]{0,5}$'),
                CONSTRAINT ext_era_lc_code_version_ended CHECK (ended_by_request IS NULL OR end_validity IS NOT NULL)
            );
            CREATE INDEX idx_ext_era_lc_code_version_country ON ext_era_lc_code_version (country_iso_code);
            CREATE INDEX idx_ext_era_lc_code_version_parent ON ext_era_lc_code_version (parent_primary_code);
            CREATE INDEX idx_ext_era_lc_code_version_rinf_uri ON ext_era_lc_code_version (rinf_uri);
            """;

    @Override
    public void migrate(Context context) {
        Connection connection = context.getConnection();
        run(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
    }

    /** Creates the version table, converts every old entry, and renames the old table. */
    public static void run(JdbcTemplate jdbc) {
        jdbc.execute(SQL);
        LocationCodeRepository repository = new LocationCodeRepository(jdbc);
        List<String> legacy = jdbc.queryForList("SELECT document::text FROM ext_era_lc_code ORDER BY key",
                String.class);
        for (String json : legacy) {
            JsonNode version = LegacyCodeVersions.versionOf(JsonDocuments.parse(json));
            repository.insertVersion(JsonDocuments.version(version), version);
        }
        jdbc.execute("ALTER TABLE ext_era_lc_code RENAME TO ext_era_lc_code_before_v2");
    }
}
