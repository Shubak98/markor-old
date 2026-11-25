package net.gsantner.markor.ai_assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class AnkiCsvHelperTest {
    @Test
    public void parsesRefinedCsvFieldsWithCommasAndNewlines() throws IOException {
        final List<AnkiCsvHelper.Card> cards = Arrays.asList(
                new AnkiCsvHelper.Card("Front, with comma", "Back\nwith line break"));

        final List<AnkiCsvHelper.Card> parsed = AnkiCsvHelper.parseCsv(AnkiCsvHelper.toCsv(cards));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).getFront()).isEqualTo("Front, with comma");
        assertThat(parsed.get(0).getBack()).isEqualTo("Back\nwith line break");
    }

    @Test
    public void rejectsUnterminatedCsvQuotes() {
        assertThatThrownBy(() -> AnkiCsvHelper.parseCsv("Front,Back\n\"unclosed,value"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unterminated quoted field");
    }
}
