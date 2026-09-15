package uz.tracker.trackerproject.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The old TRANSPORT route format, "From >>> To" with an optional "\n&lt;note&gt;", was parsed back
 * out for display and nothing parses it any more. {@link DataSeeder#unfoldRoute} rewrites it once,
 * as the line the list used to show: the note first (it was the title), then the route.
 */
class RetiredRouteFormatTest {

    @Test
    void aRouteWithANoteLeadsWithTheNote() {
        assertThat(DataSeeder.unfoldRoute("Kvartira >>> Chilonzor metro\nTaxi, raining"))
                .isEqualTo("Taxi, raining · Kvartira → Chilonzor metro");
    }

    @Test
    void aRouteOnItsOwnBecomesTheRoute() {
        assertThat(DataSeeder.unfoldRoute("Kvartira >>> Chilonzor metro"))
                .isEqualTo("Kvartira → Chilonzor metro");
    }

    @Test
    void aMissingEndKeepsTheDashTheOldFormWrote() {
        assertThat(DataSeeder.unfoldRoute("— >>> Airport")).isEqualTo("— → Airport");
        assertThat(DataSeeder.unfoldRoute(" >>> Airport")).isEqualTo("— → Airport");
    }

    @Test
    void anythingThatIsNotARouteIsLeftAlone() {
        assertThat(DataSeeder.unfoldRoute("Lunch with Aziz")).isNull();
        assertThat(DataSeeder.unfoldRoute(null)).isNull();
    }
}
