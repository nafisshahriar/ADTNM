/*
 * DtnmaM2MobBufferOnlyRouter.java
 * Isolated ablation preset of M2_MOBILITY_AWARE: enables ICT-scaled buffer
 * protection threshold only (disables energy TTD horizon throttling and
 * contact-duration replication scaling).
 */
package routing;

import core.Settings;

public class DtnmaM2MobBufferOnlyRouter extends DtnmaM2MobRouter {

    public DtnmaM2MobBufferOnlyRouter(Settings s) {
        super(s);
        this.config.mobAblationMode = "BUFFER_ONLY";
    }

    protected DtnmaM2MobBufferOnlyRouter(DtnmaM2MobBufferOnlyRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM2MobBufferOnlyRouter(this);
    }
}
