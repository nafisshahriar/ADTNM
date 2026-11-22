/*
 * DtnmaShieldedPassiveRLRouter.java
 * OppNDA-compatible ablation preset: Shielded Expected SARSA with passive mask only
 * (NO Safe-Anchored Counterfactual Shaping), isolating counterfactual safety internalization
 * from decaying epsilon exploration.
 */
package routing;

import core.Settings;

public class DtnmaShieldedPassiveRLRouter extends DtnmaRouter {

    public DtnmaShieldedPassiveRLRouter(Settings s) {
        super(s);
        this.intelligenceMode = IntelligenceMode.SHIELDED_RL_PASSIVE;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = false;
        this.config.intelligenceMode = this.intelligenceMode;
        this.config.gossipEnabled = false;
        this.config.ackFeedbackEnabled = false;
    }

    protected DtnmaShieldedPassiveRLRouter(DtnmaShieldedPassiveRLRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaShieldedPassiveRLRouter(this);
    }
}
