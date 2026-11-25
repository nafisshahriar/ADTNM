/*
 * DtnmaM0dConservativeRouter.java
 * Comparison B M0d preset: Always-conservative static configuration from boot
 * (P1: L=2, INGRESS_THROTTLE_BULK, backpressure enabled, ACK pruning enabled,
 * no local adaptation, ignores remote CTRL_UPDATE bundles).
 */
package routing;

import core.Settings;

public class DtnmaM0dConservativeRouter extends DtnmaRouter {

    public DtnmaM0dConservativeRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M0_STATIC;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        this.config.epidemicControlFlooding = false;
        this.config.staticQuota = 2;
        this.config.staticIngressMode = DtnmaRouter.INGRESS_THROTTLE_BULK;
        this.config.staticBackpressure = true;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM0dConservativeRouter(DtnmaM0dConservativeRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM0dConservativeRouter(this);
    }
}
