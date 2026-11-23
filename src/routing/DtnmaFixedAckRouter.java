/*
 * DtnmaFixedAckRouter.java
 * Component Ladder Step 3: Priority protection + Delayed Delivery ACK Vaccine Pruning
 * + Cognitive Focus handoff at fixed L=6 WITHOUT declarative SRL adaptation.
 */
package routing;

import core.Settings;

public class DtnmaFixedAckRouter extends DtnmaStaticAckRouter {

    private static final String FIXED_L6_RULE =
            "RULE: ARI://DTNMA/SRL/FIXED_L6 | PRIORITY: 10 | DESC: Fixed non-adaptive spray quota L=6\n" +
            "IF: (EDD_QUEUE_RATIO >= 0.0)\n" +
            "THEN: CTRL_SET_SPRAY_QUOTA(6); CTRL_SET_INGRESS_MODE(0); CTRL_SET_TELEMETRY_INTERVAL(60); CTRL_SET_BACKPRESSURE(false)\n";

    public DtnmaFixedAckRouter(Settings s) {
        super(s);
        loadRulesFromString(FIXED_L6_RULE);
    }

    protected DtnmaFixedAckRouter(DtnmaFixedAckRouter r) {
        super(r);
    }

    @Override
    public boolean supportsDynamicControlReconfig() {
        return false;
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaFixedAckRouter(this);
    }
}
