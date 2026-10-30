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

package org.rutebanken.tiamat.ext.era.locationcodes.kg;

/** The KG did not answer (network, timeout, error status or unreadable reply). */
public class KnowledgeGraphUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KnowledgeGraphUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public KnowledgeGraphUnavailableException(String message) {
        super(message);
    }
}
