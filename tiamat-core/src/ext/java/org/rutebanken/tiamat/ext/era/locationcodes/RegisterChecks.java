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
 * Modified 2026-10-30: subsidiaryCodesInKg, for closing a PLC with its SLCs.
 * Modified 2026-11-02: code versions with CRD's columns.
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import org.rutebanken.tiamat.ext.era.locationcodes.kg.KnowledgeGraph;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeVersion;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.VersionChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.ASSIGNED;

/**
 * Checks of a code against the Knowledge Graph and the register, made by the
 * backend itself whatever the frontend already checked:
 * <ul>
 *   <li>a new PLC is positioned on an OP or section of line (Art. 9(6) TEL TSI, procedure 8.6);</li>
 *   <li>the RINF element a code links to exists in the KG;</li>
 *   <li>a new code is not already carried by another location in the KG
 *       (first population, "existingCode", may register a code the same element already carries);</li>
 *   <li>a new SLC has a parent PLC that is in force;</li>
 *   <li>a change to a code the register does not hold yet concerns a code the KG has.</li>
 * </ul>
 */
public class RegisterChecks {

    /** What the register knows of a parent PLC. */
    public enum ParentState { ACTIVE, CLOSED, NOT_REGISTERED }

    private static final String EXISTING_CODE = "existingCode";

    private final KnowledgeGraph kg;

    public RegisterChecks(KnowledgeGraph kg) {
        this.kg = kg;
    }

    /** SLC values the KG links to a PLC, for closing the PLC with its dependants. */
    public List<String> subsidiaryCodesInKg(String primaryCode) {
        return kg.subsidiaryCodes(primaryCode);
    }

    /**
     * A new version 1 is a new code (opened by an assignment) or a code the
     * register knew only from the KG (opened by no request); later versions
     * follow one the register holds, so only their RINF link is checked.
     *
     * @param stored            the stored version, or null for a version not saved yet
     * @param operationOfRequest the operation (newCode, existingCode, …) of a request, by id
     * @param parentState       what the register knows of a PLC, by code
     */
    public List<String> problems(
            CodeVersion stored,
            CodeVersion incoming,
            Function<String, Optional<String>> operationOfRequest,
            Function<String, ParentState> parentState) {
        List<String> problems = new ArrayList<>();
        boolean linkChanged = incoming.rinfLink() != null
                && (stored == null || !Objects.equals(stored.rinfLink(), incoming.rinfLink()));
        if (linkChanged && !kg.elementExists(incoming.rinfLink().uri())) {
            problems.add("RINF element " + incoming.rinfLink().uri() + " is not in the Knowledge Graph");
        }
        if (stored != null || incoming.version() > 1) {
            return problems;
        }
        VersionChange opened = incoming.openedBy();
        if (opened != null && ASSIGNED.equals(opened.change())) {
            String operation = operationOfRequest.apply(opened.requestId()).orElse("newCode");
            problems.addAll(incoming.isPrimary()
                    ? newPrimaryProblems(incoming, EXISTING_CODE.equals(operation))
                    : newSubsidiaryProblems(incoming, EXISTING_CODE.equals(operation), parentState));
        } else if (!knownToKg(incoming)) {
            problems.add(incoming.key() + " is neither in the register nor in the Knowledge Graph, so it cannot be "
                    + (incoming.endedBy() == null ? "changed" : incoming.endedBy().change()));
        }
        return problems;
    }

    private List<String> newPrimaryProblems(CodeVersion code, boolean existingCode) {
        List<String> problems = new ArrayList<>();
        if (code.rinfLink() == null) {
            problems.add("a new PLC must be positioned on an operational point or a section of line");
            return problems;
        }
        if (!kg.primaryCodeExists(code.code())) {
            return problems;
        }
        if (!existingCode) {
            problems.add(code.code() + " is already carried by a primary location in the Knowledge Graph");
        } else if (!kg.primaryCodeOnElement(code.code(), code.rinfLink().uri())) {
            problems.add(code.code() + " is in the Knowledge Graph on another element than "
                    + code.rinfLink().canonicalId());
        }
        return problems;
    }

    private List<String> newSubsidiaryProblems(
            CodeVersion code, boolean existingCode, Function<String, ParentState> parentState) {
        List<String> problems = new ArrayList<>();
        String parent = code.parentPrimaryCode();
        ParentState state = parentState.apply(parent);
        if (state == ParentState.CLOSED) {
            problems.add("parent PLC " + parent + " is closed");
        } else if (state == ParentState.NOT_REGISTERED && !kg.primaryCodeExists(parent)) {
            problems.add("parent PLC " + parent + " is neither in the register nor in the Knowledge Graph");
        }
        if (!existingCode && kg.subsidiaryCodeExists(parent, code.code())) {
            problems.add(code.key() + " is already in the Knowledge Graph");
        }
        return problems;
    }

    private boolean knownToKg(CodeVersion code) {
        return code.isPrimary()
                ? kg.primaryCodeExists(code.code())
                : kg.subsidiaryCodeExists(code.parentPrimaryCode(), code.code());
    }
}
