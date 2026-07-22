package cn.edu.pku.lixutian.service.code;

import java.util.ArrayList;
import java.util.List;

/** Applies exact Agent Search/Replace blocks to in-memory source content. */
final class SearchReplacePatch {
    static final String SEARCH_START = "<<<<<<< SEARCH";
    static final String SEPARATOR = "=======";
    static final String REPLACE_END = ">>>>>>> REPLACE";
    static final String CREATE_START = "<<<<<<< CREATE";
    static final String CREATE_END = ">>>>>>> CREATE";

    private static final int MAX_EDIT_BLOCKS = 50;

    private SearchReplacePatch() {
    }

    static String apply(String response, String originalContent, boolean createMode) {
        List<String> lines = protocolLines(response);
        return createMode
                ? applyCreate(lines)
                : applyEdits(lines, originalContent == null ? "" : originalContent);
    }

    private static String applyCreate(List<String> lines) {
        int start = nextContentLine(lines, 0);
        if (start >= lines.size() || !CREATE_START.equals(lines.get(start).trim())) {
            throw new IllegalArgumentException(
                    "A new or empty source file must use one <<<<<<< CREATE block."
            );
        }
        int end = findMarker(lines, start + 1, CREATE_END);
        if (end < 0) {
            throw new IllegalArgumentException("Agent3 CREATE block is not closed.");
        }
        requireOnlyBlankLines(lines, end + 1);
        String content = joinLines(lines, start + 1, end);
        if (content.isBlank()) {
            throw new IllegalArgumentException("Agent3 CREATE block cannot be empty.");
        }
        return content;
    }

    private static String applyEdits(List<String> lines, String originalContent) {
        String lineSeparator = originalContent.contains("\r\n") ? "\r\n" : "\n";
        String normalizedOriginal = normalizeLineEndings(originalContent);
        String working = normalizedOriginal;
        int cursor = nextContentLine(lines, 0);
        int editCount = 0;

        while (cursor < lines.size()) {
            if (!SEARCH_START.equals(lines.get(cursor).trim())) {
                throw new IllegalArgumentException(
                        "Agent3 must return only <<<<<<< SEARCH / >>>>>>> REPLACE blocks."
                );
            }
            if (++editCount > MAX_EDIT_BLOCKS) {
                throw new IllegalArgumentException("Agent3 returned too many Search/Replace blocks.");
            }

            int separator = findMarker(lines, cursor + 1, SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("Agent3 Search/Replace block is missing =======.");
            }
            int end = findMarker(lines, separator + 1, REPLACE_END);
            if (end < 0) {
                throw new IllegalArgumentException("Agent3 Search/Replace block is not closed.");
            }

            String search = joinLines(lines, cursor + 1, separator);
            String replacement = joinLines(lines, separator + 1, end);
            if (search.isEmpty()) {
                throw new IllegalArgumentException("Agent3 SEARCH text cannot be empty.");
            }
            boolean replacesCompleteFile = search.equals(working)
                    || (working.startsWith(search) && working.substring(search.length()).isBlank());
            if (replacesCompleteFile && working.lines().count() > 1) {
                throw new IllegalArgumentException(
                        "Agent3 cannot replace the complete existing file; use minimal Search/Replace blocks."
                );
            }

            int match = working.indexOf(search);
            if (match < 0) {
                throw new IllegalArgumentException(
                        "SEARCH text did not match the current target file exactly."
                );
            }
            if (working.indexOf(search, match + 1) >= 0) {
                throw new IllegalArgumentException(
                        "SEARCH text matched more than once; include more unchanged context."
                );
            }
            working = working.substring(0, match) + replacement + working.substring(match + search.length());
            cursor = nextContentLine(lines, end + 1);
        }

        if (editCount == 0) {
            throw new IllegalArgumentException("Agent3 returned no Search/Replace blocks.");
        }
        if (working.equals(normalizedOriginal)) {
            throw new IllegalArgumentException("Agent3 Search/Replace blocks produced no changes.");
        }
        return "\r\n".equals(lineSeparator) ? working.replace("\n", "\r\n") : working;
    }

    private static List<String> protocolLines(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("Agent3 returned an empty response.");
        }
        String normalized = normalizeLineEndings(response);
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));

        int first = nextContentLine(lines, 0);
        int last = previousContentLine(lines, lines.size() - 1);
        if (first <= last && lines.get(first).trim().startsWith("```")) {
            if (first == last || !"```".equals(lines.get(last).trim())) {
                throw new IllegalArgumentException("Agent3 Markdown fence is not closed.");
            }
            lines = new ArrayList<>(lines.subList(first + 1, last));
        }
        return lines;
    }

    private static int findMarker(List<String> lines, int start, String marker) {
        for (int index = start; index < lines.size(); index++) {
            if (marker.equals(lines.get(index).trim())) {
                return index;
            }
        }
        return -1;
    }

    private static int nextContentLine(List<String> lines, int start) {
        int index = start;
        while (index < lines.size() && lines.get(index).isBlank()) {
            index++;
        }
        return index;
    }

    private static int previousContentLine(List<String> lines, int start) {
        int index = start;
        while (index >= 0 && lines.get(index).isBlank()) {
            index--;
        }
        return index;
    }

    private static void requireOnlyBlankLines(List<String> lines, int start) {
        if (nextContentLine(lines, start) < lines.size()) {
            throw new IllegalArgumentException("Agent3 returned content outside the CREATE block.");
        }
    }

    private static String joinLines(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start, end));
    }

    private static String normalizeLineEndings(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
    }
}
