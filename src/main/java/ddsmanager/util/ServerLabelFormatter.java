package ddsmanager.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.*;

public final class ServerLabelFormatter {
    private ServerLabelFormatter() {}

    public static Component render(String template, TextColor fallbackColor) {
        String value = value(template); Parsed parsed = parse(value);
        if (!parsed.valid) return Component.text(value, fallbackColor);
        Component out = Component.empty();
        for (Glyph g : parsed.glyphs) {
            Component part = Component.text(g.text, g.color == null ? fallbackColor : g.color);
            for (TextDecoration d : g.decorations) part = part.decorate(d);
            if (g.font != null) part = part.font(g.font);
            out = out.append(part);
        }
        return out;
    }

    public static String plainText(String template) {
        String value = value(template); Parsed parsed = parse(value);
        if (!parsed.valid) return value;
        StringBuilder out = new StringBuilder(); parsed.glyphs.forEach(g -> out.append(g.text));
        return out.isEmpty() ? "?" : out.toString();
    }

    public static boolean isValid(String template) { return isValidStyledText(template, 128, Integer.MAX_VALUE); }
    public static boolean isValidStyledText(String template, int maxTemplateCodePoints, int maxVisibleCodePoints) {
        if (template == null) return false; String value = template.trim(), lower = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maxTemplateCodePoints || value.codePoints().anyMatch(Character::isISOControl)
                || lower.contains("<click") || lower.contains("<hover") || lower.contains("<insertion")) return false;
        Parsed parsed = parse(value); return parsed.valid && parsed.glyphs.size() >= 1 && parsed.glyphs.size() <= maxVisibleCodePoints;
    }
    public static int visibleCodePoints(String template) { if (template == null || template.isBlank()) return 0; Parsed p = parse(template.trim()); return p.valid ? p.glyphs.size() : -1; }

    private static String value(String template) { return template == null || template.isBlank() ? "?" : template.trim(); }

    private static Parsed parse(String value) {
        List<Glyph> glyphs = new ArrayList<>(); List<Effect> effects = new ArrayList<>(); Deque<Ctx> stack = new ArrayDeque<>(); stack.push(new Ctx("", new StyleState(), null, 0, 0));
        boolean valid = true;
        for (int i = 0; i < value.length() && valid;) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length() && "<>\\".indexOf(value.charAt(i + 1)) >= 0) { add(glyphs, stack.peek().style, value.substring(i + 1, i + 2)); i += 2; continue; }
            if (ch != '<') { int cp = value.codePointAt(i); add(glyphs, stack.peek().style, new String(Character.toChars(cp))); i += Character.charCount(cp); continue; }
            int end = value.indexOf('>', i + 1); if (end < 0) { valid = false; break; }
            String raw = value.substring(i + 1, end).trim(); if (raw.isEmpty()) { valid = false; break; }
            if (raw.charAt(0) == '/') {
                String close = closeKey(raw.substring(1).trim()); Ctx top = stack.peek();
                if (stack.size() == 1 || !top.closeKey.equals(close)) valid = false;
                else { stack.pop(); if (top.effect != null) effects.add(new Effect(top.start, glyphs.size(), top.depth, top.effect)); }
            } else {
                Open open = open(raw, stack.peek().style, stack.size());
                if (!open.valid) valid = false;
                else if (open.reset) {
                    while (stack.size() > 1) { Ctx top = stack.pop(); if (top.effect != null) effects.add(new Effect(top.start, glyphs.size(), top.depth, top.effect)); }
                    stack.clear(); stack.push(new Ctx("", new StyleState(), null, 0, 0));
                } else stack.push(new Ctx(open.closeKey, open.style, open.effect, glyphs.size(), stack.size()));
            }
            i = end + 1;
        }
        if (stack.size() != 1) valid = false;
        if (valid) { effects.sort(Comparator.comparingInt(e -> e.depth)); effects.forEach(e -> apply(glyphs, e)); }
        return new Parsed(valid, glyphs);
    }

    private static void add(List<Glyph> out, StyleState style, String text) { out.add(new Glyph(text, style.color, style.colorDepth, copy(style.decorations), style.font)); }
    private static EnumSet<TextDecoration> copy(EnumSet<TextDecoration> set) { return set.isEmpty() ? EnumSet.noneOf(TextDecoration.class) : EnumSet.copyOf(set); }

    private static Open open(String raw, StyleState base, int depth) {
        int colon = raw.indexOf(':'); String name = (colon < 0 ? raw : raw.substring(0, colon)).trim().toLowerCase(Locale.ROOT), arg = colon < 0 ? "" : raw.substring(colon + 1).trim();
        if ("reset".equals(name) && arg.isEmpty()) return Open.resetOpen();
        StyleState style = base.copy(); TextColor shorthand = colon < 0 ? color(name) : null;
        if (shorthand != null) { style.color = shorthand; style.colorDepth = depth; return Open.style("color:" + name, style); }
        if ("color".equals(name)) { TextColor c = color(arg); if (c == null) return Open.invalid(); style.color = c; style.colorDepth = depth; return Open.style("color", style); }
        TextDecoration decoration = decoration(name);
        if (decoration != null && arg.isEmpty()) { style.decorations.add(decoration); return Open.style(decorationName(name), style); }
        if ("font".equals(name) && !arg.isEmpty()) { try { style.font = Key.key(arg); return Open.style("font", style); } catch (RuntimeException ignored) { return Open.invalid(); } }
        if ("gradient".equals(name)) { EffectSpec effect = effect(arg, false); return effect == null ? Open.invalid() : Open.effect("gradient", style, effect); }
        if ("rainbow".equals(name)) { Double phase = arg.isEmpty() ? Double.valueOf(0D) : number(arg); return phase == null ? Open.invalid() : Open.effect("rainbow", style, new EffectSpec(EffectType.RAINBOW, List.of(), phase)); }
        if ("transition".equals(name)) { EffectSpec effect = effect(arg, true); if (effect == null) return Open.invalid(); style.color = interpolate(effect.colors, clamp((effect.phase + 1D) / 2D)); style.colorDepth = depth; return Open.style("transition", style); }
        return Open.invalid();
    }

    private static EffectSpec effect(String arg, boolean transition) {
        if (arg.isBlank()) return null; List<String> parts = new ArrayList<>(Arrays.asList(arg.split(":"))); double phase = 0D;
        if (parts.size() >= 3) { Double p = number(parts.get(parts.size() - 1)); if (p != null) { phase = p; parts.remove(parts.size() - 1); } }
        List<TextColor> colors = new ArrayList<>(); for (String part : parts) { TextColor c = color(part.trim()); if (c == null) return null; colors.add(c); }
        if (colors.size() < 2 || transition && (phase < -1D || phase > 1D)) return null;
        return new EffectSpec(transition ? EffectType.TRANSITION : EffectType.GRADIENT, colors, phase);
    }

    private static String closeKey(String raw) {
        String name = raw.toLowerCase(Locale.ROOT); if (color(name) != null) return "color:" + name;
        String decoration = decorationName(name); return decoration == null ? name : decoration;
    }
    private static String decorationName(String name) { return switch (name) { case "b", "bold" -> "bold"; case "i", "em", "italic" -> "italic"; case "u", "underlined" -> "underlined"; case "st", "strikethrough" -> "strikethrough"; case "obf", "obfuscated" -> "obfuscated"; default -> null; }; }
    private static TextDecoration decoration(String name) {
        String key = decorationName(name); if (key == null) return null;
        return switch (key) { case "bold" -> TextDecoration.BOLD; case "italic" -> TextDecoration.ITALIC; case "underlined" -> TextDecoration.UNDERLINED; case "strikethrough" -> TextDecoration.STRIKETHROUGH; case "obfuscated" -> TextDecoration.OBFUSCATED; default -> null; };
    }

    private static TextColor color(String raw) {
        if (raw == null) return null; String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.matches("#[0-9a-f]{6}")) { int rgb = Integer.parseInt(v.substring(1), 16); return TextColor.color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255); }
        return switch (v) {
            case "black" -> NamedTextColor.BLACK; case "dark_blue" -> NamedTextColor.DARK_BLUE; case "dark_green" -> NamedTextColor.DARK_GREEN; case "dark_aqua" -> NamedTextColor.DARK_AQUA;
            case "dark_red" -> NamedTextColor.DARK_RED; case "dark_purple" -> NamedTextColor.DARK_PURPLE; case "gold" -> NamedTextColor.GOLD; case "gray", "grey" -> NamedTextColor.GRAY;
            case "dark_gray", "dark_grey" -> NamedTextColor.DARK_GRAY; case "blue" -> NamedTextColor.BLUE; case "green" -> NamedTextColor.GREEN; case "aqua" -> NamedTextColor.AQUA;
            case "red" -> NamedTextColor.RED; case "light_purple" -> NamedTextColor.LIGHT_PURPLE; case "yellow" -> NamedTextColor.YELLOW; case "white" -> NamedTextColor.WHITE; default -> null;
        };
    }

    private static void apply(List<Glyph> glyphs, Effect effect) {
        int count = effect.end - effect.start; if (count <= 0) return;
        for (int i = effect.start; i < effect.end; i++) {
            Glyph g = glyphs.get(i); if (effect.depth < g.colorDepth) continue; double t = count == 1 ? .5D : (i - effect.start) / (double) (count - 1); TextColor c;
            if (effect.spec.type == EffectType.RAINBOW) c = rainbow(t + effect.spec.phase);
            else if (effect.spec.type == EffectType.TRANSITION) c = interpolate(effect.spec.colors, clamp((effect.spec.phase + 1D) / 2D));
            else c = interpolate(effect.spec.colors, clamp(t + effect.spec.phase / 2D));
            g.color = c; g.colorDepth = effect.depth;
        }
    }
    private static TextColor interpolate(List<TextColor> colors, double t) {
        if (colors.isEmpty()) return NamedTextColor.WHITE; double scaled = t * (colors.size() - 1); int i = Math.min(colors.size() - 2, (int) Math.floor(scaled)); double f = scaled - i; TextColor a = colors.get(i), b = colors.get(i + 1);
        return TextColor.color(lerp(a.red(), b.red(), f), lerp(a.green(), b.green(), f), lerp(a.blue(), b.blue(), f));
    }
    private static int lerp(int a, int b, double t) { return (int) Math.round(a + (b - a) * t); }
    private static TextColor rainbow(double h) {
        h = h - Math.floor(h); double x = h * 6D; int sector = (int) x; double f = x - sector, q = 1D - f;
        double r = 0, g = 0, b = 0; switch (sector) { case 0 -> { r = 1; g = f; } case 1 -> { r = q; g = 1; } case 2 -> { g = 1; b = f; } case 3 -> { g = q; b = 1; } case 4 -> { r = f; b = 1; } default -> { r = 1; b = q; } }
        return TextColor.color((int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255));
    }
    private static double clamp(double v) { return Math.max(0D, Math.min(1D, v)); }
    private static Double number(String value) { try { double parsed = Double.parseDouble(value.trim()); return Double.isFinite(parsed) ? parsed : null; } catch (NumberFormatException ignored) { return null; } }

    private static final class StyleState {
        private TextColor color; private int colorDepth = -1; private EnumSet<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class); private Key font;
        private StyleState copy() { StyleState s = new StyleState(); s.color = color; s.colorDepth = colorDepth; s.decorations = ServerLabelFormatter.copy(decorations); s.font = font; return s; }
    }
    private static final class Glyph {
        private final String text; private TextColor color; private int colorDepth; private final EnumSet<TextDecoration> decorations; private final Key font;
        private Glyph(String text, TextColor color, int colorDepth, EnumSet<TextDecoration> decorations, Key font) { this.text = text; this.color = color; this.colorDepth = colorDepth; this.decorations = decorations; this.font = font; }
    }
    private record Parsed(boolean valid, List<Glyph> glyphs) {}
    private record Ctx(String closeKey, StyleState style, EffectSpec effect, int start, int depth) {}
    private record Effect(int start, int end, int depth, EffectSpec spec) {}
    private record EffectSpec(EffectType type, List<TextColor> colors, double phase) {}
    private enum EffectType { GRADIENT, RAINBOW, TRANSITION }
    private record Open(boolean valid, boolean reset, String closeKey, StyleState style, EffectSpec effect) {
        private static Open invalid() { return new Open(false, false, "", null, null); } private static Open resetOpen() { return new Open(true, true, "", null, null); }
        private static Open style(String key, StyleState style) { return new Open(true, false, key, style, null); } private static Open effect(String key, StyleState style, EffectSpec effect) { return new Open(true, false, key, style, effect); }
    }
}
