package dev.atmpilot;

import java.util.*;
import static dev.atmpilot.PlanModel.*;

/** Transactional, bounded expansion: failed alternatives cannot spend the real stock ledger. */
public final class DependencyPlanner {
    private final Map<Material, List<Recipe>> producers = new HashMap<>();
    private int visits;
    private static final int MAX_VISITS = 20_000;
    private static final int MAX_DEPTH = 48;

    public DependencyPlanner(Collection<Recipe> recipes) {
        for (Recipe recipe : recipes) {
            for (Amount output : recipe.outputs()) {
                producers.computeIfAbsent(output.material(), k -> new ArrayList<>()).add(recipe);
            }
        }
        producers.values().forEach(list -> list.sort(Comparator
            .comparing((Recipe r) -> !r.deterministic())
            .thenComparing(r -> !r.executable())
            .thenComparingInt(r -> r.inputs().size())
            .thenComparing(Recipe::id)));
    }

    public Plan plan(Material target, long count, Map<Material, Long> inventory) {
        return plan(target,count,inventory,Set.of());
    }
    public Plan plan(Material target,long count,Map<Material,Long> inventory,Set<Material> availableStations) {
        if (count <= 0 || count > 1_000_000_000_000L) throw new IllegalArgumentException("Invalid target count");
        visits = 0;
        Ledger ledger = new Ledger();
        ledger.stations.addAll(availableStations);
        inventory.forEach((k,v) -> { if (v > 0) ledger.stock.put(k, v); });
        require(target, count, ledger, new LinkedHashSet<>(), 0);
        boolean ready = ledger.missing.isEmpty() && ledger.issues.isEmpty()
            && ledger.steps.stream().allMatch(Step::executable);
        return new Plan(target, count, List.copyOf(ledger.steps), Map.copyOf(ledger.missing),
                        List.copyOf(ledger.issues), ready);
    }

    private void require(Material material, long count, Ledger ledger, Set<Material> ancestors, int depth) {
        long available = ledger.stock.getOrDefault(material, 0L);
        long used = Math.min(available, count);
        ledger.stock.put(material, available - used);
        long needed = count - used;
        if (needed == 0) return;
        if (++visits > MAX_VISITS || depth > MAX_DEPTH) {
            ledger.missing.merge(material, needed, Math::addExact);
            ledger.issues.add("Planning limit reached at " + material.id());
            return;
        }
        if (ancestors.contains(material)) {
            ledger.missing.merge(material, needed, Math::addExact);
            ledger.issues.add("Recipe cycle at " + material.id());
            return;
        }
        List<Recipe> recipes = producers.getOrDefault(material, List.of());
        if (recipes.isEmpty()) { ledger.missing.merge(material, needed, Math::addExact); return; }
        Set<Material> path = new LinkedHashSet<>(ancestors);
        path.add(material);
        Ledger best = null;
        int candidates = 0;
        for (Recipe recipe : recipes) {
            if (++candidates > 8 || visits > MAX_VISITS) break;
            // Display-only JEI entries and chance outputs must not create fictitious supplies.
            if (recipe.inputs().isEmpty() || !recipe.deterministic()) continue;
            long yield = recipe.produces(material);
            if (yield <= 0) continue;
            Ledger branch = ledger.copy();
            try {
                long batches = Math.addExact(needed, yield - 1) / yield;
                List<Amount> selected = new ArrayList<>();
                for (Ingredient input : recipe.inputs()) {
                    Amount choice = choose(input, batches, branch, path);
                    if (choice == null) {
                        branch.issues.add("Unknown ingredient in " + recipe.id());
                        continue;
                    }
                    long quantity = Math.multiplyExact(choice.count(), batches);
                    require(choice.material(), quantity, branch, path, depth + 1);
                    selected.add(new Amount(choice.material(), quantity));
                }
                // Machine catalysts describe required stations, not materials consumed per batch.
                for (Ingredient catalyst : recipe.catalysts()) {
                    Amount choice = choose(catalyst, 1, branch, path);
                    if (choice != null && branch.stations.add(choice.material())) {
                        require(choice.material(), choice.count(), branch, path, depth + 1);
                    }
                }
                List<Amount> made = new ArrayList<>();
                for (Amount output : recipe.outputs()) {
                    long quantity = Math.multiplyExact(output.count(), batches);
                    branch.stock.merge(output.material(), quantity, Math::addExact);
                    made.add(new Amount(output.material(), quantity));
                }
                branch.stock.compute(material, (k,v) -> Math.subtractExact(v == null ? 0L : v, needed));
                branch.steps.add(new Step(recipe.id(), recipe.type(), batches, selected, made,
                                          recipe.executable(), recipe.note()));
                if (best == null || score(branch) < score(best)) best = branch;
                if (branch.missing.isEmpty() && branch.issues.isEmpty()
                    && branch.steps.stream().allMatch(Step::executable)) break;
            } catch (ArithmeticException e) {
                branch.issues.add("Quantity overflow in " + recipe.id());
                if (best == null) best = branch;
            }
        }
        if (best == null) {
            ledger.missing.merge(material, needed, Math::addExact);
            ledger.issues.add("Only probabilistic, display-only or unresolved producers for " + material.id());
        } else ledger.replace(best);
    }

    private Amount choose(Ingredient ingredient, long batches, Ledger ledger, Set<Material> path) {
        return ingredient.alternatives().stream().min(Comparator
            .comparing((Amount a) -> path.contains(a.material()))
            .thenComparing(a -> ledger.stock.getOrDefault(a.material(),0L) / a.count() < batches)
            .thenComparing(a -> !producers.containsKey(a.material()))
            .thenComparingLong(Amount::count)
            .thenComparing(a -> a.material().id())).orElse(null);
    }

    private double score(Ledger ledger) {
        double missing = ledger.missing.values().stream().mapToDouble(n -> 1 + Math.log1p(n)).sum();
        return ledger.issues.size() * 1_000_000.0 + missing * 1000
            + ledger.steps.stream().filter(s -> !s.executable()).count() * 100 + ledger.steps.size();
    }

    private static final class Ledger {
        final Map<Material,Long> stock = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
        final Set<Material> stations = new HashSet<>();
        final List<Step> steps = new ArrayList<>();
        final Set<String> issues = new LinkedHashSet<>();
        Ledger copy() { Ledger l = new Ledger(); l.replace(this); return l; }
        void replace(Ledger l) {
            stock.clear(); stock.putAll(l.stock); missing.clear(); missing.putAll(l.missing);
            stations.clear(); stations.addAll(l.stations); steps.clear(); steps.addAll(l.steps);
            issues.clear(); issues.addAll(l.issues);
        }
    }
}
