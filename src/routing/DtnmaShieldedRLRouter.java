/*
 * DtnmaShieldedRLRouter.java
 * OppNDA-compatible ablation preset: Shielded CMDP RL (Single-Node, No Gossip).
 */
package routing;

import core.Settings;

public class DtnmaShieldedRLRouter extends DtnmaRouter {

    public DtnmaShieldedRLRouter(Settings s) {
        super(s);
        this.intelligenceMode = IntelligenceMode.SHIELDED_CMDP_RL;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = false;
        this.config.intelligenceMode = this.intelligenceMode;
        this.config.gossipEnabled = false;
        this.config.ackFeedbackEnabled = false;
    }

    protected DtnmaShieldedRLRouter(DtnmaShieldedRLRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaShieldedRLRouter(this);
    }
}
