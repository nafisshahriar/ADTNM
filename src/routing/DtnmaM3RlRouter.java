/*
 * DtnmaM3RlRouter.java
 * Comparison B M3 preset: Full Autonomic DTNMA (Local SRL + Safety Shield
 * + Shielded CMDP RL + Density-Adaptive Gossip + Remote Policy Overlays).
 */
package routing;

import core.Settings;

public class DtnmaM3RlRouter extends DtnmaRouter {

    public DtnmaM3RlRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M3_AUTONOMIC_RL;
        this.intelligenceMode = IntelligenceMode.SHIELDED_CMDP_RL;
        this.gossipEnabled = true;
        this.ackFeedbackEnabled = true;
        this.config.densityAdaptiveGossip = true;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM3RlRouter(DtnmaM3RlRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM3RlRouter(this);
    }
}
