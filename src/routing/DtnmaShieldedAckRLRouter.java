/*
 * DtnmaShieldedAckRLRouter.java
 * OppNDA-compatible ablation preset: Shielded CMDP RL + Delayed ACK Feedback
 * WITHOUT peer weight gossip (isolates RL contribution from opportunistic gossip).
 */
package routing;

import core.Settings;

public class DtnmaShieldedAckRLRouter extends DtnmaRouter {

    public DtnmaShieldedAckRLRouter(Settings s) {
        super(s);
        this.intelligenceMode = IntelligenceMode.SHIELDED_CMDP_RL;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        this.config.intelligenceMode = this.intelligenceMode;
        this.config.gossipEnabled = false;
        this.config.ackFeedbackEnabled = true;
    }

    protected DtnmaShieldedAckRLRouter(DtnmaShieldedAckRLRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaShieldedAckRLRouter(this);
    }
}
