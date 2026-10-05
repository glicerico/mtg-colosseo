package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.MageObjectReference;
import mage.abilities.Ability;
import mage.abilities.decorator.ConditionalContinuousEffect;
import mage.abilities.condition.Condition;
import mage.abilities.dynamicvalue.DynamicValue;
import mage.abilities.effects.ContinuousEffect;
import mage.constants.Duration;
import mage.constants.Layer;
import mage.counters.Counter;
import mage.filter.FilterPermanent;
import mage.game.Game;
import mage.game.permanent.Permanent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why a permanent looks the way it does, for the web UI: printed vs current power/toughness, what its counters add,
 * and the continuous effects the engine applies to it (source, text, duration). Everything here is public
 * information (it follows from the battlefield and the stack's history), but it is only added to the views of
 * browser seats ({@code human}) and spectators, never to agents' observations.
 * <p>
 * XMage has no "effects on this object" query, so effects are attributed from what they reference: their locked-in
 * affected objects, their target pointer, their source ("this creature gets ...") or attachment ("enchanted creature
 * gets ..."), or their filter ("creatures you control get ..."). The power/toughness totals are exact whatever the
 * attribution finds: {@code current = printed (or set) + counters + effects}.
 * <p>
 * One instance per state build; must be called from the game thread. Never changes the game.
 */
final class Explain {

    private static final Pattern PT_COUNTER = Pattern.compile("^([+-]\\d+)/([+-]\\d+)$");
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    private final Game game;
    private final UUID viewerId;
    private final boolean revealAll;
    private List<Active> active;

    /** A layered effect with one of the abilities that created it. */
    private static final class Active {
        final ContinuousEffect effect;
        final Ability source;

        Active(ContinuousEffect effect, Ability source) {
            this.effect = effect;
            this.source = source;
        }
    }

    Explain(Game game, UUID viewerId, boolean revealAll) {
        this.game = game;
        this.viewerId = viewerId;
        this.revealAll = revealAll;
    }

    /**
     * The explanation of a visible permanent, or null when there is nothing to explain (a land with no effects).
     */
    JsonObject of(Permanent perm) {
        try {
            JsonArray effects = effectsOn(perm);
            boolean creature = perm.isCreature(game);
            if (!creature && effects.isEmpty()) {
                return null;
            }
            JsonObject o = new JsonObject();
            boolean modified = !effects.isEmpty();
            if (creature) {
                int printedP = perm.getPower().getBaseValue();
                int printedT = perm.getToughness().getBaseValue();
                int baseP = perm.getPower().getModifiedBaseValue();
                int baseT = perm.getToughness().getModifiedBaseValue();
                int curP = perm.getPower().getValue();
                int curT = perm.getToughness().getValue();
                int[] counters = counterPT(perm);
                o.add("printed", pair(printedP, printedT));
                if (baseP != printedP || baseT != printedT) {
                    o.add("set", pair(baseP, baseT)); // layer 7a/7b: characteristic-defining or "base P/T becomes"
                }
                if (counters[0] != 0 || counters[1] != 0) {
                    o.add("counters", pair(counters[0], counters[1]));
                }
                int effP = curP - baseP - counters[0];
                int effT = curT - baseT - counters[1];
                if (effP != 0 || effT != 0) {
                    o.add("effects_pt", pair(effP, effT));
                }
                modified |= curP != printedP || curT != printedT;
            }
            o.add("effects", effects);
            o.addProperty("modified", modified);
            return o;
        } catch (RuntimeException e) {
            return null; // an explanation must never break a state build
        }
    }

    private static JsonArray pair(int a, int b) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        return arr;
    }

    private int[] counterPT(Permanent perm) {
        int p = 0;
        int t = 0;
        for (Counter c : perm.getCounters(game).values()) {
            Matcher m = PT_COUNTER.matcher(c.getName());
            if (m.matches()) {
                p += Integer.parseInt(m.group(1)) * c.getCount();
                t += Integer.parseInt(m.group(2)) * c.getCount();
            }
        }
        return new int[]{p, t};
    }

    private List<Active> active() {
        if (active == null) {
            active = new ArrayList<>();
            for (ContinuousEffect effect : game.getContinuousEffects().getLayeredEffects(game)) {
                if (effect.isDiscarded() || !relevant(effect)) {
                    continue;
                }
                Set<Ability> abilities = game.getContinuousEffects().getLayeredEffectAbilities(effect);
                for (Ability a : abilities) {
                    active.add(new Active(effect, a));
                }
            }
        }
        return active;
    }

    /** Layers a player would want explained: control, type, color, abilities, power/toughness. */
    private static boolean relevant(ContinuousEffect effect) {
        return effect.hasLayer(Layer.PTChangingEffects_7) || effect.hasLayer(Layer.AbilityAddingRemovingEffects_6)
                || effect.hasLayer(Layer.ControlChangingEffects_2) || effect.hasLayer(Layer.TypeChangingEffects_4)
                || effect.hasLayer(Layer.ColorChangingEffects_5);
    }

    private JsonArray effectsOn(Permanent perm) {
        JsonArray arr = new JsonArray();
        for (Active a : active()) {
            ContinuousEffect inner = unwrap(a.effect, a.source);
            if (inner == null || !affects(inner, a.source, perm)) {
                continue;
            }
            arr.add(describe(a.effect, inner, a.source));
        }
        return arr;
    }

    /** The effect a conditional effect currently applies, or null when its condition is false. */
    private ContinuousEffect unwrap(ContinuousEffect effect, Ability source) {
        if (!(effect instanceof ConditionalContinuousEffect)) {
            return effect;
        }
        Object condition = field(effect, "condition", Condition.class);
        if (condition == null) {
            condition = field(effect, "baseCondition", Condition.class);
        }
        boolean on = condition == null || ((Condition) condition).apply(game, source);
        Object inner = field(effect, on ? "effect" : "otherwiseEffect", ContinuousEffect.class);
        return inner == null ? null : (ContinuousEffect) inner;
    }

    private boolean affects(ContinuousEffect effect, Ability source, Permanent perm) {
        List<MageObjectReference> locked = effect.getAffectedObjects();
        if (locked != null && !locked.isEmpty()) {
            for (MageObjectReference mor : locked) {
                if (mor.refersTo(perm, game)) {
                    return true;
                }
            }
            return false;
        }
        try {
            if (effect.getTargetPointer() != null && effect.getTargetPointer().getTargets(game, source).contains(perm.getId())) {
                return true;
            }
        } catch (RuntimeException e) {
            // pointers may refuse abilities without targets
        }
        String cls = effect.getClass().getSimpleName();
        UUID sourceId = source.getSourceId();
        if (cls.contains("Source")) {
            return perm.getId().equals(sourceId);
        }
        if (cls.contains("Enchanted") || cls.contains("Equipped") || cls.contains("Attached")) {
            Permanent attachment = game.getPermanent(sourceId);
            return attachment != null && perm.getId().equals(attachment.getAttachedTo());
        }
        Object filter = field(effect, null, FilterPermanent.class);
        if (filter != null) {
            if (!((FilterPermanent) filter).match(perm, source.getControllerId(), source, game)) {
                return false;
            }
            if (cls.contains("Controlled") && !perm.isControlledBy(source.getControllerId())) {
                return false;
            }
            if (cls.contains("Opponents") && perm.isControlledBy(source.getControllerId())) {
                return false;
            }
            Object exclude = field(effect, "excludeSource", boolean.class);
            return !(Boolean.TRUE.equals(exclude) && perm.getId().equals(sourceId));
        }
        return false;
    }

    private JsonObject describe(ContinuousEffect outer, ContinuousEffect inner, Ability source) {
        JsonObject e = new JsonObject();
        UUID sourceId = source.getSourceId();
        MageObject src = game.getObject(sourceId);
        boolean hidden = !revealAll && StateView.isHidden(game, sourceId)
                && (viewerId == null || src == null || !viewerId.equals(game.getOwnerId(sourceId)));
        String name = src == null ? "" : hidden ? "a hidden card" : src.getName();
        e.addProperty("source", name);
        e.addProperty("source_id", hidden ? null : Json.str(sourceId));
        String text;
        try {
            text = Json.plain(outer.getText(source.getModes().getMode()));
        } catch (RuntimeException ex) {
            text = "";
        }
        e.addProperty("text", text.replace("{this}", hidden ? "it" : name).trim());
        Duration d = outer.getDuration();
        String duration = d == null ? "" : d.toString().replace("{this}", name);
        e.addProperty("duration", duration);
        e.addProperty("layer", inner.hasLayer(Layer.PTChangingEffects_7) ? "pt"
                : inner.hasLayer(Layer.AbilityAddingRemovingEffects_6) ? "abilities"
                : inner.hasLayer(Layer.ControlChangingEffects_2) ? "control"
                : inner.hasLayer(Layer.TypeChangingEffects_4) ? "type" : "color");
        if (inner.getClass().getSimpleName().startsWith("Boost")) {
            Object p = field(inner, "power", DynamicValue.class);
            Object t = field(inner, "toughness", DynamicValue.class);
            if (p != null && t != null) {
                try {
                    e.addProperty("power", ((DynamicValue) p).calculate(game, source, inner));
                    e.addProperty("toughness", ((DynamicValue) t).calculate(game, source, inner));
                } catch (RuntimeException ex) {
                    // amount unknown: the text still says it
                }
            }
        }
        return e;
    }

    /** A field of the object's class or its superclasses, by name (or the first of a type when name is null). */
    private static Object field(Object obj, String name, Class<?> type) {
        for (Field f : fields(obj.getClass())) {
            boolean typeOk = type.isPrimitive() ? f.getType() == type : type.isAssignableFrom(f.getType());
            if (typeOk && (name == null || f.getName().equals(name))) {
                try {
                    return f.get(obj);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<Field> fields(Class<?> cls) {
        return FIELDS.computeIfAbsent(cls, c -> {
            List<Field> out = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (RuntimeException e) {
                        // inaccessible (module rules): skip
                    }
                }
            }
            return out;
        });
    }
}
