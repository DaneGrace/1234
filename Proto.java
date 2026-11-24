import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * CrewBoard wire protocol helpers shared by server and client.
 * Every message is ONE line of text: TYPE|field1|field2|...
 * Field text is escaped so that '|' and line breaks never break the format.
 */
final class Proto {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private Proto() {
    }

    /** Escape one field: backslash -> \\, '|' -> \p, newline -> \n. */
    static String enc(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\p").replace("\r", "").replace("\n", "\\n");
    }

    /** Reverse of enc(). */
    static String dec(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'p' ? '|' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Build one protocol line (without the trailing newline). */
    static String join(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(enc(fields[i]));
        }
        return sb.toString();
    }

    /** Split one received line into decoded fields. */
    static String[] split(String line) {
        String[] parts = line.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = dec(parts[i]);
        return parts;
    }

    /** Field i or "" when the message is shorter than expected. */
    static String field(String[] parts, int i) {
        return i < parts.length ? parts[i] : "";
    }

    static String now() {
        return LocalTime.now().format(TIME);
    }
}
