package com.andreygrigoriev.senate;

import com.andreygrigoriev.senate.SenateEfdClient.SearchRow;
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

/**
 * Scrapes Senate PTRs from eFD into {@code <dir>/{year}.json} and {@code <dir>/state.json}.
 *
 * <p>Usage: {@code SenateEfdMirror <dir> [--from yyyy-MM-dd] [--capture <captureDir>]}. Without {@code --from} the last
 * 60 days are re-scanned (late amendments); detail pages are fetched only for unknown uuids. Exit code 1 on failure,
 * with the error written to {@code state.json}.
 */
public class SenateEfdMirror {

    static final int RESCAN_DAYS = 60;
    private static final int CAPTURE_PAGES = 6;

    private final SenateEfdClient client;
    private final SenateMirrorFiles files;
    private final Clock clock;

    public SenateEfdMirror(SenateEfdClient client, SenateMirrorFiles files, Clock clock) {
        this.client = client;
        this.files = files;
        this.clock = clock;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: SenateEfdMirror <dir> [--from yyyy-MM-dd] [--capture <dir>]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        LocalDate from = null;
        Path capture = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = LocalDate.parse(args[++i]);
                case "--capture" -> capture = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        SenateEfdClient client = new SenateEfdClient(SenateEfdClient.DEFAULT_BASE_URL, 1000);
        SenateEfdMirror mirror = new SenateEfdMirror(client, new SenateMirrorFiles(dir), Clock.systemUTC());
        int code = capture != null ? mirror.capture(capture) : mirror.run(from);
        System.exit(code);
    }

    /** Runs the scrape; returns the process exit code. */
    public int run(LocalDate from) {
        Instant now = clock.instant();
        State previous = files.readState();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate backfilledFrom = previous.backfilledFrom();
        try {
            client.open();
            if (from == null) {
                scan(today.minusDays(RESCAN_DAYS), null);
            } else {
                for (int year = from.getYear(); year <= today.getYear(); year++) {
                    LocalDate start = year == from.getYear() ? from : LocalDate.of(year, 1, 1);
                    scan(start, LocalDate.of(year, 12, 31));
                    files.writeState(new State(now, previous.lastSuccessAt(), previous.error(), backfilledFrom, total()));
                }
                backfilledFrom = backfilledFrom == null || from.isBefore(backfilledFrom) ? from : backfilledFrom;
            }
            files.writeLoadedYears();
            files.writeState(new State(now, now, null, backfilledFrom, total()));
            return 0;
        } catch (RuntimeException e) {
            files.writeLoadedYears();
            files.writeState(new State(now, previous.lastSuccessAt(), describe(e), backfilledFrom, total()));
            System.err.println("Scrape failed: " + describe(e));
            return 1;
        }
    }

    /** Saves the raw home page, a search page and a few PTR pages (amendment, paper) for building fixtures. */
    public int capture(Path out) {
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("home.html"), client.open());
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            Files.writeString(out.resolve("search.json"), client.searchPage(today.minusDays(RESCAN_DAYS * 3L), null, 0));
            List<SearchRow> rows = client.search(today.minusDays(RESCAN_DAYS * 3L), null);
            int saved = 0;
            boolean amendment = false;
            boolean paper = false;
            for (SearchRow row : rows) {
                boolean isPaper = row.link().contains("/paper/");
                boolean isAmendment = row.title().toLowerCase().contains("amendment");
                boolean wanted = (isAmendment && !amendment) || (isPaper && !paper) || (!isAmendment && !isPaper);
                if (!wanted || saved >= CAPTURE_PAGES) {
                    continue;
                }
                amendment |= isAmendment;
                paper |= isPaper;
                String name = (isPaper ? "paper-" : isAmendment ? "amendment-" : "ptr-") + (++saved) + ".html";
                Files.writeString(out.resolve(name), client.fetchPage(row.link()));
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("Capture failed: " + describe(e));
            return 1;
        }
    }

    private void scan(LocalDate start, LocalDate end) {
        for (SearchRow row : client.search(start, end)) {
            String uuid = uuid(row.link());
            if (files.contains(uuid)) {
                continue;
            }
            boolean paper = row.link().contains("/paper/");
            boolean amendment = row.title().toLowerCase().contains("amendment");
            List<SenatePtrHtmlParser.Row> rows = List.of();
            if (!paper) {
                SenatePtrHtmlParser.Ptr ptr = SenatePtrHtmlParser.parse(client.fetchPage(row.link()));
                amendment |= ptr.amendment();
                rows = ptr.rows();
            }
            files.add(new Filing(
                    uuid,
                    row.firstName(),
                    row.lastName(),
                    row.office(),
                    row.filedDate(),
                    amendment,
                    paper,
                    row.link(),
                    rows));
        }
        files.writeLoadedYears();
    }

    static String uuid(String link) {
        String trimmed = link.endsWith("/") ? link.substring(0, link.length() - 1) : link;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private int total() {
        return files.totalFilings();
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
