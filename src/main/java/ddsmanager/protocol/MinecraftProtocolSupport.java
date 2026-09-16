package ddsmanager.protocol;

import com.velocitypowered.api.network.ProtocolVersion;

import java.util.Set;

/** Product support boundary only; packet IDs and layouts are owned by Velocity's active registry. */
public final class MinecraftProtocolSupport {
    private static final int MIN_SUPPORTED = 477; // Minecraft 1.14
    private static final Set<Integer> VERIFIED = Set.of(477, 498, 578, 735, 754, 758, 763, 765, 766, 774, 775, 776, 777);

    private MinecraftProtocolSupport() {}

    public static boolean supports(ProtocolVersion version) { return version != null && supports(version.getProtocol()); }
    static boolean supports(int protocol) { return protocol >= MIN_SUPPORTED; }
    static boolean verified(int protocol) { return VERIFIED.contains(protocol); }
}
