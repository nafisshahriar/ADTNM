/*
 * DtnmaM1RemoteRouter.java
 * Comparison B M1 preset: Centralized remote-manager control over DTN only
 * (adopts manager-pushed CTRL_UPDATE bundles via epidemic control flooding;
 * no local SRL reflexes or local RL intelligence).
 */
package routing;

import core.Settings;

public class DtnmaM1RemoteRouter extends DtnmaRouter {

    public DtnmaM1RemoteRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M1_REMOTE_ONLY;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM1RemoteRouter(DtnmaM1RemoteRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM1RemoteRouter(this);
    }
}
