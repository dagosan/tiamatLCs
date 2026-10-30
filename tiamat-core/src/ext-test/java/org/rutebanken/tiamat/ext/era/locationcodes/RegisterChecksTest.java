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

package org.rutebanken.tiamat.ext.era.locationcodes;

import org.junit.jupiter.api.Test;
import org.rutebanken.tiamat.ext.era.locationcodes.RegisterChecks.ParentState;
import org.rutebanken.tiamat.ext.era.locationcodes.kg.KnowledgeGraph;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.HistoryEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RinfLink;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRulesTest.ASSIGNED;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRulesTest.GEMBLOUX;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRulesTest.plc;
import static org.rutebanken.tiamat.ext.era.locationcodes.LocationCodeRulesTest.slc;

class RegisterChecksTest {

    private static final String BELGB = "http://data.europa.eu/949/operationalPoint/BELGB";
    private static final String SE00111_OP = "http://data.europa.eu/949/operationalPoint/SE00111";
    private static final HistoryEntry CLOSED = new HistoryEntry("2026-11-01T00:00:00Z", "r5", null, "closed");

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
    };

    private final RegisterChecks checks = new RegisterChecks(KG);

    private static Function<String, Optional<String>> operation(String value) {
        return requestId -> Optional.of(value);
    }

    private static final Function<String, ParentState> NOTHING_REGISTERED = code -> ParentState.NOT_REGISTERED;

    @Test
    void aNewPrimaryCodeOnAKnownOpPasses() {
        CodeEntry code = plc("SE00001", new RinfLink("operationalPoint", "SE00111"), List.of(ASSIGNED));
        assertEquals(List.of(), checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void aNewPrimaryCodeNeedsAPosition() {
        CodeEntry code = plc("SE00001", null, List.of(ASSIGNED));
        assertEquals(List.of("a new PLC must be positioned on an operational point or a section of line"),
                checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void anUnknownElementIsRefused() {
        CodeEntry code = plc("SE00001", new RinfLink("operationalPoint", "SEXXX"), List.of(ASSIGNED));
        assertEquals(List.of("RINF element http://data.europa.eu/949/operationalPoint/SEXXX is not in the Knowledge Graph"),
                checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void aNewCodeAlreadyInTheKgIsRefused() {
        CodeEntry code = plc("BE00438", GEMBLOUX, List.of(ASSIGNED));
        assertEquals(List.of("BE00438 is already carried by a primary location in the Knowledge Graph"),
                checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void firstPopulationMayRegisterTheCodeTheSameElementCarries() {
        CodeEntry onGembloux = plc("BE00438", GEMBLOUX, List.of(ASSIGNED));
        assertEquals(List.of(), checks.problems(null, onGembloux, operation("existingCode"), NOTHING_REGISTERED));
        CodeEntry elsewhere = plc("BE00438", new RinfLink("operationalPoint", "SE00111"), List.of(ASSIGNED));
        assertEquals(List.of("BE00438 is in the Knowledge Graph on another element than SE00111"),
                checks.problems(null, elsewhere, operation("existingCode"), NOTHING_REGISTERED));
    }

    @Test
    void aNewSubsidiaryCodeNeedsALiveParent() {
        CodeEntry underKgParent = slc("BE00438", "0000000002", null, List.of(ASSIGNED));
        assertEquals(List.of(), checks.problems(null, underKgParent, operation("newCode"), NOTHING_REGISTERED));
        CodeEntry underUnknown = slc("BE77777", "0000000001", null, List.of(ASSIGNED));
        assertEquals(List.of("parent PLC BE77777 is neither in the register nor in the Knowledge Graph"),
                checks.problems(null, underUnknown, operation("newCode"), NOTHING_REGISTERED));
        assertEquals(List.of("parent PLC BE77777 is closed"),
                checks.problems(null, underUnknown, operation("newCode"), code -> ParentState.CLOSED));
        assertEquals(List.of(),
                checks.problems(null, underUnknown, operation("newCode"), code -> ParentState.ACTIVE));
    }

    @Test
    void aNewSubsidiaryCodeAlreadyInTheKgIsRefused() {
        CodeEntry code = slc("BE00438", "0001", null, List.of(ASSIGNED));
        assertEquals(List.of("BE00438/0001 is already in the Knowledge Graph"),
                checks.problems(null, code, operation("newCode"), NOTHING_REGISTERED));
    }

    @Test
    void closingACodeKnownOnlyToTheKg() {
        assertEquals(List.of(), checks.problems(null, plc("BE00438", null, List.of(CLOSED)),
                operation("deactivation"), NOTHING_REGISTERED));
        assertEquals(List.of("BE00999 is neither in the register nor in the Knowledge Graph, so it cannot be closed"),
                checks.problems(null, plc("BE00999", null, List.of(CLOSED)),
                        operation("deactivation"), NOTHING_REGISTERED));
    }

    @Test
    void laterChangesOnlyCheckANewLink() {
        CodeEntry stored = plc("SE00001", new RinfLink("operationalPoint", "SE00111"), List.of(ASSIGNED));
        CodeEntry closed = plc("SE00001", stored.rinfLink(), List.of(ASSIGNED, CLOSED));
        assertEquals(List.of(), checks.problems(stored, closed, operation("deactivation"), NOTHING_REGISTERED));
        CodeEntry moved = plc("SE00001", new RinfLink("sectionOfLine", "gone"), List.of(ASSIGNED, CLOSED));
        assertEquals(1, checks.problems(stored, moved, operation("amendment"), NOTHING_REGISTERED).size());
    }
}
