package dev.atmpilot;

import java.util.*;

/** The planner has no dependency on Minecraft, and never changes world state. */
public final class PlanModel {
    private PlanModel() {}
    public record Material(String id, String components) {
        public Material {
            Objects.requireNonNull(id);
            if (id.isBlank()) throw new IllegalArgumentException("Empty material ID");
            components = components == null || components.equals("{}") ? "" : components;
        }
        public static Material item(String id) { return new Material(id, ""); }
    }
    public record Amount(Material material, long count) {
        public Amount {
            Objects.requireNonNull(material);
            if (count <= 0) throw new IllegalArgumentException("Quantity must be positive");
        }
    }
    /** Alternatives belong to ONE ingredient slot. They are never added together. */
    public record Ingredient(List<Amount> alternatives) {
        public Ingredient { alternatives = List.copyOf(alternatives); }
        public static Ingredient of(String id, long count) {
            return new Ingredient(List.of(new Amount(Material.item(id), count)));
        }
    }
    public record Recipe(String id, String type, List<Ingredient> inputs,
                         List<Amount> outputs, List<Ingredient> catalysts,
                         boolean deterministic, boolean executable, String note) {
        public Recipe {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            catalysts = List.copyOf(catalysts);
            if (outputs.isEmpty()) throw new IllegalArgumentException("Recipe has no output");
        }
        public long produces(Material material) {
            return outputs.stream().filter(a -> a.material.equals(material)).mapToLong(Amount::count).sum();
        }
    }
    public record Step(String recipe, String type, long batches, List<Amount> inputs,
                       List<Amount> outputs, boolean executable, String note) {}
    public record Plan(Material target, long count, List<Step> steps,
                       Map<Material, Long> missing, List<String> issues, boolean ready) {}
}
