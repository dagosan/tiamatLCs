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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;

import java.util.ArrayList;
import java.util.List;

/**
 * The master data the register starts with (migration V3); the same lists as
 * the frontend's model/defaultCountries.ts and model/defaultSubsidiaryTypes.ts.
 * Countries: the prefixes the register accepted before V3, with the UIC
 * country code (code list B.9.1) where the country has one. Subsidiary types:
 * the 48 values of the XSD attribute LocationSubsidiaryTypeCode (release
 * 4.0.0) with the requester of TD 103 v4.0, Table 6, as listed in the LCMS
 * specification 5.5; 39 and 40 follow the procedure (RU).
 */
public final class DefaultMasterData {

    private static final String IM = "infrastructureManager";
    private static final String RU = "railwayUndertaking";
    private static final String STATION_MANAGER = "stationManager";
    private static final String TERMINAL_MANAGER = "terminalManager";
    private static final String OWNER = "owner";

    private DefaultMasterData() {
    }

    public static List<ObjectNode> countries() {
        List<ObjectNode> countries = new ArrayList<>();
        countries.add(country("AT", "81", "Austria", true));
        countries.add(country("BE", "88", "Belgium", true));
        countries.add(country("BG", "52", "Bulgaria", true));
        countries.add(country("CZ", "54", "Czechia", true));
        countries.add(country("DE", "80", "Germany", true));
        countries.add(country("DK", "86", "Denmark", true));
        countries.add(country("EE", "26", "Estonia", true));
        countries.add(country("ES", "71", "Spain", true));
        countries.add(country("FI", "10", "Finland", true));
        countries.add(country("FR", "87", "France", true));
        countries.add(country("GR", "73", "Greece", true));
        countries.add(country("HR", "78", "Croatia", true));
        countries.add(country("HU", "55", "Hungary", true));
        countries.add(country("IE", "60", "Ireland", true));
        countries.add(country("IT", "83", "Italy", true));
        countries.add(country("LT", "24", "Lithuania", true));
        countries.add(country("LU", "82", "Luxembourg", true));
        countries.add(country("LV", "25", "Latvia", true));
        countries.add(country("NL", "84", "Netherlands", true));
        countries.add(country("PL", "51", "Poland", true));
        countries.add(country("PT", "94", "Portugal", true));
        countries.add(country("RO", "53", "Romania", true));
        countries.add(country("SE", "74", "Sweden", true));
        countries.add(country("SI", "79", "Slovenia", true));
        countries.add(country("SK", "56", "Slovakia", true));
        countries.add(country("EU", null, "Border points declared jointly (EU)", true));
        countries.add(country("AL", "41", "Albania", false));
        countries.add(country("BA", "49", "Bosnia and Herzegovina", false));
        countries.add(country("BY", "21", "Belarus", false));
        countries.add(country("CH", "85", "Switzerland", false));
        countries.add(country("GB", "70", "United Kingdom", false));
        countries.add(country("LI", null, "Liechtenstein", false));
        countries.add(country("MD", "23", "Moldova", false));
        countries.add(country("ME", "62", "Montenegro", false));
        countries.add(country("MK", "65", "North Macedonia", false));
        countries.add(country("NO", "76", "Norway", false));
        countries.add(country("RS", "72", "Serbia", false));
        countries.add(country("TR", "75", "Türkiye", false));
        countries.add(country("UA", "22", "Ukraine", false));
        return countries;
    }

    public static List<ObjectNode> subsidiaryTypes() {
        List<ObjectNode> types = new ArrayList<>();
        types.add(type("00", "Not defined"));
        types.add(type("01", "Track", IM));
        types.add(retail(type("03", "Border point code", RU)));
        types.add(type("04", "Sorting code", IM, RU));
        types.add(type("05", "Vehicle parking points", IM));
        types.add(type("06", "Public loading places", IM));
        types.add(type("07", "Private loading places", OWNER));
        types.add(type("09", "Depot / maintenance workshop", OWNER));
        types.add(type("10", "Switch/turnout", IM));
        types.add(type("15", "Platform", STATION_MANAGER, IM));
        types.add(type("21", "Building"));
        types.add(type("22", "Level crossing", IM));
        types.add(type("23", "Bridge", IM));
        types.add(type("24", "Tunnel", IM));
        types.add(type("25", "Underpass", STATION_MANAGER, IM));
        types.add(type("27", "Signal", IM));
        types.add(type("28", "Sign and board", IM));
        types.add(type("29", "Phase break", IM));
        types.add(type("33", "Hot spot detector", IM));
        types.add(type("36", "Freight yard", RU));
        types.add(type("37", "Loading point", RU));
        types.add(retail(type("39", "Reservation code", RU)));
        types.add(retail(type("40", "Metastation", RU)));
        types.add(type("41", "Company specific identifier", OWNER));
        types.add(type("42", "DIUM station", RU));
        types.add(type("43", "Passenger cars loading", OWNER));
        types.add(type("45", "Sewage dump", OWNER));
        types.add(type("46", "Refuelling point", OWNER));
        types.add(type("47", "Mains supply", OWNER));
        types.add(type("48", "Water supply", OWNER));
        types.add(type("50", "Indoor cleaning platform", OWNER));
        types.add(type("51", "Car-wash plant", OWNER));
        types.add(type("52", "Short dry-cleaning track", OWNER));
        types.add(type("54", "Sand-filling station", OWNER));
        types.add(type("56", "Signal box", IM));
        types.add(type("57", "Intermodal terminal", OWNER));
        types.add(type("58", "OSJD system based location", RU));
        types.add(type("59", "Train service substitute stop", OWNER));
        types.add(type("60", "Multifunctional rail terminal", TERMINAL_MANAGER, IM));
        types.add(type("61", "Relief facility", IM));
        types.add(type("66", "Location ENEE code"));
        types.add(type("70", "Network border", IM));
        types.add(type("71", "State border", IM));
        types.add(type("72", "Administrative border", IM));
        types.add(type("74", "Operational handover", IM));
        types.add(type("75", "Planning handover", IM));
        types.add(type("76", "Other technical facility", IM));
        types.add(type("99", "Relation to station"));
        return types;
    }

    private static ObjectNode country(String isoCode, String uicCode, String name, boolean euMemberState) {
        ObjectNode country = JsonDocuments.MAPPER.createObjectNode();
        country.put("isoCode", isoCode);
        country.put("uicCode", uicCode);
        country.put("name", name);
        country.put("euMemberState", euMemberState);
        country.put("plcRangeStart", MasterData.FIRST_LOCATION_NUMBER);
        country.put("plcRangeEnd", MasterData.LAST_LOCATION_NUMBER);
        country.putNull("validFrom");
        country.putNull("validTo");
        return country;
    }

    private static ObjectNode type(String code, String name, String... requesterRoles) {
        ObjectNode type = JsonDocuments.MAPPER.createObjectNode();
        type.put("code", code);
        type.put("name", name);
        ArrayNode roles = type.putArray("requesterRoles");
        for (String role : requesterRoles) {
            roles.add(role);
        }
        type.put("retailRelated", false);
        type.putNull("validFrom");
        type.putNull("validTo");
        return type;
    }

    private static ObjectNode retail(ObjectNode type) {
        type.put("retailRelated", true);
        return type;
    }
}
