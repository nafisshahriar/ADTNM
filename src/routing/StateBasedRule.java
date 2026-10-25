/*
 * StateBasedRule.java
 * Represents an RFC 9675 State-Based Rule (SRL) in the DTNMA Autonomy Model.
 * Evaluates: IF <PredicateExpression> THEN <ControlActions>
 * Maintains causal audit trail for explainable decision auditing.
 */
package routing;

import java.util.ArrayList;
import java.util.List;

public class StateBasedRule {

    private String ari; // Autonomous Resource Identifier
    private String description;
    private PredicateExpression predicate;
    private List<ControlAction> actions;
    private int priority; // Higher priority rules evaluated first

    public StateBasedRule(String ari, String description, int priority, PredicateExpression predicate) {
        this.ari = ari;
        this.description = description;
        this.priority = priority;
        this.predicate = predicate;
        this.actions = new ArrayList<ControlAction>();
    }

    public void addAction(ControlAction action) {
        this.actions.add(action);
    }

    public boolean isTriggered(EddTelemetrySampler sampler) {
        if (predicate == null) {
            return false;
        }
        return predicate.evaluate(sampler);
    }

    public void execute(DtnmaRouter router) {
        for (ControlAction action : actions) {
            action.execute(router);
        }
    }

    public String getAri() {
        return ari;
    }

    public String getDescription() {
        return description;
    }

    public int getPriority() {
        return priority;
    }

    public PredicateExpression getPredicate() {
        return predicate;
    }

    public List<ControlAction> getActions() {
        return actions;
    }

    public String getLastExplanation() {
        return predicate != null ? predicate.getLastExplanation() : "N/A";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SRL[").append(ari).append("] (Priority ").append(priority).append("): ");
        sb.append("IF ").append(predicate.toString()).append(" THEN { ");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(actions.get(i).toString());
        }
        sb.append(" }");
        return sb.toString();
    }
}
