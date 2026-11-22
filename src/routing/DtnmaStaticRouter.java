/*
 * DtnmaStaticRouter.java
 * OppNDA-compatible ablation preset: RFC 9675 Declarative Static Rules only.
 */
package routing;

import core.Settings;

public class DtnmaStaticRouter extends DtnmaRouter {

    public DtnmaStaticRouter(Settings s) {
        super(s);
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = false;
    }

    protected DtnmaStaticRouter(DtnmaStaticRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaStaticRouter(this);
    }
}
