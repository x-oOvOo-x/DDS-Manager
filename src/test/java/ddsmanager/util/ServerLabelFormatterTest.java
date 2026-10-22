package ddsmanager.util;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ServerLabelFormatterTest {
    @Test void parsesSupportedStylesWithoutMiniMessageRuntime() {
        String gradient = "<gradient:#55ff55:#00ffaa><bold>◆ S</bold></gradient>";
        assertTrue(ServerLabelFormatter.isValid(gradient));
        assertEquals("◆ S", ServerLabelFormatter.plainText(gradient));
        assertEquals(3, ServerLabelFormatter.visibleCodePoints(gradient));
        assertDoesNotThrow(() -> ServerLabelFormatter.render(gradient, NamedTextColor.GRAY));
        assertTrue(ServerLabelFormatter.isValid("<rainbow>昵称</rainbow>"));
        assertTrue(ServerLabelFormatter.isValid("<font:dds:icons>\uE001</font> 生存"));
    }

    @Test void rejectsInteractiveUnknownMalformedAndOverlongNicknames() {
        assertFalse(ServerLabelFormatter.isValid("<click:run_command:/server x>X</click>"));
        assertFalse(ServerLabelFormatter.isValid("<hover:show_text:x>X</hover>"));
        assertFalse(ServerLabelFormatter.isValid("<unknown>X</unknown>"));
        assertFalse(ServerLabelFormatter.isValid("<red>X"));
        assertFalse(ServerLabelFormatter.isValidStyledText("12345678901234567", 128, 16));
        assertTrue(ServerLabelFormatter.isValidStyledText("<bold>1234567890123456</bold>", 128, 16));
    }
    @org.junit.jupiter.api.Test void rejectsNonFiniteColorPhases() {
        org.junit.jupiter.api.Assertions.assertFalse(ServerLabelFormatter.isValid("<rainbow:NaN>X</rainbow>"));
        org.junit.jupiter.api.Assertions.assertFalse(ServerLabelFormatter.isValid("<rainbow:invalid>X</rainbow>"));
        org.junit.jupiter.api.Assertions.assertFalse(ServerLabelFormatter.isValid("<gradient:red:blue:Infinity>X</gradient>"));
    }
}
