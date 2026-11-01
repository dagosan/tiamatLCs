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
 * Modified 2026-10-30: the code patterns are public, for the workflow package.
 * Modified 2026-11-02: code versions with CRD's columns.
 */

package org.rutebanken.tiamat.ext.era.locationcodes;

import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CodeVersion;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RequestEntry;
import org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.RinfLink;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.ASSET;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.CODE_TYPES;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.ERA_NAMESPACE;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.OPERATIONAL_POINT;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.OPERATIONS;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.SECTION_OF_LINE;
import static org.rutebanken.tiamat.ext.era.locationcodes.model.LocationCodeModel.STATUSES;

/**
 * Checks that need nothing but the documents: the form of codes and keys, and
 * that a save only adds to what is stored (history and events are append-only,
 * which also stops one user's save from silently replacing another's).
 */
public final class LocationCodeRules {

    /** ISO alpha-2 (or EU) plus five digits, as every PLC in the KG (TD 103 4.10.1). */
    public static final Pattern PRIMARY_CODE = Pattern.compile("^[A-Z]{2}[0-9]{5}$");
    /** TSI LocationSubsidiaryCode: up to ten letters, digits, dots and hyphens ("NO-S.998"). */
    public static final Pattern SUBSIDIARY_CODE = Pattern.compile("^[A-Za-z0-9.\\-]{1,10}$");
    private static final Pattern UNSAFE_IRI = Pattern.compile("[\\s<>\"{}|\\\\^`]");

    private LocationCodeRules() {
    }

    /** The form of a code version: its code and key, RINF link, and CRD columns (VersionRules). */
    public static List<String> versionProblems(CodeVersion code) {
        List<String> problems = new ArrayList<>();
        if (!CODE_TYPES.contains(code.codeType())) {
            problems.add("codeType must be primary or subsidiary, not \"" + code.codeType() + "\"");
            return problems;
        }
        if (code.isPrimary()) {
            if (!matches(PRIMARY_CODE, code.code())) {
                problems.add("PLC \"" + code.code() + "\" is not two letters and five digits");
            }
            if (!Objects.equals(code.key(), code.code())) {
                problems.add("key of a PLC must be the code itself");
            }
            if (!isBlank(code.parentPrimaryCode())) {
                problems.add("a PLC has no parent primary code");
            }
            if (code.code() != null && code.code().length() >= 2
                    && !code.code().substring(0, 2).equals(code.countryIsoCode())) {
                problems.add("country must be the code's prefix");
            }
        } else {
            if (!matches(SUBSIDIARY_CODE, code.code())) {
                problems.add("SLC \"" + code.code() + "\" is not 1 to 10 letters, digits, dots or hyphens");
            }
            if (!matches(PRIMARY_CODE, code.parentPrimaryCode())) {
                problems.add("parent PLC \"" + code.parentPrimaryCode() + "\" is not two letters and five digits");
            } else {
                if (!Objects.equals(code.key(), code.parentPrimaryCode() + "/" + code.code())) {
                    problems.add("key of an SLC must be \"<parent PLC>/<SLC>\"");
                }
                if (!code.parentPrimaryCode().substring(0, 2).equals(code.countryIsoCode())) {
                    problems.add("country must be the parent PLC's prefix");
                }
            }
        }
        problems.addAll(linkProblems(code.rinfLink(), code.isPrimary()));
        problems.addAll(VersionRules.problems(code));
        return problems;
    }

    /** A PLC sits on an OP or a section of line; an SLC may also sit on an asset. */
    public static List<String> linkProblems(RinfLink link, boolean primary) {
        List<String> problems = new ArrayList<>();
        if (link == null) {
            return problems;
        }
        boolean placeKind = OPERATIONAL_POINT.equals(link.kind()) || SECTION_OF_LINE.equals(link.kind());
        if (!placeKind && !(ASSET.equals(link.kind()) && !primary)) {
            problems.add(primary
                    ? "a PLC links to an operationalPoint or a sectionOfLine, not \"" + link.kind() + "\""
                    : "rinfLink.kind must be operationalPoint, sectionOfLine or asset");
            return problems;
        }
        if (isBlank(link.canonicalId())) {
            problems.add("rinfLink.canonicalId is empty");
            return problems;
        }
        String uri = link.uri();
        if (!uri.startsWith(ERA_NAMESPACE) || UNSAFE_IRI.matcher(uri).find()) {
            problems.add("rinfLink is not an ERA canonical URI: " + uri);
        }
        return problems;
    }

    public static List<String> requestProblems(RequestEntry request) {
        List<String> problems = new ArrayList<>();
        if (isBlank(request.id())) {
            problems.add("id is empty");
        }
        if (!STATUSES.contains(request.status())) {
            problems.add("unknown status \"" + request.status() + "\"");
        }
        if (!CODE_TYPES.contains(request.codeType())) {
            problems.add("data.codeType must be primary or subsidiary");
        }
        if (!OPERATIONS.contains(request.operation())) {
            problems.add("unknown data.operation \"" + request.operation() + "\"");
        }
        if (isBlank(request.country())) {
            problems.add("data.country is empty");
        }
        if (isBlank(request.submittedAt())) {
            problems.add("submittedAt is empty");
        }
        if (request.eventCount() < 1) {
            problems.add("events is empty");
        }
        return problems;
    }

    /** What a later save of a request may not change. */
    public static List<String> requestChangeProblems(RequestEntry stored, RequestEntry incoming) {
        List<String> problems = new ArrayList<>();
        if (!Objects.equals(stored.submittedAt(), incoming.submittedAt())
                || !Objects.equals(stored.submittedBy(), incoming.submittedBy())) {
            problems.add("submittedAt and submittedBy cannot change");
        }
        if (stored.reference() != null && !stored.reference().equals(incoming.reference())) {
            problems.add("reference " + stored.reference() + " cannot change");
        }
        return problems;
    }

    /**
     * How a save relates to what is stored: it adds entries, repeats the stored
     * state, or is based on an outdated copy (someone else saved in between).
     */
    public enum Extension { ADDS, SAME, STALE }

    public static <T> Extension extension(List<T> stored, List<T> incoming) {
        if (incoming.size() < stored.size() || !incoming.subList(0, stored.size()).equals(stored)) {
            return Extension.STALE;
        }
        return incoming.size() == stored.size() ? Extension.SAME : Extension.ADDS;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    static boolean isBlankOrDate(String value) {
        if (isBlank(value)) {
            return true;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
