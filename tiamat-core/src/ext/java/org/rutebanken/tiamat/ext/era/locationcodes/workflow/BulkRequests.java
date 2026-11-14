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
 * Added for the ERA Location Code Manager, 2026-11-14.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.object;
import static org.rutebanken.tiamat.ext.era.locationcodes.workflow.RequestDocuments.text;

/**
 * Bulk requests (LCMS specification FR-08): the accepted lines of one
 * uploaded file are requests of their own that carry the same bulk id,
 * {"bulk": {"id", "line", "fileName"}}. They are submitted together and
 * registered together, under one reference and one T0; after that each line
 * goes through the ordinary workflow. Same rules as the frontend's
 * bulk/bulkRequests.ts and store/browserBulk.ts.
 */
public final class BulkRequests {

    /** A bulk file holds at most this many lines (Annex A of the specification). */
    public static final int MAX_LINES = 500;
    /** The header is line 1 of the file. */
    static final int FIRST_LINE = 2;
    static final int FILE_NAME_MAX_LENGTH = 255;

    /** Text key of the frontend: a line is registered with its bulk request, not alone. */
    public static final String REGISTER_WITH_BULK = "rinf_lc_blocked_bulk_register";

    private static final String LINE_SEPARATOR = "/";

    private BulkRequests() {
    }

    /** What is wrong with a submission {"fileName", "lines": [{"line", "data"}]}; empty when nothing is. */
    public static List<String> submissionProblems(JsonNode body) {
        List<String> problems = new ArrayList<>();
        String fileName = text(body, "fileName");
        if (fileName == null || fileName.isBlank() || fileName.length() > FILE_NAME_MAX_LENGTH) {
            problems.add("fileName must have 1 to " + FILE_NAME_MAX_LENGTH + " characters");
        }
        JsonNode lines = body.path("lines");
        if (!lines.isArray() || lines.isEmpty()) {
            problems.add("lines must be an array with at least one line");
            return problems;
        }
        if (lines.size() > MAX_LINES) {
            problems.add("a bulk request has at most " + MAX_LINES + " lines, not " + lines.size());
            return problems;
        }
        Set<Integer> seen = new HashSet<>();
        for (JsonNode line : lines) {
            JsonNode number = line.path("line");
            if (!number.isIntegralNumber() || !number.canConvertToInt() || number.asInt() < FIRST_LINE) {
                problems.add("each line needs its line number in the file, " + FIRST_LINE + " or more");
                continue;
            }
            if (!seen.add(number.asInt())) {
                problems.add("line " + number.asInt() + " appears twice");
            }
            if (!line.path("data").isObject()) {
                problems.add("line " + number.asInt() + ": data must be an object");
            }
        }
        return problems;
    }

    /** Marks a request as line `line` of the bulk request `bulkId`. */
    public static ObjectNode asLine(ObjectNode request, String bulkId, int line, String fileName) {
        ObjectNode bulk = object();
        bulk.put("id", bulkId);
        bulk.put("line", line);
        bulk.put("fileName", fileName);
        request.set("bulk", bulk);
        return request;
    }

    public static boolean isLine(JsonNode request) {
        return request.path("bulk").isObject();
    }

    public static int lineOf(JsonNode request) {
        return request.path("bulk").path("line").asInt();
    }

    /** "LC-2026-0007" and 3 give "LC-2026-0007/3": a line's reference within the request. */
    public static String lineReference(String reference, int line) {
        return reference + LINE_SEPARATOR + line;
    }

    /**
     * Why `actor` may not register the bulk request, as a text key of the
     * frontend; empty when they may: every line still as submitted, by a
     * code administrator who did not decide on any of them.
     */
    public static Optional<String> registrationRefusal(List<JsonNode> lines, Actor actor) {
        if (lines.isEmpty()) {
            return Optional.of(StepPermission.WRONG_STATUS);
        }
        for (JsonNode line : lines) {
            if (!WorkflowSteps.canBeTakenFrom(WorkflowSteps.REGISTER, text(line, "status"))) {
                return Optional.of(StepPermission.WRONG_STATUS);
            }
        }
        if (!Actor.CODE_ADMINISTRATOR.equals(actor.role())) {
            return Optional.of(StepPermission.WRONG_ROLE);
        }
        for (JsonNode line : lines) {
            if (!StepPermission.mayAdminister(line, actor.name())) {
                return Optional.of(StepPermission.FOUR_EYES);
            }
        }
        return Optional.empty();
    }
}
