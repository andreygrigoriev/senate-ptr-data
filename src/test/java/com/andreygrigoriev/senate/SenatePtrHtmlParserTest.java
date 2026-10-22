package com.andreygrigoriev.senate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SenatePtrHtmlParserTest {

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/efd", name));
    }

    @Test
    void parsesRowsAsPrinted() throws IOException {
        SenatePtrHtmlParser.Ptr ptr = SenatePtrHtmlParser.parse(fixture("ptr.html"));

        assertThat(ptr.amendment()).isFalse();
        assertThat(ptr.rows()).hasSize(3);
        SenatePtrHtmlParser.Row first = ptr.rows().get(0);
        assertThat(first.rowNo()).isEqualTo(1);
        assertThat(first.transactionDate()).isEqualTo("02/26/2026");
        assertThat(first.owner()).isEqualTo("Spouse");
        assertThat(first.ticker()).isEqualTo("MSFT");
        assertThat(first.assetName()).isEqualTo("Microsoft Corp");
        assertThat(first.assetType()).isEqualTo("Stock");
        assertThat(first.type()).isEqualTo("Purchase");
        assertThat(first.amount()).isEqualTo("$1,001 - $15,000");
        assertThat(first.comment()).isNull();
    }

    @Test
    void dashTickerBecomesNullAndCommentIsKept() throws IOException {
        SenatePtrHtmlParser.Row row = SenatePtrHtmlParser.parse(fixture("ptr.html")).rows().get(1);

        assertThat(row.ticker()).isNull();
        assertThat(row.type()).isEqualTo("Sale (Partial)");
        assertThat(row.comment()).isEqualTo("Rolled over");
    }

    @Test
    void keepsTickerAndAmountVerbatim() throws IOException {
        SenatePtrHtmlParser.Row row = SenatePtrHtmlParser.parse(fixture("ptr.html")).rows().get(2);

        assertThat(row.ticker()).isEqualTo("BRK.B");
        assertThat(row.amount()).isEqualTo("Over $50,000,000");
    }

    @Test
    void detectsAmendmentFromTitle() throws IOException {
        SenatePtrHtmlParser.Ptr ptr = SenatePtrHtmlParser.parse(fixture("ptr-amendment.html"));

        assertThat(ptr.amendment()).isTrue();
        assertThat(ptr.rows()).singleElement().extracting(SenatePtrHtmlParser.Row::type).isEqualTo("Exchange");
    }

    @Test
    void pageWithoutTableHasNoRows() {
        assertThat(SenatePtrHtmlParser.parse("<html><body><h1>Paper</h1></body></html>")
                        .rows())
                .isEmpty();
    }
}
