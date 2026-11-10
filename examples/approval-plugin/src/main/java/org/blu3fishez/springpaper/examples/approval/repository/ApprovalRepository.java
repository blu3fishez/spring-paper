package org.blu3fishez.springpaper.examples.approval.repository;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** Demo approvals last for this application instance. A production consumer supplies durable approval storage. */
@Repository
public final class ApprovalRepository {
    private final Set<UUID> approved = ConcurrentHashMap.newKeySet();
    public boolean approved(UUID playerId) { return approved.contains(playerId); }
    public void approve(UUID playerId) { approved.add(playerId); }
    public void remove(UUID playerId) { approved.remove(playerId); }
}
