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
 * Modified 2026-10-30: the test KG answers subsidiaryCodes.
 * Modified 2026-11-02: code versions with CRD's columns.
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.RegisterChecks.ParentState;
import org.rutebanken.tiamat.ext.era.locationcodes.kg.KnowledgeGraph;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeVersion;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RinfLink;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.ASSIGNED;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.CLOSED;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.GEMBLOUX;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.ended;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.plc;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.plcDocument;
import static org.rutebanken.tiamat.ext.era.locationcodes.TestVersions.slc;

class RegisterChecksTest {

    private static final String BELGB = "http://data.europa.eu/949/operationalPoint/BELGB";
    private static final String SE00111_OP = "http://data.europa.eu/949/operationalPoint/SE00111";

    /** A small KG: Gembloux carries BE00438, with SLC NO-S.1-like code "0001" under it. */
    private static final KnowledgeGraph KG = new KnowledgeGraph() {
        @Override
        public boolean elementExists(String uri) {
            return Set.of(BELGB, SE00111_OP).contains(uri);
        }

        @Override
        public boolean primaryCodeExists(String code) {
            return code.equals("BE00438");
        }

        @Override
        public boolean primaryCodeOnElement(String code, String uri) {
            return code.equals("BE00438") && uri.equals(BELGB);
        }

        @Override
        public boolean subsidiaryCodeExists(String parent, String code) {
            return parent.equals("BE00438") && code.equals("0001");
        }

        @Override
        public List<String> subsidiaryCodes(String parent) {
            return parent.equals("BE00438") ? List.of("0001") : List.of();
        }
    };

    private final RegisterChecks checks = new RegisterChecks(KG);

    private static Function<String, Optional<String>> operation(String value) {
        return requestId -> Optional.of(value);
    }

    private static final Function<String, ParentState> NOTHING_REGISTERED = code -> ParentState.NOT_REGISTERED;

    @Test
    void aNewPrimaryCodeOnAKnownOpPasses() {
        CodeVersion code = plc("SE00001", new RinfLink("operationalPoint", "SE00111"), ASSIGNED);
        assertEquals(List.of(), checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void aNewPrimaryCodeNeedsAPosition() {
        CodeVersion code = plc("SE00001", null, ASSIGNED);
        assertEquals(List.of("a new PLC must be positioned on an operational point or a section of line"),
                checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void anUnknownElementIsRefused() {
        CodeVersion code = plc("SE00001", new RinfLink("operationalPoint", "SEXXX"), ASSIGNED);
        assertEquals(List.of("RINF element http://data.europa.eu/949/operationalPoint/SEXXX is not in the Knowledge Graph"),
                checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void aNewCodeAlreadyInTheKgIsRefused() {
        CodeVersion code = plc("BE00438", GEMBLOUX, ASSIGNED);
        assertEquals(List.of("BE00438 is already carried by a primary location in the Knowledge Graph"),
                checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void firstPopulationMayRegisterTheCodeTheSameElementCarries() {
        CodeVersion onGembloux = plc("BE00438", GEMBLOUX, ASSIGNED);
        assertEquals(List.of(), checks.problems(null, onGembloux, operation("existingCode"), NOTHING_REGISTERED));
        CodeVersion elsewhere = plc("BE00438", new RinfLink("operationalPoint", "SE00111"), ASSIGNED);
        assertEquals(List.of("BE00438 is in the Knowledge Graph on another element than SE00111"),
                checks.problems(null, elsewhere, operation("existingCode"), NOTHING_REGISTERED));
    }

    @Test
    void aNewSubsidiaryCodeNeedsALiveParent() {
        CodeVersion underKgParent = slc("BE00438", "0000000002", null, ASSIGNED);
        assertEquals(List.of(), checks.problems(null, underKgParent, operation("newCode"), NOTHING_REGISTERED));
        CodeVersion underUnknown = slc("BE77777", "0000000001", null, ASSIGNED);
        assertEquals(List.of("parent PLC BE77777 is neither in the register nor in the Knowledge Graph"),
                checks.problems(null, underUnknown, operation("newCode"), NOTHING_REGISTERED));
        assertEquals(List.of("parent PLC BE77777 is closed"),
                checks.problems(null, underUnknown, operation("newCode"), code -> ParentState.CLOSED));
        assertEquals(List.of(),
                checks.problems(null, underUnknown, operation("newCode"), code -> ParentState.ACTIVE));
    }

    @Test
    void aNewSubsidiaryCodeAlreadyInTheKgIsRefused() {
        CodeVersion code = slc("BE00438", "0001", null, ASSIGNED);
        assertEquals(List.of("BE00438/0001 is already in the Knowledge Graph"),
                checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void closingACodeKnownOnlyToTheKg() {
        CodeVersion fromKg = JsonDocuments.version(ended(plcDocument("BE00438", null, null), "2026-11-30", CLOSED));
        assertEquals(List.of(), checks.problems(null, fromKg, operation("deactivation"), NOTHING_REGISTERED));
        CodeVersion unknown = JsonDocuments.version(ended(plcDocument("BE00999", null, null), "2026-11-30", CLOSED));
        assertEquals(List.of("BE00999 is neither in the register nor in the Knowledge Graph, so it cannot be closed"),
                checks.problems(null, unknown, operation("deactivation"), NOTHING_REGISTERED));
    }

    @Test
    void laterChangesOnlyCheckANewLink() {
        CodeVersion stored = plc("SE00001", new RinfLink("operationalPoint", "SE00111"), ASSIGNED);
        CodeVersion closed = JsonDocuments.version(ended(plcDocument("SE00001", stored.rinfLink(), ASSIGNED),
                "2026-11-30", CLOSED));
        assertEquals(List.of(), checks.problems(stored, closed, operation("deactivation"), NOTHING_REGISTERED));
        ObjectNode movedDocument = plcDocument("SE00001", new RinfLink("sectionOfLine", "gone"), TestVersions.AMENDED);
        movedDocument.put("version", 2);
        CodeVersion moved = JsonDocuments.version(movedDocument);
        assertEquals(1, checks.problems(null, moved, operation("amendment"), NOTHING_REGISTERED).size());
    }
}
