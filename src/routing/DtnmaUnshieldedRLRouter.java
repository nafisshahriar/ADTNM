/*
 * DtnmaUnshieldedRLRouter.java
 * OppNDA-compatible ablation preset: Unconstrained CMDP RL without Safety Shield.
 */
package routing;

import core.Settings;

public class DtnmaUnshieldedRLRouter extends DtnmaRouter {

    public DtnmaUnshieldedRLRouter(Settings s) {
        super(s);
        this.intelligenceMode = IntelligenceMode.UNSHIELDED_RL;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = false;
        this.config.intelligenceMode = this.intelligenceMode;
        this.config.gossipEnabled = false;
        this.config.ackFeedbackEnabled = false;
    }

    protected DtnmaUnshieldedRLRouter(DtnmaUnshieldedRLRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaUnshieldedRLRouter(this);
    }
}
