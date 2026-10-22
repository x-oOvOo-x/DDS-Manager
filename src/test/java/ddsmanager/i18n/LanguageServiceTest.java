package ddsmanager.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ddsmanager.config.PluginConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class LanguageServiceTest {
    private final Set<String> available = Set.of("en_US", "fr_FR", "zh_CN");
    @TempDir Path dataDirectory;
    @Test void usesMatchingClientLanguage() {
        assertEquals("fr_FR", LanguageService.selectLocale(Locale.FRANCE, "en_US", true, available));
        assertEquals("zh_CN", LanguageService.selectLocale(Locale.TAIWAN, "en_US", true, available));
    }
    @Test void selectsAllAddedClientLanguages() {
        Map<Locale, String> cases = Map.of(
                Locale.JAPAN, "ja_JP",
                Locale.KOREA, "ko_KR",
                Locale.GERMANY, "de_DE",
                new Locale("ru", "RU"), "ru_RU",
                new Locale("es", "ES"), "es_ES");
        Set<String> locales = Set.of("en_US", "fr_FR", "zh_CN", "zh_TW", "ja_JP", "ko_KR", "ru_RU", "de_DE", "es_ES");
        cases.forEach((client, expected) ->
                assertEquals(expected, LanguageService.selectLocale(client, "en_US", true, locales)));
    }

    @Test void installsAllBuiltInLanguagesAndTranslatesMessages() throws Exception {
        PluginConfig config = new PluginConfig();
        LanguageService language = new LanguageService(dataDirectory, LoggerFactory.getLogger("test"), () -> config);
        language.reload();

        for (String code : List.of("en_US", "fr_FR", "zh_CN", "zh_TW", "ja_JP", "ko_KR", "ru_RU", "de_DE", "es_ES")) {
            assertTrue(Files.isRegularFile(dataDirectory.resolve("lang").resolve(code + ".json")), code);
        }
        String english = language.format(null, "ban.empty");
        for (String code : List.of("zh_TW", "ja_JP", "ko_KR", "ru_RU", "de_DE", "es_ES")) {
            config.language.consoleLocale = code;
            String translated = language.format(null, "ban.empty");
            assertNotEquals(english, translated, code + " must not silently fall back to English");
            assertFalse(translated.isBlank(), code);
        }
    }

    @Test void choosesTraditionalChineseAndKeepsRegionalVariantsDistinct() {
        Set<String> locales = Set.of("en_US", "zh_CN", "zh_TW");
        assertEquals("zh_TW", LanguageService.selectLocale(Locale.TAIWAN, "en_US", true, locales));
        assertEquals("zh_TW", LanguageService.selectLocale(new Locale("zh", "HK"), "en_US", true, locales));
        assertEquals("zh_TW", LanguageService.selectLocale(new Locale("zh", "MO"), "en_US", true, locales));
        assertEquals("zh_TW", LanguageService.selectLocale(
                new Locale.Builder().setLanguage("zh").setScript("Hant").build(), "en_US", true, locales));
        assertEquals("zh_CN", LanguageService.selectLocale(Locale.CHINA, "en_US", true, locales));
        assertEquals("zh_CN", LanguageService.selectLocale(new Locale("zh", "SG"), "en_US", true, locales));
        assertEquals("zh_CN", LanguageService.selectLocale(
                new Locale.Builder().setLanguage("zh").setScript("Hans").build(), "en_US", true, locales));
        assertEquals("en_US", LanguageService.selectLocale(new Locale("zh"), "en_US", true, locales));
        assertEquals("en_US", LanguageService.selectLocale(Locale.TAIWAN, "en_US", false, locales));
        assertEquals("zh_HK", LanguageService.selectLocale(new Locale("zh", "HK"), "en_US", true,
                Set.of("en_US", "zh_CN", "zh_TW", "zh_HK")));
        assertEquals("zh_CN", LanguageService.selectLocale(Locale.TAIWAN, "en_US", true,
                Set.of("en_US", "zh_CN")), "older installations without Traditional Chinese still work");
    }

    @Test void respectsConfiguredDefaultWhenDetectionDisabled() {
        assertEquals("en_US", LanguageService.selectLocale(Locale.FRANCE, "en_US", false, available));
    }
    @Test void fallsBackForUnknownLanguage() {
        assertEquals("en_US", LanguageService.selectLocale(Locale.ITALY, "en_US", true, available));
    }
    @Test void ambiguousRegionalFallbackUsesConfiguredDefault() {
        assertEquals("en_US", LanguageService.selectLocale(new Locale("pt", "AO"), "en_US", true,
                Set.of("pt_BR", "pt_PT", "en_US")));
        assertEquals("pt_PT", LanguageService.selectLocale(new Locale("pt", "PT"), "en_US", true,
                Set.of("pt_BR", "pt_PT", "en_US")));
    }
    @Test void placeholderValuesAreNotRecursivelyInterpolated() {
        assertEquals("Hello {server} on lobby", LanguageService.interpolate("Hello {player} on {server}",
                Map.of("player", "{server}", "server", "lobby")));
    }
    @Test void invalidExternalPlaceholdersFallBackToBundledText() throws Exception {
        Path folder = Files.createDirectories(dataDirectory.resolve("lang"));
        Files.writeString(folder.resolve("fr_FR.json"), "{\"channel.changed\":\"Bonjour {invalid}\"}");
        PluginConfig config = new PluginConfig(); config.language.consoleLocale = "fr_FR"; config.normalize();
        LanguageService language = new LanguageService(dataDirectory, LoggerFactory.getLogger("test"), () -> config);
        language.reload();
        String value = language.format(null, "channel.changed", "channel", "global");
        assertTrue(value.contains("global"));
        assertFalse(value.contains("{invalid}"));
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
