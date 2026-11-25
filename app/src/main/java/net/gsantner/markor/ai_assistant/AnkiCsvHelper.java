package net.gsantner.markor.ai_assistant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AnkiCsvHelper {
    private AnkiCsvHelper() {
    }

    public static List<Card> parseCards(final String json) throws IOException {
        final Map<?, ?> object = GeminiMarkdownHelper.parseJsonObject(json);
        final Object value = object.get("cards");
        if (!(value instanceof List<?>) || ((List<?>) value).isEmpty()) {
            throw new IOException("The AI provider returned no Anki cards.");
        }
        final List<Card> cards = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IOException("The AI provider returned an invalid Anki card.");
            }
            final Map<?, ?> card = (Map<?, ?>) item;
            final Object front = card.get("front");
            final Object back = card.get("back");
            if (!(front instanceof String) || ((String) front).trim().isEmpty()
                    || !(back instanceof String) || ((String) back).trim().isEmpty()) {
                throw new IOException("Every Anki card must have non-empty front and back text.");
            }
            cards.add(new Card((String) front, (String) back));
        }
        return cards;
    }

    public static String toCsv(final List<Card> cards) {
        final StringBuilder csv = new StringBuilder("Front,Back\r\n");
        for (Card card : cards) {
            csv.append(escape(card.front)).append(',').append(escape(card.back)).append("\r\n");
        }
        return csv.toString();
    }

    public static List<Card> parseCsv(final String csv) throws IOException {
        if (csv == null || csv.trim().isEmpty()) {
            throw new IOException("The Anki CSV is empty.");
        }
        final List<List<String>> rows = new ArrayList<>();
        final List<String> row = new ArrayList<>();
        final StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < csv.length(); index++) {
            final char character = csv.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else if (character == '"') {
                    quoted = false;
                } else if (character == '\r') {
                    field.append('\n');
                    if (index + 1 < csv.length() && csv.charAt(index + 1) == '\n') {
                        index++;
                    }
                } else {
                    field.append(character);
                }
            } else if (character == '"' && field.length() == 0) {
                quoted = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < csv.length() && csv.charAt(index + 1) == '\n') {
                    index++;
                }
                row.add(field.toString());
                field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(new ArrayList<>(row));
                }
                row.clear();
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw new IOException("The Anki CSV contains an unterminated quoted field.");
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new IOException("The Anki CSV contains no cards.");
        }
        final int firstDataRow = "Front".equalsIgnoreCase(rows.get(0).get(0).replace("\uFEFF", ""))
                && rows.get(0).size() == 2 && "Back".equalsIgnoreCase(rows.get(0).get(1)) ? 1 : 0;
        final List<Card> cards = new ArrayList<>();
        for (int index = firstDataRow; index < rows.size(); index++) {
            final List<String> data = rows.get(index);
            if (data.size() != 2 || data.get(0).trim().isEmpty() || data.get(1).trim().isEmpty()) {
                throw new IOException("Every Anki CSV row must contain non-empty Front and Back fields.");
            }
            cards.add(new Card(data.get(0), data.get(1)));
        }
        if (cards.isEmpty()) {
            throw new IOException("The Anki CSV contains no cards.");
        }
        return cards;
    }

    private static String escape(final String value) {
        final String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf(',') >= 0 || normalized.indexOf('"') >= 0
                || normalized.indexOf('\n') >= 0) {
            return '"' + normalized.replace("\"", "\"\"").replace("\n", "\r\n") + '"';
        }
        return normalized;
    }

    public static final class Card {
        private final String front;
        private final String back;

        public Card(final String front, final String back) {
            this.front = front;
            this.back = back;
        }

        public String getFront() {
            return front;
        }

        public String getBack() {
            return back;
        }
    }
}
