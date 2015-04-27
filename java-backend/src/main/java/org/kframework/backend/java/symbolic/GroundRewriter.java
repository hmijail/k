// Copyright (c) 2013-2014 K Team. All Rights Reserved.
package org.kframework.backend.java.symbolic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.kframework.backend.java.kil.CellCollection;
import org.kframework.backend.java.kil.CellLabel;
import org.kframework.backend.java.kil.Definition;
import org.kframework.backend.java.kil.Rule;
import org.kframework.backend.java.kil.Term;
import org.kframework.backend.java.kil.TermContext;
import org.kframework.backend.java.kil.Variable;
import org.kframework.krun.api.SearchType;

import com.google.common.base.Stopwatch;

public class GroundRewriter extends AbstractRewriter {

    private final Stopwatch stopwatch = Stopwatch.createUnstarted();
    private boolean transition;

    public GroundRewriter(Definition definition, TermContext termContext) {
        super(definition, termContext);
    }

    @Override
    public Term rewrite(Term subject, int bound) {
        stopwatch.start();

        subject = super.rewrite(subject, bound);

        stopwatch.stop();
        if (termContext.definition().context().krunOptions.experimental.statistics) {
            System.err.println("[" + step + ", " + stopwatch + "]");
        }

        return subject;
    }

    /**
     * Gets the rules that could be applied to a given term according to the
     * rule indexing mechanism.
     *
     * @param term
     *            the given term
     * @return a list of rules that could be applied
     */
    private List<Rule> getRules(Term term) {
        return ruleIndex.getRules(term);
    }

    @Override
    protected final void computeRewriteStep(Term subject, int successorBound) {
        results.clear();

        if (successorBound == 0) {
            return;
        }

        // Applying a strategy to a list of rules divides the rules up into
        // equivalence classes of rules. We iterate through these equivalence
        // classes one at a time, seeing which one contains rules we can apply.
        //        System.out.println(LookupCell.find(constrainedTerm.term(),"k"));
        strategy.reset(getRules(subject));

        while (strategy.hasNext()) {
            transition = strategy.nextIsTransition();
            ArrayList<Rule> rules = new ArrayList<Rule>(strategy.next());
//            System.out.println("rules.size: "+rules.size());
            for (Rule rule : rules) {
                for (Map<Variable, Term> subst : getMatchingResults(subject, rule)) {
                    results.add(constructNewSubjectTerm(rule, subst));
                    if (results.size() == successorBound) {
                        return;
                    }
                }
            }
            // If we've found matching results from one equivalence class then
            // we are done, as we can't match rules from two equivalence classes
            // in the same step.
            if (results.size() > 0) {
                return;
            }
        }
    }

    @Override
    protected Term constructNewSubjectTerm(Rule rule, Map<Variable, Term> substitution) {
        return rule.rightHandSide().substituteAndEvaluate(substitution, termContext);
    }

    private Map<Variable, Term> getSubstitutionMap(Term term, Rule pattern) {
        List<Map<Variable, Term>> maps = PatternMatcher.match(term, pattern, termContext);
        if (maps.size() != 1) {
            return null;
        }

        Map<Variable, Term> map = maps.get(0);
        map.entrySet().forEach(e -> e.setValue(
                CellCollection.singleton(
                        CellLabel.GENERATED_TOP,
                        e.getValue(),
                        termContext.definition().context())));
        return map;
    }

    @Override
    public List<Map<Variable,Term>> search(
            Term initialTerm,
            Term targetTerm,
            List<Rule> rules,
            Rule pattern,
            int bound,
            int depth,
            SearchType searchType) {
        stopwatch.start();

        List<Map<Variable,Term>> searchResults = new ArrayList<Map<Variable,Term>>();
        Set<Term> visited = new HashSet<Term>();

        // If depth is 0 then we are just trying to match the pattern.
        // A more clean solution would require a bit of a rework to how patterns
        // are handled in krun.Main when not doing search.
        if (depth == 0) {
            Map<Variable, Term> map = getSubstitutionMap(initialTerm, pattern);
            if (map != null) {
                searchResults.add(map);
            }
            stopwatch.stop();
            if (termContext.definition().context().krunOptions.experimental.statistics) {
                System.err.println("[" + visited.size() + "states, " + step + "steps, " + stopwatch + "]");
            }
            return searchResults;
        }

        // The search queues will map terms to their depth in terms of transitions.
        Map<Term,Integer> queue = new LinkedHashMap<Term,Integer>();
        Map<Term,Integer> nextQueue = new LinkedHashMap<Term,Integer>();

        visited.add(initialTerm);
        queue.put(initialTerm, 0);

        if (searchType == SearchType.ONE) {
            depth = 1;
        }
        if (searchType == SearchType.STAR) {
            Map<Variable, Term> map = getSubstitutionMap(initialTerm, pattern);
            if (map != null) {
                searchResults.add(map);
            }
        }

        label:
        for (step = 0; !queue.isEmpty(); ++step) {
            for (Map.Entry<Term, Integer> entry : queue.entrySet()) {
                Term term = entry.getKey();
                Integer currentDepth = entry.getValue();
                computeRewriteStep(term);

                if (results.isEmpty() && searchType == SearchType.FINAL) {
                    Map<Variable, Term> map = getSubstitutionMap(term, pattern);
                    if (map != null) {
                        searchResults.add(map);
                        if (searchResults.size() == bound) {
                            break label;
                        }
                    }
                }

                for (Term result : results) {
                    if (!transition) {
                        nextQueue.put(result, currentDepth);
                        break;
                    } else {
                        // Continue searching if we haven't reached our target
                        // depth and we haven't already visited this state.
                        if (currentDepth + 1 != depth && visited.add(result)) {
                            nextQueue.put(result, currentDepth + 1);
                        }
                        // If we aren't searching for only final results, then
                        // also add this as a result if it matches the pattern.
                        if (searchType != SearchType.FINAL || currentDepth + 1 == depth) {
                            Map<Variable, Term> map = getSubstitutionMap(result, pattern);
                            if (map != null) {
                                searchResults.add(map);
                                if (searchResults.size() == bound) {
                                    break label;
                                }
                            }
                        }
                    }
                }
            }
//            System.out.println("+++++++++++++++++++++++");

            /* swap the queues */
            Map<Term, Integer> temp;
            temp = queue;
            queue = nextQueue;
            nextQueue = temp;
            nextQueue.clear();
        }

        stopwatch.stop();
        if (termContext.definition().context().krunOptions.experimental.statistics) {
            System.err.println("[" + visited.size() + "states, " + step + "steps, " + stopwatch + "]");
        }

        return searchResults;
    }

}