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
 */

package org.rutebanken.tiamat.ext.era.locationcodes.db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * Tables of the location code register. Each request and each code keeps the
 * whole document the frontend works with (JSONB), plus the columns needed to
 * search it and the checks the database itself can enforce.
 */
public class V1__CreateEraLocationCodeTables extends BaseJavaMigration {

    public static final String SQL = """
            CREATE TABLE ext_era_lc_request (
                id            TEXT PRIMARY KEY,
                reference     TEXT UNIQUE,
                status        TEXT NOT NULL,
                code_type     TEXT NOT NULL CHECK (code_type IN ('primary', 'subsidiary')),
                operation     TEXT NOT NULL,
                country       TEXT NOT NULL,
                submitted_at  TIMESTAMPTZ NOT NULL,
                document      JSONB NOT NULL,
                revision      INT NOT NULL DEFAULT 1,
                created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX idx_ext_era_lc_request_status ON ext_era_lc_request (status);

            CREATE TABLE ext_era_lc_code (
                key                  TEXT PRIMARY KEY,
                code_type            TEXT NOT NULL CHECK (code_type IN ('primary', 'subsidiary')),
                code                 TEXT NOT NULL,
                parent_primary_code  TEXT,
                country              TEXT NOT NULL,
                name                 TEXT NOT NULL DEFAULT '',
                im_organisation_code TEXT NOT NULL DEFAULT '',
                subsidiary_type_code TEXT NOT NULL DEFAULT '',
                rinf_kind            TEXT,
                rinf_uri             TEXT,
                valid_from           DATE,
                closed_from          DATE,
                document             JSONB NOT NULL,
                revision             INT NOT NULL DEFAULT 1,
                created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
                CONSTRAINT ext_era_lc_code_primary_form CHECK (
                    code_type <> 'primary' OR (
                        code ~ '^[A-Z]{2}[0-9]{5}$'
                        AND parent_primary_code IS NULL
                        AND key = code
                        AND country = substr(code, 1, 2))),
                CONSTRAINT ext_era_lc_code_subsidiary_form CHECK (
                    code_type <> 'subsidiary' OR (
                        code ~ '^[A-Za-z0-9.-]{1,10}$'
                        AND parent_primary_code ~ '^[A-Z]{2}[0-9]{5}$'
                        AND key = parent_primary_code || '/' || code
                        AND country = substr(parent_primary_code, 1, 2)))
            );
            CREATE INDEX idx_ext_era_lc_code_country ON ext_era_lc_code (country);
            CREATE INDEX idx_ext_era_lc_code_parent ON ext_era_lc_code (parent_primary_code);
            CREATE INDEX idx_ext_era_lc_code_rinf_uri ON ext_era_lc_code (rinf_uri);

            CREATE TABLE ext_era_lc_reference_sequence (
                year       INT PRIMARY KEY,
                last_value INT NOT NULL CHECK (last_value >= 0)
            );

            -- Every write, in order: who saved what, and the document as saved.
            CREATE TABLE ext_era_lc_change_log (
                id         BIGSERIAL PRIMARY KEY,
                at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                entity     TEXT NOT NULL CHECK (entity IN ('request', 'code')),
                entity_key TEXT NOT NULL,
                operation  TEXT NOT NULL CHECK (operation IN ('create', 'update', 'import')),
                principal  TEXT,
                document   JSONB NOT NULL
            );
            CREATE INDEX idx_ext_era_lc_change_log_entity ON ext_era_lc_change_log (entity, entity_key);
            """;

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(SQL);
        }
    }
}
