package com.andreygrigoriev.senate;

import static org.assertj.core.api.Assertions.assertThat;

import com.andreygrigoriev.senate.SenateMirrorFiles.Filing;
import com.andreygrigoriev.senate.SenateMirrorFiles.State;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SenateMirrorFilesTest {

    @TempDir
    Path dir;

    private static SenatePtrHtmlParser.Row row(int no) {
        return new SenatePtrHtmlParser.Row(no, "02/26/2026", "Self", "MSFT", "Microsoft", "Stock", "Purchase", "$1,001 - $15,000", null);
    }

    private static Filing filing(String uuid, LocalDate filed, boolean amendment) {
        return new Filing(uuid, "A", "B", "B, A (Senator)", filed, amendment, false, "/search/view/ptr/" + uuid + "/", List.of(row(1)));
    }

    @Test
    void newUuidIsAddedAndKnownOneUntouched() {
        SenateMirrorFiles files = new SenateMirrorFiles(dir);
        files.add(filing("b", LocalDate.of(2026, 3, 1), false));
        files.writeLoadedYears();

        SenateMirrorFiles again = new SenateMirrorFiles(dir);
        boolean replaced = again.add(new Filing("b", "X", "Y", "o", LocalDate.of(2026, 3, 1), false, false, "u", List.of()));
        boolean added = again.add(filing("a", LocalDate.of(2026, 3, 2), false));
        again.writeLoadedYears();

        assertThat(replaced).isFalse();
        assertThat(added).isTrue();
        Filing kept = new SenateMirrorFiles(dir).year(2026).get("b");
        assertThat(kept.firstName()).isEqualTo("A");
        assertThat(kept.rows()).hasSize(1);
    }

    @Test
    void yearFileIsSplitByFiledDateSortedByUuidAndPrettyPrinted() throws IOException {
        SenateMirrorFiles files = new SenateMirrorFiles(dir);
        files.add(filing("zz", LocalDate.of(2026, 1, 2), false));
        files.add(filing("aa", LocalDate.of(2026, 5, 2), false));
        files.add(filing("mm", LocalDate.of(2025, 12, 31), false));
        files.writeLoadedYears();

        String json2026 = Files.readString(dir.resolve("2026.json"));
        assertThat(json2026.indexOf("\"aa\"")).isLessThan(json2026.indexOf("\"zz\""));
        assertThat(json2026).contains("\n  ").doesNotContain("\"mm\"");
        assertThat(Files.readString(dir.resolve("2025.json"))).contains("\"mm\"").contains("\"2025-12-31\"");
    }

    @Test
    void amendmentIsKeptAsSeparateFiling() {
        SenateMirrorFiles files = new SenateMirrorFiles(dir);
        files.add(filing("orig", LocalDate.of(2026, 3, 1), false));
        files.add(filing("amend", LocalDate.of(2026, 3, 9), true));

        assertThat(files.year(2026)).containsOnlyKeys("orig", "amend");
        assertThat(files.year(2026).get("amend").amendment()).isTrue();
    }

    @Test
    void stateRoundTrips() {
        SenateMirrorFiles files = new SenateMirrorFiles(dir);
        State state = new State(Instant.parse("2026-03-01T06:17:00Z"), null, "boom", LocalDate.of(2021, 1, 1), 5);
        files.writeState(state);

        assertThat(new SenateMirrorFiles(dir).readState()).isEqualTo(state);
    }

    @Test
    void failureWritesErrorStateKeepsLastSuccessAndExitsWithOne() {
        SenateMirrorFiles files = new SenateMirrorFiles(dir);
        Instant lastOk = Instant.parse("2026-03-01T06:17:00Z");
        files.writeState(new State(lastOk, lastOk, null, null, 0));
        SenateEfdClient failing = new SenateEfdClient("http://127.0.0.1:1", 0);
        Clock clock = Clock.fixed(Instant.parse("2026-03-02T06:17:00Z"), ZoneOffset.UTC);

        int code = new SenateEfdMirror(failing, files, clock).run(null);

        assertThat(code).isEqualTo(1);
        State state = new SenateMirrorFiles(dir).readState();
        assertThat(state.error()).isNotBlank();
        assertThat(state.lastSuccessAt()).isEqualTo(lastOk);
        assertThat(state.lastRunAt()).isEqualTo(Instant.parse("2026-03-02T06:17:00Z"));
    }

    @Test
    void uuidIsLastPathSegment() {
        assertThat(SenateEfdMirror.uuid("/search/view/ptr/abc-123/")).isEqualTo("abc-123");
    }
}
