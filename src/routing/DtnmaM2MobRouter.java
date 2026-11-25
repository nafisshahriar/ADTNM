/*
 * DtnmaM2MobRouter.java
 * Joint Mobility-Aware Distributed Autonomic Resource Coordination preset:
 * uses first-class encounter rate, contact duration, ICT, and energy-drain
 * TTD inside DtnmaAgent within the deterministic Safety Shield (no RL).
 */
package routing;

import core.Settings;

public class DtnmaM2MobRouter extends DtnmaRouter {

    public DtnmaM2MobRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M2_MOBILITY_AWARE;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM2MobRouter(DtnmaM2MobRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM2MobRouter(this);
    }
}
