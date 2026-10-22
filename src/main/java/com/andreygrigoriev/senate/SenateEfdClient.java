package com.andreygrigoriev.senate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Session client for efdsearch.senate.gov: cookie jar, agreement, CSRF header, paged search, page fetch. */
public class SenateEfdClient {

    public static final String DEFAULT_BASE_URL = "https://efdsearch.senate.gov";
    static final int PAGE_SIZE = 100;
    private static final String HOME = "/search/home/";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0 Safari/537.36";
    private static final DateTimeFormatter SEARCH_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy 00:00:00");
    private static final Pattern HREF = Pattern.compile("href=[\"']([^\"']+)[\"']");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    /** One row of the search result: a report link with the filer name and filing date. */
    public record SearchRow(
            String firstName, String lastName, String office, String link, String title, LocalDate filedDate) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String baseUrl;
    private final long delayMillis;
    private long lastRequestAt;

    public SenateEfdClient(String baseUrl, long delayMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.delayMillis = delayMillis;
    }

    /** GET the home page for the csrftoken cookie, then accept the agreement. Returns the raw home page. */
    public String open() {
        String home = send(HttpRequest.newBuilder(uri(HOME)).GET(), HOME, false);
        String csrf = csrfToken();
        send(
                HttpRequest.newBuilder(uri(HOME))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Referer", baseUrl + HOME)
                        .POST(form(Map.of("csrfmiddlewaretoken", csrf, "prohibition_agreement", "1"))),
                HOME,
                true);
        return home;
    }

    /** Raw JSON of one search page of PTR reports submitted in [start, end] (end may be null). */
    public String searchPage(LocalDate start, LocalDate end, int offset) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("start", String.valueOf(offset));
        fields.put("length", String.valueOf(PAGE_SIZE));
        fields.put("report_types", "[11]");
        fields.put("filer_types", "[]");
        fields.put("submitted_start_date", start.format(SEARCH_DATE));
        if (end != null) {
            fields.put("submitted_end_date", end.format(SEARCH_DATE));
        }
        fields.put("candidate_state", "");
        fields.put("senator_state", "");
        fields.put("office_id", "");
        fields.put("first_name", "");
        fields.put("last_name", "");
        fields.put("csrfmiddlewaretoken", csrfToken());
        return send(
                HttpRequest.newBuilder(uri("/search/report/data/"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("X-CSRFToken", csrfToken())
                        .header("Referer", baseUrl + "/search/")
                        .POST(form(fields)),
                "/search/report/data/",
                false);
    }

    /** All PTR rows submitted in [start, end], newest page order as eFD returns them. */
    public List<SearchRow> search(LocalDate start, LocalDate end) {
        List<SearchRow> rows = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode page = readJson(searchPage(start, end, offset));
            JsonNode data = page.path("data");
            for (JsonNode row : data) {
                rows.add(toRow(row));
            }
            offset += PAGE_SIZE;
            if (data.isEmpty() || offset >= page.path("recordsFiltered").asInt(0)) {
                return rows;
            }
        }
    }

    public String fetchPage(String link) {
        return send(HttpRequest.newBuilder(uri(link)).GET(), link, false);
    }

    private SearchRow toRow(JsonNode row) {
        String anchor = row.path(3).asText();
        Matcher href = HREF.matcher(anchor);
        if (!href.find()) {
            throw new IllegalStateException("No link in search row: " + row);
        }
        String title = TAGS.matcher(anchor).replaceAll("").strip();
        LocalDate filed = LocalDate.parse(row.path(4).asText().strip(), DateTimeFormatter.ofPattern("MM/dd/yyyy"));
        return new SearchRow(
                row.path(0).asText().strip(),
                row.path(1).asText().strip(),
                row.path(2).asText().strip(),
                href.group(1),
                title,
                filed);
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("eFD returned non-JSON: " + abbreviate(body), e);
        }
    }

    private String csrfToken() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("csrftoken"))
                .map(c -> c.getValue())
                .findFirst()
                .orElseThrow(() -> new EfdUnavailableException("No csrftoken cookie from eFD"));
    }

    private String send(HttpRequest.Builder builder, String path, boolean agreement) {
        throttle();
        HttpRequest request = builder.header("User-Agent", USER_AGENT).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("eFD request failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: " + path, e);
        }
        int status = response.statusCode();
        if (status == 403) {
            throw new EfdUnavailableException("eFD answered 403 for " + path);
        }
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("Location").orElse("");
            boolean backToAgreement = location.contains(HOME) && !path.equals(HOME);
            if (backToAgreement || !agreement) {
                throw new EfdUnavailableException("eFD redirected " + path + " to " + location);
            }
            return response.body();
        }
        if (status >= 400) {
            throw new IllegalStateException("eFD answered " + status + " for " + path);
        }
        return response.body();
    }

    private void throttle() {
        long wait = lastRequestAt + delayMillis - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private URI uri(String path) {
        return URI.create(path.startsWith("http") ? path : baseUrl + path);
    }

    private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        return HttpRequest.BodyPublishers.ofString(fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")));
    }

    private static String abbreviate(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
