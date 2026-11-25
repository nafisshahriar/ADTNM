/*
 * DtnmaM2MobReplicaOnlyRouter.java
 * Isolated ablation preset of M2_MOBILITY_AWARE: enables contact-duration and
 * encounter-rate replication scaling only (disables energy TTD horizon
 * throttling and ICT buffer scaling).
 */
package routing;

import core.Settings;

public class DtnmaM2MobReplicaOnlyRouter extends DtnmaM2MobRouter {

    public DtnmaM2MobReplicaOnlyRouter(Settings s) {
        super(s);
        this.config.mobAblationMode = "REPLICA_ONLY";
    }

    protected DtnmaM2MobReplicaOnlyRouter(DtnmaM2MobReplicaOnlyRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM2MobReplicaOnlyRouter(this);
    }
}
