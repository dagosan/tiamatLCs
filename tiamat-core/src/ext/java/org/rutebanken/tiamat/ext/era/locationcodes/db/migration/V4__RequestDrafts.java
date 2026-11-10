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
 * Added for the ERA Location Code Manager, 2026-11-10.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Drafts (LCMS specification, state "Draft"; a requester amends own drafts):
 * a request form saved before it is submitted. A draft is not a request: it
 * has no status, no history and no reference, the Agency never sees it, and
 * it is removed when it is submitted or discarded.
 */
public class V4__RequestDrafts extends BaseJavaMigration {

    public static final String SQL = """
            CREATE TABLE ext_era_lc_draft (
                id          TEXT PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9-]{1,64}$'),
                owner       TEXT NOT NULL CHECK (length(btrim(owner)) > 0),
                document    JSONB NOT NULL,
                created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX idx_ext_era_lc_draft_owner ON ext_era_lc_draft (lower(btrim(owner)));
            """;

    @Override
    public void migrate(Context context) {
        new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true)).execute(SQL);
    }
}
