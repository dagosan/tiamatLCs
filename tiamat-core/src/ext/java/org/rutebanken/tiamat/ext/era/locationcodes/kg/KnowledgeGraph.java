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

package org.rutebanken.tiamat.ext.era.locationcodes.kg;

/**
 * The questions the register asks ERA's Knowledge Graph before it accepts a
 * code. Every method throws {@link KnowledgeGraphUnavailableException} when
 * the KG cannot answer, so a check never passes by default.
 */
public interface KnowledgeGraph {

    /** Some element has this canonical URI (era:canonicalURI). */
    boolean elementExists(String canonicalUri);

    /** Some primary location carries this code (era:primaryLocationCode). */
    boolean primaryCodeExists(String primaryCode);

    /** The element with this canonical URI carries this PLC through its primary location. */
    boolean primaryCodeOnElement(String primaryCode, String canonicalUri);

    /** A subsidiary location with this code is linked to the primary location with this PLC. */
    boolean subsidiaryCodeExists(String primaryCode, String subsidiaryCode);
}
