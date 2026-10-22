package ddsmanager.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

final class DdsCommandConfirmationTest {
    @Test void consoleSeesExecutablePlainTextWithoutClickEvent() {
        CommandSource console = (CommandSource) Proxy.newProxyInstance(CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class}, (proxy, method, args) -> null);

        Component instruction = DdsCommand.confirmationAction(console, "abc123", Component.text("[Confirm]"));

        assertInstanceOf(TextComponent.class, instruction);
        assertEquals("dds confirm abc123", ((TextComponent) instruction).content());
        assertNull(instruction.clickEvent());
    }

    @Test void playerStillGetsClickableConfirmation() {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> null);

        Component button = DdsCommand.confirmationAction(player, "abc123", Component.text("[Confirm]"));

        assertEquals(ClickEvent.runCommand("/dds confirm abc123"), button.clickEvent());
        assertEquals("[Confirm]", ((TextComponent) button).content());
    }
}
