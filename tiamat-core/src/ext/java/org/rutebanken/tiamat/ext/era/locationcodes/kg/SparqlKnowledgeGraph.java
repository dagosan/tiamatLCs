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
 * Modified 2026-10-30: subsidiaryCodes, a SELECT query.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.kg;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.rutebanken.tiamat.ext.era.locationcodes.JsonDocuments;

/**
 * Asks the ERA SPARQL endpoint (GraphDB, rinf-plus), mostly with ASK queries. Each
 * takes about half a second (measured 2026-10-29). Plain JDK HTTP client, so
 * no new dependency.
 */
public class SparqlKnowledgeGraph implements KnowledgeGraph {

    private static final String PREFIXES = "PREFIX era: <http://data.europa.eu/949/>\n";
    private static final Pattern UNSAFE_IRI = Pattern.compile("[\\s<>\"{}|\\\\^`]");

    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    public SparqlKnowledgeGraph(String endpoint, Duration timeout) {
        this.endpoint = URI.create(endpoint);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                // Honours the JVM's proxy settings (https.proxyHost), as ERA's network may need.
                .proxy(ProxySelector.getDefault())
                .build();
    }

    @Override
    public boolean elementExists(String canonicalUri) {
        return ask("ASK { ?element era:canonicalURI " + iri(canonicalUri) + " }");
    }

    @Override
    public boolean primaryCodeExists(String primaryCode) {
        return ask("ASK { ?location era:primaryLocationCode " + literal(primaryCode) + " }");
    }

    @Override
    public boolean primaryCodeOnElement(String primaryCode, String canonicalUri) {
        return ask("ASK { ?element era:canonicalURI " + iri(canonicalUri) + " ; era:primaryLocation ?location .\n"
                + "  ?location era:primaryLocationCode " + literal(primaryCode) + " }");
    }

    @Override
    public boolean subsidiaryCodeExists(String primaryCode, String subsidiaryCode) {
        return ask("ASK { ?location era:primaryLocationCode " + literal(primaryCode) + " .\n"
                + "  ?subsidiary era:linkedToPrimaryLocation ?location ;\n"
                + "              era:subsidiaryLocationCode " + literal(subsidiaryCode) + " }");
    }

    @Override
    public List<String> subsidiaryCodes(String primaryCode) {
        String answer = send("SELECT DISTINCT ?code WHERE {\n"
                + "  ?location era:primaryLocationCode " + literal(primaryCode) + " .\n"
                + "  ?subsidiary era:linkedToPrimaryLocation ?location ;\n"
                + "              era:subsidiaryLocationCode ?code .\n}", "application/sparql-results+json");
        List<String> codes = new ArrayList<>();
        try {
            for (JsonNode row : JsonDocuments.MAPPER.readTree(answer).path("results").path("bindings")) {
                codes.add(row.path("code").path("value").asText());
            }
        } catch (JsonProcessingException e) {
            throw new KnowledgeGraphUnavailableException("Unexpected KG answer: " + abbreviate(answer));
        }
        return codes;
    }

    boolean ask(String query) {
        String answer = send(query, "text/boolean").trim();
        if (answer.equalsIgnoreCase("true")) {
            return true;
        }
        if (answer.equalsIgnoreCase("false")) {
            return false;
        }
        throw new KnowledgeGraphUnavailableException("Unexpected KG answer: " + abbreviate(answer));
    }

    private String send(String query, String accept) {
        String body = "query=" + URLEncoder.encode(PREFIXES + query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", accept)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new KnowledgeGraphUnavailableException("KG not reachable at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeGraphUnavailableException("Interrupted while asking the KG", e);
        }
        if (response.statusCode() != 200) {
            throw new KnowledgeGraphUnavailableException("KG answered HTTP " + response.statusCode());
        }
        return response.body();
    }

    static String iri(String value) {
        if (value == null || !value.startsWith("http") || UNSAFE_IRI.matcher(value).find()) {
            throw new IllegalArgumentException("Not a safe IRI: " + value);
        }
        return "<" + value + ">";
    }

    static String literal(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(character);
            }
        }
        return escaped.append('"').toString();
    }

    private static String abbreviate(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }
}
