package ddsmanager.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class Messages {
    private Messages() { }
    public static Component info(String text) { return prefix().append(Component.text(text)); }
    public static Component success(String text) { return prefix().append(Component.text(text, NamedTextColor.GREEN)); }
    public static Component error(String text) { return prefix().append(Component.text(text, NamedTextColor.RED)); }
    private static Component prefix() { return Component.text("[DDS] ", NamedTextColor.AQUA); }
}
