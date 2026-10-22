package ddsmanager.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftProtocolSupportTest {
    @Test void supports114Through26xContinuouslyAndTracksBaselines() {
        for (int protocol : new int[]{477, 498, 578, 735, 754, 758, 763, 765, 766, 774, 775, 776, 777}) {
            assertTrue(MinecraftProtocolSupport.supports(protocol)); assertTrue(MinecraftProtocolSupport.verified(protocol));
        }
        assertFalse(MinecraftProtocolSupport.supports(476));
        for (int protocol : new int[]{478, 499, 573, 736, 759, 760, 767, 771, 772, 773, 778}) {
            assertTrue(MinecraftProtocolSupport.supports(protocol)); assertFalse(MinecraftProtocolSupport.verified(protocol));
        }
    }
}
