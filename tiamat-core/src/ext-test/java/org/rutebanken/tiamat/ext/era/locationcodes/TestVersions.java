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

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeVersion;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RinfLink;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.VersionChange;

/** Code versions for tests: valid documents, changed field by field. */
public final class TestVersions {

    public static final VersionChange ASSIGNED =
            new VersionChange("2026-10-29T10:00:00.000Z", "r1", "LC-2026-0001", "assigned");
    public static final VersionChange AMENDED =
            new VersionChange("2026-11-02T10:00:00.000Z", "r2", "LC-2026-0002", "amended");
    public static final VersionChange CLOSED =
            new VersionChange("2026-11-01T00:00:00.000Z", "r5", null, "closed");
    public static final RinfLink GEMBLOUX = new RinfLink("operationalPoint", "BELGB");

    private TestVersions() {
    }

    /** Version 1 of a PLC, valid from 2026-10-29, opened by `opened` (null: known from the KG). */
    public static ObjectNode plcDocument(String code, RinfLink link, VersionChange opened) {
        ObjectNode version = base(code, code, null, code.substring(0, 2), link, opened);
        version.put("codeType", "primary");
        return version;
    }

    public static ObjectNode slcDocument(String parent, String code, RinfLink link, VersionChange opened) {
        ObjectNode version = base(parent + "/" + code, code, parent, parent.substring(0, 2), link, opened);
        version.put("codeType", "subsidiary");
        return version;
    }

    public static CodeVersion plc(String code, RinfLink link, VersionChange opened) {
        return JsonDocuments.version(plcDocument(code, link, opened));
    }

    public static CodeVersion slc(String parent, String code, RinfLink link, VersionChange opened) {
        return JsonDocuments.version(slcDocument(parent, code, link, opened));
    }

    /** The version as a request ending it on `lastDay` leaves it. */
    public static ObjectNode ended(ObjectNode version, String lastDay, VersionChange change) {
        ObjectNode copy = version.deepCopy();
        copy.put("endValidity", lastDay);
        copy.set("endedBy", JsonDocuments.MAPPER.valueToTree(change));
        copy.put("modDate", change.at());
        return copy;
    }

    private static ObjectNode base(String key, String code, String parent, String country, RinfLink link,
            VersionChange opened) {
        ObjectNode version = JsonDocuments.MAPPER.createObjectNode();
        version.put("key", key);
        version.put("version", 1);
        version.put("code", code);
        version.put("parentPrimaryCode", parent);
        version.put("subsidiaryTypeCode", "");
        version.put("countryIsoCode", country);
        if (opened == null) {
            version.putNull("startValidity");
        } else {
            version.put("startValidity", "2026-10-29");
        }
        version.putNull("endValidity");
        version.put("responsibleImCode", "0088");
        version.put("locationName", "Gembloux");
        version.put("locationNameAscii", "Gembloux");
        version.put("nutsCode", "BE352");
        version.put("containerHandlingFlag", false);
        version.put("handoverPointFlag", false);
        version.put("freightPossibleFlag", false);
        version.putNull("freightStartValidity");
        version.putNull("freightEndValidity");
        version.put("passengerPossibleFlag", true);
        version.put("passengerStartValidity", "2026-10-29");
        version.putNull("passengerEndValidity");
        version.put("latitude", 50.571292);
        version.put("longitude", 4.690778);
        version.put("freeText", "");
        version.put("addDate", "2026-10-29T10:00:00.000Z");
        version.put("modDate", "2026-10-29T10:00:00.000Z");
        version.set("rinfLink", link == null ? JsonDocuments.MAPPER.nullNode() : JsonDocuments.MAPPER.valueToTree(link));
        version.set("openedBy", opened == null ? JsonDocuments.MAPPER.nullNode()
                : JsonDocuments.MAPPER.valueToTree(opened));
        version.putNull("endedBy");
        return version;
    }
}
