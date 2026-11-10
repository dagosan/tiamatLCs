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

package org.rutebanken.tiamat.ext.era.locationcodes.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterDataTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-10");

    private static JsonNode json(String text) {
        return JsonDocuments.parse(text.replace('\'', '"'));
    }

    private static ObjectNode belgium() {
        return MasterData.country(json("{'isoCode':'BE','uicCode':'88','name':'Belgium','euMemberState':true,"
                + "'plcRangeStart':1,'plcRangeEnd':99999}"));
    }

    @Test
    void theListsTheRegisterStartsWithAreValid() {
        DefaultMasterData.countries().forEach(country ->
                assertEquals(List.of(), MasterData.countryProblems(country), country.path("isoCode").asText()));
        DefaultMasterData.subsidiaryTypes().forEach(type ->
                assertEquals(List.of(), MasterData.subsidiaryTypeProblems(type), type.path("code").asText()));
    }

    @Test
    void theListsHoldEachKeyOnce() {
        Set<String> isoCodes = new HashSet<>();
        Set<String> uicCodes = new HashSet<>();
        DefaultMasterData.countries().forEach(country -> {
            assertTrue(isoCodes.add(country.path("isoCode").asText()), country.path("isoCode").asText());
            if (!country.path("uicCode").isNull()) {
                assertTrue(uicCodes.add(country.path("uicCode").asText()), country.path("uicCode").asText());
            }
        });
        Set<String> typeCodes = new HashSet<>();
        DefaultMasterData.subsidiaryTypes().forEach(type ->
                assertTrue(typeCodes.add(type.path("code").asText()), type.path("code").asText()));
        assertEquals(48, typeCodes.size());
    }

    @Test
    void aCountryKeepsOnlyItsOwnFieldsAndEmptyBecomesNull() {
        ObjectNode country = MasterData.country(json("{'isoCode':' BE ','uicCode':'','name':'Belgium',"
                + "'euMemberState':true,'plcRangeStart':1,'plcRangeEnd':99999,'validFrom':'','extra':1}"));
        assertEquals("BE", country.path("isoCode").asText());
        assertTrue(country.path("uicCode").isNull());
        assertTrue(country.path("validFrom").isNull());
        assertTrue(country.path("extra").isMissingNode());
        assertEquals(List.of(), MasterData.countryProblems(country));
    }

    @Test
    void aCountryWithAWrongFormIsRefusedWithEveryReason() {
        ObjectNode country = MasterData.country(json("{'isoCode':'be','uicCode':'8','name':'',"
                + "'euMemberState':'yes','plcRangeStart':500,'plcRangeEnd':100,'validFrom':'2026-12-01',"
                + "'validTo':'2026-01-01'}"));
        assertEquals(List.of(
                "isoCode must be two capital letters",
                "uicCode must be two digits, or empty",
                "name must have 1 to 100 characters",
                "euMemberState must be true or false",
                "plcRangeStart is after plcRangeEnd",
                "validTo is before validFrom"), MasterData.countryProblems(country));
    }

    @Test
    void theRangeIsInclusive() {
        ObjectNode country = belgium();
        country.put("plcRangeStart", 100);
        country.put("plcRangeEnd", 200);
        assertTrue(MasterData.inRange(country, 100));
        assertTrue(MasterData.inRange(country, 200));
        assertTrue(!MasterData.inRange(country, 99));
        assertTrue(!MasterData.inRange(country, 201));
    }

    @Test
    void validityCountsItsFirstAndLastDay() {
        ObjectNode country = belgium();
        assertTrue(MasterData.validOn(country, TODAY));
        country.put("validFrom", "2026-11-10");
        country.put("validTo", "2026-11-10");
        assertTrue(MasterData.validOn(country, TODAY));
        assertTrue(!MasterData.validOn(country, TODAY.minusDays(1)));
        assertTrue(!MasterData.validOn(country, TODAY.plusDays(1)));
    }

    @Test
    void aSubsidiaryTypeNeedsTwoDigitsAndKnownRequesters() {
        ObjectNode type = MasterData.subsidiaryType(json("{'code':'1','name':'Track',"
                + "'requesterRoles':['infrastructureManager','dispatcher'],'retailRelated':false}"));
        assertEquals(List.of("code must be two digits", "unknown requester role \"dispatcher\""),
                MasterData.subsidiaryTypeProblems(type));
    }
}
