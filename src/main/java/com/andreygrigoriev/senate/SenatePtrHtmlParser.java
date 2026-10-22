package com.andreygrigoriev.senate;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/** Parses an eFD PTR view page. Values stay as printed; a {@code --} ticker becomes null. */
public final class SenatePtrHtmlParser {

    /** One transaction row as printed on the page. */
    public record Row(
            int rowNo,
            String transactionDate,
            String owner,
            String ticker,
            String assetName,
            String assetType,
            String type,
            String amount,
            String comment) {}

    public record Ptr(boolean amendment, List<Row> rows) {}

    private SenatePtrHtmlParser() {}

    public static Ptr parse(String html) {
        Document doc = Jsoup.parse(html);
        Element title = doc.selectFirst("h1, h2");
        boolean amendment = title != null && title.text().toLowerCase().contains("amendment");
        List<Row> rows = new ArrayList<>();
        Element table = doc.selectFirst("table.table-striped, table");
        if (table != null) {
            for (Element tr : table.select("tbody tr")) {
                Elements cells = tr.select("td");
                if (cells.size() < 9) {
                    continue;
                }
                rows.add(new Row(
                        parseRowNo(cells.get(0).text(), rows.size() + 1),
                        cells.get(1).text().strip(),
                        cells.get(2).text().strip(),
                        ticker(cells.get(3).text()),
                        cells.get(4).text().strip(),
                        cells.get(5).text().strip(),
                        cells.get(6).text().strip(),
                        cells.get(7).text().strip(),
                        blankToNull(cells.get(8).text())));
            }
        }
        return new Ptr(amendment, rows);
    }

    private static int parseRowNo(String text, int fallback) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String ticker(String text) {
        String value = text.strip();
        return value.isEmpty() || value.equals("--") ? null : value;
    }

    private static String blankToNull(String text) {
        String value = text.strip();
        return value.isEmpty() || value.equals("--") ? null : value;
    }
}
