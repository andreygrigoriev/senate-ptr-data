package com.andreygrigoriev.senate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON model of the mirror ({year}.json and state.json) and the merge rules. */
public class SenateMirrorFiles {

    public record Filing(
            String uuid,
            String firstName,
            String lastName,
            String office,
            LocalDate filedDate,
            boolean amendment,
            boolean paper,
            String url,
            List<SenatePtrHtmlParser.Row> rows) {}

    public record State(Instant lastRunAt, Instant lastSuccessAt, String error, LocalDate backfilledFrom, int filings) {}

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    private final Path dir;
    private final Map<Integer, Map<String, Filing>> years = new LinkedHashMap<>();

    public SenateMirrorFiles(Path dir) {
        this.dir = dir;
    }

    public Map<String, Filing> year(int year) {
        return years.computeIfAbsent(year, this::load);
    }

    public boolean contains(String uuid) {
        return years.values().stream().anyMatch(m -> m.containsKey(uuid))
                || knownOnDisk().anyMatch(m -> m.containsKey(uuid));
    }

    /** Adds the filing to the file of its filed-date year unless the uuid is already known. */
    public boolean add(Filing filing) {
        if (contains(filing.uuid())) {
            return false;
        }
        year(filing.filedDate().getYear()).put(filing.uuid(), filing);
        return true;
    }

    public void writeYear(int year) {
        List<Filing> sorted = new ArrayList<>(year(year).values());
        sorted.sort(Comparator.comparing(Filing::uuid));
        write(dir.resolve(year + ".json"), sorted);
    }

    public void writeLoadedYears() {
        new ArrayList<>(years.keySet()).forEach(this::writeYear);
    }

    public State readState() {
        Path file = dir.resolve("state.json");
        if (!Files.exists(file)) {
            return new State(null, null, null, null, 0);
        }
        try {
            return mapper.readValue(file.toFile(), State.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    public void writeState(State state) {
        write(dir.resolve("state.json"), state);
    }

    public int totalFilings() {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("\\d{4}\\.json"))
                    .mapToInt(p -> year(Integer.parseInt(p.getFileName().toString().substring(0, 4)))
                            .size())
                    .sum();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list " + dir, e);
        }
    }

    private java.util.stream.Stream<Map<String, Filing>> knownOnDisk() {
        try {
            if (!Files.isDirectory(dir)) {
                return java.util.stream.Stream.empty();
            }
            try (var files = Files.list(dir)) {
                return files.map(p -> p.getFileName().toString())
                        .filter(n -> n.matches("\\d{4}\\.json"))
                        .map(n -> year(Integer.parseInt(n.substring(0, 4))))
                        .toList()
                        .stream();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list " + dir, e);
        }
    }

    private Map<String, Filing> load(int year) {
        Map<String, Filing> result = new LinkedHashMap<>();
        Path file = dir.resolve(year + ".json");
        if (Files.exists(file)) {
            try {
                List<Filing> filings = mapper.readValue(file.toFile(), new TypeReference<>() {});
                filings.forEach(f -> result.put(f.uuid(), f));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + file, e);
            }
        }
        return result;
    }

    private void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(value) + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }
}
