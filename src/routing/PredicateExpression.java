/*
 * PredicateExpression.java
 * Part of RFC 9675 DTNMA Predicate Autonomy Engine.
 * Evaluates conditional expressions and generates explainable audit explanations.
 */
package routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PredicateExpression {

    public enum Operator {
        GREATER_THAN, LESS_THAN, GREATER_EQUAL, LESS_EQUAL, EQUAL, NOT_EQUAL
    }

    public static class Clause {
        private String eddName;
        private Operator op;
        private double threshold;
        private double lastSampledValue = 0.0;
        private boolean lastResult = false;

        public Clause(String eddName, Operator op, double threshold) {
            this.eddName = eddName;
            this.op = op;
            this.threshold = threshold;
        }

        public boolean evaluate(EddTelemetrySampler sampler) {
            this.lastSampledValue = sampler.getEddValue(eddName);
            switch (op) {
                case GREATER_THAN:  lastResult = lastSampledValue > threshold; break;
                case LESS_THAN:     lastResult = lastSampledValue < threshold; break;
                case GREATER_EQUAL: lastResult = lastSampledValue >= threshold; break;
                case LESS_EQUAL:    lastResult = lastSampledValue <= threshold; break;
                case EQUAL:         lastResult = Math.abs(lastSampledValue - threshold) < 1e-6; break;
                case NOT_EQUAL:     lastResult = Math.abs(lastSampledValue - threshold) >= 1e-6; break;
                default:            lastResult = false; break;
            }
            return lastResult;
        }

        public String getExplanation() {
            String opStr = getOpString();
            String status = lastResult ? "TRUE" : "FALSE";
            return String.format(Locale.US, "%s(%.2f) %s %.2f -> %s", eddName, lastSampledValue, opStr, threshold, status);
        }

        public String getOpString() {
            switch (op) {
                case GREATER_THAN:  return ">";
                case LESS_THAN:     return "<";
                case GREATER_EQUAL: return ">=";
                case LESS_EQUAL:    return "<=";
                case EQUAL:         return "==";
                case NOT_EQUAL:     return "!=";
                default:            return "?";
            }
        }

        @Override
        public String toString() {
            return eddName + " " + getOpString() + " " + threshold;
        }
    }

    private List<Clause> clauses;
    private boolean isConjunction; // true: AND, false: OR
    private String lastExplanation = "";

    public PredicateExpression(boolean isConjunction) {
        this.clauses = new ArrayList<Clause>();
        this.isConjunction = isConjunction;
    }

    public void addClause(String eddName, Operator op, double threshold) {
        this.clauses.add(new Clause(eddName, op, threshold));
    }

    public boolean evaluate(EddTelemetrySampler sampler) {
        if (clauses.isEmpty()) {
            this.lastExplanation = "EMPTY_PREDICATE -> TRUE";
            return true;
        }

        StringBuilder expl = new StringBuilder();
        String delim = isConjunction ? " AND " : " OR ";
        boolean overallResult = isConjunction; // start true for AND, false for OR

        if (isConjunction) {
            for (int i = 0; i < clauses.size(); i++) {
                Clause c = clauses.get(i);
                boolean r = c.evaluate(sampler);
                if (i > 0) expl.append(delim);
                expl.append("[").append(c.getExplanation()).append("]");
                if (!r) {
                    overallResult = false;
                }
            }
        } else {
            overallResult = false;
            for (int i = 0; i < clauses.size(); i++) {
                Clause c = clauses.get(i);
                boolean r = c.evaluate(sampler);
                if (i > 0) expl.append(delim);
                expl.append("[").append(c.getExplanation()).append("]");
                if (r) {
                    overallResult = true;
                }
            }
        }

        this.lastExplanation = expl.toString();
        return overallResult;
    }

    public String getLastExplanation() {
        return lastExplanation;
    }

    public static Operator parseOperator(String opStr) {
        opStr = opStr.trim();
        if (">=".equals(opStr)) return Operator.GREATER_EQUAL;
        if ("<=".equals(opStr)) return Operator.LESS_EQUAL;
        if (">".equals(opStr))  return Operator.GREATER_THAN;
        if ("<".equals(opStr))  return Operator.LESS_THAN;
        if ("==".equals(opStr) || "=".equals(opStr)) return Operator.EQUAL;
        if ("!=".equals(opStr)) return Operator.NOT_EQUAL;
        throw new IllegalArgumentException("Unknown predicate operator: " + opStr);
    }

    @Override
    public String toString() {
        if (clauses.isEmpty()) return "TRUE";
        StringBuilder sb = new StringBuilder();
        String delim = isConjunction ? " && " : " || ";
        for (int i = 0; i < clauses.size(); i++) {
            if (i > 0) sb.append(delim);
            sb.append("(").append(clauses.get(i).toString()).append(")");
        }
        return sb.toString();
    }
}
