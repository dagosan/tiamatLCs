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

package org.rutebanken.tiamat.ext.era.locationcodes.model;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CRD's Location_Name_ASCII made from a name: "Grimsås" → "Grimsas",
 * "Hst außer Betrieb" → "Hst ausser Betrieb". Same as the frontend's
 * model/asciiName.ts.
 */
public final class AsciiName {

    /** Letters NFD does not split into a base letter and accents. */
    private static final Map<String, String> SPECIAL_LETTERS = Map.ofEntries(
            Map.entry("ß", "ss"), Map.entry("Æ", "AE"), Map.entry("æ", "ae"),
            Map.entry("Œ", "OE"), Map.entry("œ", "oe"), Map.entry("Ø", "O"),
            Map.entry("ø", "o"), Map.entry("Ł", "L"), Map.entry("ł", "l"),
            Map.entry("Đ", "D"), Map.entry("đ", "d"), Map.entry("Ð", "D"),
            Map.entry("ð", "d"), Map.entry("Þ", "Th"), Map.entry("þ", "th"),
            Map.entry("ı", "i"), Map.entry("’", "'"), Map.entry("‘", "'"),
            Map.entry("–", "-"), Map.entry("—", "-"));
    private static final Pattern COMBINING_MARKS = Pattern.compile("[\\u0300-\\u036f]");
    private static final Pattern NOT_PRINTABLE_ASCII = Pattern.compile("[^\\x20-\\x7e]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private AsciiName() {
    }

    public static String of(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        StringBuilder replaced = new StringBuilder();
        decomposed.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            replaced.append(SPECIAL_LETTERS.getOrDefault(character, character));
        });
        String withoutMarks = COMBINING_MARKS.matcher(replaced).replaceAll("");
        String printable = NOT_PRINTABLE_ASCII.matcher(withoutMarks).replaceAll("");
        return SPACES.matcher(printable).replaceAll(" ").trim();
    }

    /** Printable ASCII only. */
    public static boolean isAscii(String text) {
        return text == null || !NOT_PRINTABLE_ASCII.matcher(text).find();
    }
}
