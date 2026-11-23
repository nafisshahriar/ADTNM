/*
 * DtnmaM2SrlRouter.java
 * Comparison B M2 preset: Autonomic local declarative SRL engine + Safety Shield
 * + manager-pushed remote policy overlays (no local RL or gossip).
 */
package routing;

import core.Settings;

public class DtnmaM2SrlRouter extends DtnmaRouter {

    public DtnmaM2SrlRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM2SrlRouter(DtnmaM2SrlRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM2SrlRouter(this);
    }
}
