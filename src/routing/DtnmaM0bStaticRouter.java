/*
 * DtnmaM0bStaticRouter.java
 * Comparison B M0b preset: Static agent configuration only (critical buffer
 * protection, HoL priority, and ACK pruning active; no local SRL/RL adaptation;
 * ignores remote CTRL_UPDATE bundles).
 */
package routing;

import core.Settings;

public class DtnmaM0bStaticRouter extends DtnmaRouter {

    public DtnmaM0bStaticRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M0_STATIC;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        this.config.epidemicControlFlooding = false;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM0bStaticRouter(DtnmaM0bStaticRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM0bStaticRouter(this);
    }
}
