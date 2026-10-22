package ddsmanager.i18n;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import ddsmanager.config.PluginConfig;
import ddsmanager.util.ServerLabelFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Player-localized messages stored as editable JSON files. */
public final class LanguageService {
    private static final Set<String> BUILTIN = Set.of("en_US", "fr_FR", "zh_CN", "zh_TW", "ja_JP", "ko_KR", "ru_RU", "de_DE", "es_ES");
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}");
    private final Path folder;
    private final Logger logger;
    private final Supplier<PluginConfig> configuration;
    private volatile Map<String, Map<String, String>> bundles = Map.of();

    public LanguageService(Path dataDirectory, Logger logger, Supplier<PluginConfig> configuration) {
        this.folder = dataDirectory.resolve("lang");
        this.logger = logger;
        this.configuration = configuration;
    }

    /** Reloads all languages and merges new bundled keys without losing customized values. */
    public synchronized void reload() {
        Map<String, Map<String, String>> next = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            Files.createDirectories(folder);
            for (String locale : BUILTIN) {
                Path file = folder.resolve(locale + ".json");
                if (!Files.exists(file)) {
                    try (InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".json")) {
                        if (in == null) { logger.warn("DDS language resource missing: {}", locale); continue; }
                        Files.copy(in, file);
                    } catch (IOException e) { logger.warn("Could not create DDS locale {}: {}", locale, e.getMessage()); }
                }
            }
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().matches("(?i)[a-z]{2,3}_[a-z]{2}\\.json"))
                     .forEach(f -> {
                         try {
                             String code = f.getFileName().toString().replaceFirst("\\.json$", "");
                             Map<String, String> custom = read(f);
                             Map<String, String> builtIn = builtin(code);
                             Map<String, String> reference = builtin("en_US");
                             Map<String, String> merged = new LinkedHashMap<>(builtIn);
                             custom.forEach((key, value) -> {
                                 String expected = reference.get(key);
                                 if (expected != null && !placeholders(expected).equals(placeholders(value))) {
                                     logger.warn("DDS locale {}: placeholders differ for key {}; bundled/fallback text used", code, key);
                                 } else {
                                     merged.put(key, value);
                                 }
                             });
                             next.put(code, Collections.unmodifiableMap(merged));
                             if (!builtIn.isEmpty() && !custom.keySet().containsAll(builtIn.keySet())) {
                                 try { writeWithNewKeys(f, custom, builtIn); }
                                 catch (IOException updateError) { logger.warn("Could not extend DDS translations in {}: {}", f.getFileName(), updateError.getMessage()); }
                             }
                         } catch (Exception e) { logger.warn("Invalid DDS language file {} (skipping): {}", f.getFileName(), e.getMessage()); }
                     });
            }
        } catch (IOException e) { logger.warn("Unable to load DDS language directory {}: {}", folder, e.getMessage()); }
        // If an existing file is invalid, preserve it and fall back to the packaged translations.
        for (String locale : BUILTIN) if (!next.containsKey(locale)) next.put(locale, builtin(locale));
        if (!next.isEmpty()) bundles = Collections.unmodifiableMap(next);
    }


    private Map<String, String> builtin(String code) {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + code + ".json")) {
            if (in != null) return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) { logger.error("Invalid bundled DDS locale {}", code, e); }
        return Map.of();
    }

    /** Writes missing keys only. On disk, existing customized values take precedence. */
    private static void writeWithNewKeys(Path path, Map<String, String> custom, Map<String, String> bundled) throws IOException {
        Map<String, String> output = new LinkedHashMap<>(custom);
        bundled.forEach(output::putIfAbsent);
        Path temporary = Files.createTempFile(path.getParent(), "dds-lang-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(output), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Map<String, String> read(Path path) throws IOException { return read(Files.readString(path, StandardCharsets.UTF_8)); }
    private static Map<String, String> read(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> item : object.entrySet()) {
            if (item.getValue().isJsonPrimitive() && item.getValue().getAsJsonPrimitive().isString())
                result.put(item.getKey(), item.getValue().getAsString());
        }
        return Collections.unmodifiableMap(result);
    }

    public String text(CommandSource receiver, String key) { return text(receiver, key, Map.of()); }
    public String text(CommandSource receiver, String key, String name, String value) { return text(receiver, key, Map.of(name, value)); }
    public String text(CommandSource receiver, String key, Map<String, String> values) {
        return interpolate(template(receiver, key), values);
    }

    private String template(CommandSource receiver, String key) {
        PluginConfig.Language language = configuration.get().language;
        Locale client = receiver instanceof Player player ? player.getEffectiveLocale() : null;
        String code = receiver instanceof Player
            ? selectLocale(client, language.defaultLocale, language.detectClientLanguage, bundles.keySet())
            : selectLocale(null, language.consoleLocale, false, bundles.keySet());
        String template = bundles.getOrDefault(code, Map.of()).get(key);
        if (template == null) template = bundles.getOrDefault(language.fallbackLocale, Map.of()).get(key);
        if (template == null) template = bundles.getOrDefault("en_US", Map.of()).getOrDefault(key, key);
        return template;
    }

    /** Plain-text interpolation for callers that do not request styled output. */
    private static Set<String> placeholders(String value) {
        Set<String> names = new HashSet<>();
        Matcher matcher = VARIABLE.matcher(value);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    static String interpolate(String template, Map<String, String> values) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    static String selectLocale(Locale client, String requestedDefault, boolean detect, Set<String> available) {
        if (detect && client != null) {
            String exact = client.getLanguage().toLowerCase(Locale.ROOT) + "_" + client.getCountry().toUpperCase(Locale.ROOT);
            if (available.contains(exact)) return exact;
            // When both Chinese variants exist, map HK/MO and traditional-script clients
            // to Traditional Chinese instead of falling back to an unrelated language.
            if ("zh".equals(client.getLanguage())) {
                String country = client.getCountry().toUpperCase(Locale.ROOT);
                boolean traditional = "Hant".equalsIgnoreCase(client.getScript())
                        || Set.of("TW", "HK", "MO").contains(country);
                boolean simplified = "Hans".equalsIgnoreCase(client.getScript())
                        || Set.of("CN", "SG").contains(country);
                if (traditional && available.contains("zh_TW")) return "zh_TW";
                if (simplified && available.contains("zh_CN")) return "zh_CN";
            }
            String prefix = client.getLanguage().toLowerCase(Locale.ROOT) + "_";
            String match = null;
            for (String candidate : available) {
                if (!candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
                if (match != null) { match = null; break; } // Ambiguous region variants: use configured default.
                match = candidate;
            }
            if (match != null) return match;
        }
        return available.contains(requestedDefault) ? requestedDefault : available.contains("en_US") ? "en_US" : requestedDefault;
    }

    /**
     * Renders a localized template with the same safe, restricted styling syntax as
     * DDS server labels (colors, decorations and gradients, but no clicks/hovers).
     * Placeholder values are escaped BEFORE parsing, so player-provided strings
     * cannot inject formatting or interactive components.
     */
    static Component renderTemplate(String template, Map<String, String> values, NamedTextColor fallbackColor) {
        Map<String, String> escaped = new LinkedHashMap<>();
        values.forEach((key, value) -> escaped.put(key, escapeMarkup(value)));
        String styled = interpolate(template, escaped);
        if (ServerLabelFormatter.isValidStyledText(styled, 4096, 4096)) {
            return ServerLabelFormatter.render(styled, fallbackColor);
        }
        // Malformed templates remain visible instead of throwing or hiding text.
        return Component.text(interpolate(template, values), fallbackColor);
    }

    /** ServerLabelFormatter treats backslash as an escape for <, > and \. */
    static String escapeMarkup(String value) {
        return value.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
    }

    private Component component(CommandSource receiver, String key, Map<String, String> values, NamedTextColor color) {
        return Component.text("[DDS] ", NamedTextColor.AQUA)
                .append(renderTemplate(template(receiver, key), values, color));
    }


    public static Map<String, String> vars(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected alternating names and values");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
        return result;
    }

    public String format(CommandSource source, String key, Object... pairs) {
        return text(source, key, vars(pairs));
    }
    public Component styled(CommandSource source, String key, Object... pairs) {
        return renderTemplate(template(source, key), vars(pairs), NamedTextColor.WHITE);
    }
    public Component info(CommandSource source, String key, Map<String, String> values) {
        return component(source, key, values, NamedTextColor.WHITE);
    }
    public Component success(CommandSource source, String key, Map<String, String> values) {
        return component(source, key, values, NamedTextColor.GREEN);
    }
    public Component error(CommandSource source, String key, Map<String, String> values) {
        return component(source, key, values, NamedTextColor.RED);
    }

    public Component info(CommandSource receiver, String key) { return component(receiver, key, Map.of(), NamedTextColor.WHITE); }
    public Component info(CommandSource receiver, String key, String name, String value) { return component(receiver, key, Map.of(name, value), NamedTextColor.WHITE); }
    public Component success(CommandSource receiver, String key, String name, String value) { return component(receiver, key, Map.of(name, value), NamedTextColor.GREEN); }
    public Component error(CommandSource receiver, String key) { return component(receiver, key, Map.of(), NamedTextColor.RED); }
    public Component error(CommandSource receiver, String key, String name, String value) { return component(receiver, key, Map.of(name, value), NamedTextColor.RED); }
}
