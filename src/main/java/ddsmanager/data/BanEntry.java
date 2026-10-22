package ddsmanager.data;

import java.util.UUID;

public final class BanEntry {
    public String uuid = "", lastKnownName = "", reason = "", createdBy = "";
    public long createdAtEpochMs;

    public UUID uniqueId() { return UUID.fromString(uuid); }

    public BanEntry snapshot() {
        BanEntry copy = new BanEntry();
        copy.uuid = uuid;
        copy.lastKnownName = lastKnownName;
        copy.reason = reason;
        copy.createdBy = createdBy;
        copy.createdAtEpochMs = createdAtEpochMs;
        return copy;
    }
}
