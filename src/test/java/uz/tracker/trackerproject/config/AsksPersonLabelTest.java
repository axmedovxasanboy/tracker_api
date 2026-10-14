package uz.tracker.trackerproject.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which description labels the one-time back-fill reads as "this category names a person" — the
 * owner's Family & support says "Kimga".
 */
class AsksPersonLabelTest {

    @Test
    void labelsThatAskWhoTheMoneyIsFor() {
        assertThat(DataSeeder.labelAsksPerson("Kimga")).isTrue();
        assertThat(DataSeeder.labelAsksPerson("  kimga ")).isTrue();
        assertThat(DataSeeder.labelAsksPerson("Kimga?")).isTrue();
        assertThat(DataSeeder.labelAsksPerson("Kimga (ism)")).isTrue();
        assertThat(DataSeeder.labelAsksPerson("Kim uchun")).isTrue();
        assertThat(DataSeeder.labelAsksPerson("To whom")).isTrue();
        assertThat(DataSeeder.labelAsksPerson("Who")).isTrue();
    }

    @Test
    void labelsThatDoNot() {
        assertThat(DataSeeder.labelAsksPerson(null)).isFalse();
        assertThat(DataSeeder.labelAsksPerson("")).isFalse();
        assertThat(DataSeeder.labelAsksPerson("Doctor name")).isFalse();
        assertThat(DataSeeder.labelAsksPerson("Where")).isFalse();
        assertThat(DataSeeder.labelAsksPerson("Kimning uchun emas")).isFalse();
        assertThat(DataSeeder.labelAsksPerson("Whole sale")).isFalse();
    }
}
