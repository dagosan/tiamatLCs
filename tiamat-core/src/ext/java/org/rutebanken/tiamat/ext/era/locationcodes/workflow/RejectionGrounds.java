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
 * Added for the ERA Location Code Manager, 2026-10-30.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Annex B of the procedure: the grounds for rejection, each with the
 * provision a rejection must state (8.7). Same as the frontend's
 * model/rejectionGrounds.ts.
 */
public final class RejectionGrounds {

    public static final String INADMISSIBLE = "inadmissible";

    static final Map<String, String> PROVISIONS = Map.ofEntries(
            entry(INADMISSIBLE, "Procedure 7 and 8.3; Art. 74 Reg. (EU) 2016/796"),
            entry("notTelematicsStakeholder", "Art. 3(9) and 9(4) TEL TSI"),
            entry("duplicate", "Procedure 6.2(c) and 8.5; TD 103 4.3"),
            entry("noParentCode", "Procedure 6.2(a); TD 103 4.6.1"),
            entry("notOnNetwork", "Procedure 8.6; Art. 9(6) TEL TSI"),
            entry("outsideScope", "Art. 9(5) TEL TSI"),
            entry("uniquenessNotSecured", "Procedure 10.3"),
            entry("dataQuality", "Annex point 1.5 TEL TSI"),
            entry("dependentCodesActive", "Procedure 6.2(a) and 9"));

    private RejectionGrounds() {
    }

    public static boolean isGround(String ground) {
        return ground != null && PROVISIONS.containsKey(ground);
    }

    public static String provisionOf(String ground) {
        return ground == null ? "" : PROVISIONS.getOrDefault(ground, "");
    }
}
