package ddsmanager.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LanguageCatalogTest {
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}");

    private static JsonObject read(String locale) throws Exception {
        try (var stream = LanguageCatalogTest.class.getResourceAsStream("/lang/" + locale + ".json")) {
            assertNotNull(stream, "Missing language " + locale);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = VARIABLE.matcher(value);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    @Test void allLanguagePacksHaveMatchingKeysAndVariables() throws Exception {
        JsonObject english = read("en_US");
        assertTrue(english.size() >= 100);
        for (String locale : new String[]{"fr_FR", "zh_CN", "zh_TW", "ja_JP", "ko_KR", "ru_RU", "de_DE", "es_ES"}) {
            JsonObject translated = read(locale);
            assertEquals(english.keySet(), translated.keySet(), "Different keys in " + locale);
            for (Map.Entry<String, com.google.gson.JsonElement> entry : english.entrySet()) {
                String key = entry.getKey();
                assertTrue(translated.get(key).isJsonPrimitive(), locale + ":" + key);
                assertEquals(placeholders(entry.getValue().getAsString()), placeholders(translated.get(key).getAsString()),
                        "Different named placeholders in " + locale + ":" + key);
            }
        }
    }
}
