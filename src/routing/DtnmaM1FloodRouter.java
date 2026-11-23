/*
 * DtnmaM1FloodRouter.java
 * Comparison B M1-F preset: Remote-only control over DTN with epidemic
 * control flooding enabled for CTRL_UPDATE dissemination.
 */
package routing;

import core.Settings;

public class DtnmaM1FloodRouter extends DtnmaRouter {

    public DtnmaM1FloodRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M1_REMOTE_ONLY;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        this.config.epidemicControlFlooding = true;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM1FloodRouter(DtnmaM1FloodRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM1FloodRouter(this);
    }
}
