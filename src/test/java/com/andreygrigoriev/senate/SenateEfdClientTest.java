package com.andreygrigoriev.senate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SenateEfdClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> log = new ArrayList<>();
    private volatile boolean forbidden;
    private volatile boolean agreed;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/home/", this::home);
        server.createContext("/search/report/data/", this::data);
        server.createContext("/search/view/ptr/u1/", ex -> reply(ex, 200, "<html>ptr</html>"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void agreementThenSearchSendsCsrfAndPages() throws IOException {
        SenateEfdClient client = new SenateEfdClient(baseUrl, 0);

        assertThat(client.open()).contains("agreement");
        List<SenateEfdClient.SearchRow> rows = client.search(LocalDate.of(2026, 1, 1), null);

        assertThat(rows).hasSize(1);
        SenateEfdClient.SearchRow row = rows.get(0);
        assertThat(row.lastName()).isEqualTo("Capito");
        assertThat(row.link()).isEqualTo("/search/view/ptr/u1/");
        assertThat(row.title()).isEqualTo("Periodic Transaction Report for 03/04/2026");
        assertThat(row.filedDate()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(client.fetchPage(row.link())).contains("ptr");
        assertThat(log).contains("agreed", "csrf=tok123", "body:report_types=%5B11%5D");
    }

    @Test
    void forbiddenBecomesUnavailable() {
        forbidden = true;
        SenateEfdClient client = new SenateEfdClient(baseUrl, 0);

        assertThatThrownBy(client::open).isInstanceOf(EfdUnavailableException.class);
    }

    @Test
    void redirectBackToAgreementBecomesUnavailable() {
        SenateEfdClient client = new SenateEfdClient(baseUrl, 0);
        client.open();
        agreed = false;

        assertThatThrownBy(() -> client.searchPage(LocalDate.of(2026, 1, 1), null, 0))
                .isInstanceOf(EfdUnavailableException.class);
    }

    private void home(HttpExchange ex) throws IOException {
        if (forbidden) {
            reply(ex, 403, "denied");
        } else if (ex.getRequestMethod().equals("GET")) {
            ex.getResponseHeaders().add("Set-Cookie", "csrftoken=tok123; Path=/");
            reply(ex, 200, "<html>agreement</html>");
        } else {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("prohibition_agreement=1") && body.contains("csrfmiddlewaretoken=tok123")) {
                agreed = true;
                log.add("agreed");
            }
            ex.getResponseHeaders().add("Location", "/search/");
            reply(ex, 302, "");
        }
    }

    private void data(HttpExchange ex) throws IOException {
        if (!agreed) {
            ex.getResponseHeaders().add("Location", "/search/home/");
            reply(ex, 302, "");
            return;
        }
        log.add("csrf=" + ex.getRequestHeaders().getFirst("X-CSRFToken"));
        log.add("body:" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")[2]);
        String json = Files.readString(Path.of("src/test/resources/efd/search.json"));
        reply(ex, 200, json.replace("\"recordsFiltered\":3", "\"recordsFiltered\":1")
                .replaceAll("(?s)\\],\\s*\\[\"Tommy.*\\]\\]\\}", "]]}")
                .replace("aaaa1111-0000-0000-0000-000000000001", "u1"));
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, status == 302 ? -1 : bytes.length);
        if (status != 302) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}
