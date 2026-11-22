/*
 * DtnmaGossipRLRouter.java
 * OppNDA-compatible ablation preset: Shielded CMDP RL + Mobility-Class Gossip Consensus.
 */
package routing;

import core.Settings;

public class DtnmaGossipRLRouter extends DtnmaRouter {

    public DtnmaGossipRLRouter(Settings s) {
        super(s);
        this.intelligenceMode = IntelligenceMode.SHIELDED_CMDP_RL;
        this.gossipEnabled = true;
        this.ackFeedbackEnabled = false;
    }

    protected DtnmaGossipRLRouter(DtnmaGossipRLRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaGossipRLRouter(this);
    }
}
