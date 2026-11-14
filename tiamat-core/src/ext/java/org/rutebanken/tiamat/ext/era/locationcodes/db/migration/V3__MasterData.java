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
import org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRepository;
import org.rutebanken.tiamat.ext.era.locationcodes.masterdata.DefaultMasterData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Master data (LCMS specification FR-27, 5.2 and 5.5): the countries for which
 * codes are assigned and the subsidiary location code types become tables the
 * code administrator maintains, instead of lists in the code. They start with
 * the lists the code held (masterdata.DefaultMasterData).
 */
public class V3__MasterData extends BaseJavaMigration {

    public static final String SQL = """
            CREATE TABLE ext_era_lc_country (
                iso_code         TEXT PRIMARY KEY CHECK (iso_code ~ '^[A-Z]{2}$'),
                uic_code         TEXT UNIQUE CHECK (uic_code ~ '^[0-9]{2}$'),
                name             TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
                eu_member_state  BOOLEAN NOT NULL,
                plc_range_start  INT NOT NULL CHECK (plc_range_start BETWEEN 1 AND 99999),
                plc_range_end    INT NOT NULL CHECK (plc_range_end BETWEEN 1 AND 99999),
                valid_from       DATE,
                valid_to         DATE,
                document         JSONB NOT NULL,
                revision         INT NOT NULL DEFAULT 1,
                created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                CONSTRAINT ext_era_lc_country_range CHECK (plc_range_start <= plc_range_end),
                CONSTRAINT ext_era_lc_country_validity CHECK (
                    valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
            );
            CREATE TABLE ext_era_lc_subsidiary_type (
                code             TEXT PRIMARY KEY CHECK (code ~ '^[0-9]{2}$'),
                name             TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
                retail_related   BOOLEAN NOT NULL,
                valid_from       DATE,
                valid_to         DATE,
                document         JSONB NOT NULL,
                revision         INT NOT NULL DEFAULT 1,
                created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                CONSTRAINT ext_era_lc_subsidiary_type_validity CHECK (
                    valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
            );
            """;

    @Override
    public void migrate(Context context) {
        run(new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true)));
    }

    /** Creates the two tables and fills them with the lists the code held. */
    public static void run(JdbcTemplate jdbc) {
        jdbc.execute(SQL);
        LocationCodeRepository repository = new LocationCodeRepository(jdbc);
        DefaultMasterData.countries().forEach(repository::saveCountry);
        DefaultMasterData.subsidiaryTypes().forEach(repository::saveSubsidiaryType);
    }
}
