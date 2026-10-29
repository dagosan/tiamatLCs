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

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.rutebanken.tiamat.ext.era.locationcodes.api.LocationCodeController;
import org.rutebanken.tiamat.ext.era.locationcodes.db.migration.V1__CreateEraLocationCodeTables;
import org.rutebanken.tiamat.ext.era.locationcodes.kg.SparqlKnowledgeGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

/**
 * Turns the location code register on with the Spring profile
 * "era-location-codes". Its tables have their own Flyway history
 * (ext_era_schema_version), like the Fintraffic extension, so they never mix
 * with Tiamat's migrations and upstream releases merge without conflicts.
 */
@Profile(LocationCodeController.PROFILE)
@Configuration
public class EraLocationCodesConfig {

    private static final Logger logger = LoggerFactory.getLogger(EraLocationCodesConfig.class);

    private static final List<Class<? extends JavaMigration>> MIGRATIONS = List.of(
            V1__CreateEraLocationCodeTables.class
    );

    private final DataSource dataSource;

    public EraLocationCodesConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        logger.info("Migrating the ERA location code tables ({} migrations)", MIGRATIONS.size());
        Flyway.configure()
                .dataSource(dataSource)
                .table("ext_era_schema_version")
                .sqlMigrationPrefix("ext_era_")
                .javaMigrationClassProvider(() -> MIGRATIONS)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    @Bean
    public LocationCodeService eraLocationCodeService(
            PlatformTransactionManager transactionManager,
            @Value("${era.location-codes.kg.checks-enabled:true}") boolean kgChecksEnabled,
            @Value("${era.location-codes.kg.endpoint:https://graph.data.era.europa.eu/repositories/rinf-plus}")
            String kgEndpoint,
            @Value("${era.location-codes.kg.timeout-seconds:20}") long kgTimeoutSeconds) {
        RegisterChecks checks = null;
        if (kgChecksEnabled) {
            logger.info("Location codes are checked against the Knowledge Graph at {}", kgEndpoint);
            checks = new RegisterChecks(new SparqlKnowledgeGraph(kgEndpoint, Duration.ofSeconds(kgTimeoutSeconds)));
        } else {
            logger.warn("Knowledge Graph checks of location codes are OFF (era.location-codes.kg.checks-enabled)");
        }
        return new LocationCodeService(
                new LocationCodeRepository(new JdbcTemplate(dataSource)),
                new TransactionTemplate(transactionManager),
                checks);
    }
}
