package ddsmanager.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class LanguageServiceTest {
    private final Set<String> available = Set.of("en_US", "fr_FR", "zh_CN");
    @Test void usesMatchingClientLanguage() {
        assertEquals("fr_FR", LanguageService.selectLocale(Locale.FRANCE, "en_US", true, available));
        assertEquals("zh_CN", LanguageService.selectLocale(Locale.TAIWAN, "en_US", true, available));
    }
    @Test void respectsConfiguredDefaultWhenDetectionDisabled() {
        assertEquals("en_US", LanguageService.selectLocale(Locale.FRANCE, "en_US", false, available));
    }
    @Test void fallsBackForUnknownLanguage() {
        assertEquals("en_US", LanguageService.selectLocale(Locale.ITALY, "en_US", true, available));
    }
    @Test void rendersTranslatedColorTags() {
        Component rendered = LanguageService.renderTemplate("<green>Bonjour <yellow>{channel}</yellow> !</green>",
                Map.of("channel", "local"), NamedTextColor.WHITE);
        assertEquals("Bonjour local !", plain(rendered));
        List<Component> parts = parts(rendered);
        assertTrue(parts.stream().anyMatch(part ->
                part instanceof TextComponent t && t.content().equals("B") && NamedTextColor.GREEN.equals(part.color())));
        // The inner placeholder must use yellow, not inherit green.
        assertTrue(parts.stream().anyMatch(part ->
                part instanceof TextComponent t && t.content().equals("l") && NamedTextColor.YELLOW.equals(part.color())));
    }
    @Test void placeholdersCannotInjectFormatting() {
        Component rendered = LanguageService.renderTemplate("<green>Bonjour <yellow>{player}</yellow> !</green>",
                Map.of("player", "<red>guest</red>"), NamedTextColor.WHITE);
        assertEquals("Bonjour <red>guest</red> !", plain(rendered));
        assertFalse(parts(rendered).stream().anyMatch(part -> NamedTextColor.RED.equals(part.color())));
    }
    private static List<Component> parts(Component component) {
        List<Component> all = new ArrayList<>();
        all.add(component);
        component.children().forEach(child -> all.addAll(parts(child)));
        return all;
    }
    private static String plain(Component component) {
        StringBuilder result = new StringBuilder();
        if (component instanceof TextComponent t) result.append(t.content());
        component.children().forEach(child -> result.append(plain(child)));
        return result.toString();
    }
    @Test void interpolatesWithoutInterpretingMarkup() {
        assertEquals("Welcome <red>guest</red> to lobby", LanguageService.interpolate("Welcome {player} to {server}", Map.of("player", "<red>guest</red>", "server", "lobby")));
    }
}
