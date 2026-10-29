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

import java.util.List;

/** A save the register refuses, with the HTTP status and reasons to send back. */
public class LocationCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final int BAD_REQUEST = 400;
    public static final int NOT_FOUND = 404;
    public static final int CONFLICT = 409;
    public static final int UNPROCESSABLE = 422;
    public static final int UNAVAILABLE = 503;

    private final int status;
    private final String error;
    private final transient List<String> details;

    public LocationCodeException(int status, String error, List<String> details) {
        super(error + (details.isEmpty() ? "" : ": " + String.join("; ", details)));
        this.status = status;
        this.error = error;
        this.details = List.copyOf(details);
    }

    public LocationCodeException(int status, String error, String detail) {
        this(status, error, List.of(detail));
    }

    public int status() {
        return status;
    }

    /** Stable identifier the frontend can translate, e.g. "invalidCode". */
    public String error() {
        return error;
    }

    public List<String> details() {
        return details;
    }
}
