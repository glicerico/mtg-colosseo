package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.ApprovingObject;
import mage.ConditionalMana;
import mage.Mana;
import mage.MageObject;
import mage.abilities.Abilities;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpecialAction;
import mage.abilities.SpellAbility;
import mage.abilities.costs.mana.ColoredManaCost;
import mage.abilities.costs.mana.ColorlessHybridManaCost;
import mage.abilities.costs.mana.ColorlessManaCost;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.costs.mana.HybridManaCost;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.costs.mana.MonoHybridManaCost;
import mage.abilities.costs.mana.SnowManaCost;
import mage.abilities.effects.RequirementEffect;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.AsThoughEffectType;
import mage.constants.CardType;
import mage.constants.ManaType;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.filter.common.FilterCreatureForCombat;
import mage.filter.common.FilterCreatureForCombatBlock;
import mage.filter.predicate.permanent.ControllerIdPredicate;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.game.events.DeclareAttackerEvent;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.player.human.HumanPlayer;
import mage.players.ManaPoolItem;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.util.CardUtil;
import mage.util.ManaUtil;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A player whose decisions come from outside the JVM: a human in the web UI or an agent over the protocol.
 * <p>
 * It extends XMage's {@link HumanPlayer} so every dialog the rules engine can raise (targets, modes, choices,
 * amounts, piles, replacement effects, trigger ordering, ...) is supported. Dialogs raised by HumanPlayer
 * surface as {@code PlayerQueryEvent}s and are translated by {@link QueryTranslator}. The decisions that are
 * awkward as click streams (priority, attacking, blocking, mana payment) are overridden here and exposed as
 * single structured decisions:
 * <ul>
 *     <li>priority: an explicit list of legal actions (cast / play land / activate) plus pass</li>
 *     <li>declare attackers / blockers: one decision answered with the full assignment</li>
 *     <li>mana payment: automatic (ported from XMage's AI), falling back to manual payment</li>
 * </ul>
 */
public class BridgePlayer extends HumanPlayer {

    /**
     * Rich context for the dialog currently raised by HumanPlayer (the query event itself lacks e.g. min/max).
     */
    public static final class QueryContext {
        public final String kindHint;
        public final Target target;
        public final Ability source;
        public final Cards cards;

        QueryContext(String kindHint, Target target, Ability source, Cards cards) {
            this.kindHint = kindHint;
            this.target = target;
            this.source = source;
            this.cards = cards;
        }
    }

    private static final String WAKE = "colosseo";

    // actions that failed to activate in the current game window (see legalActions)
    private final transient Set<String> failedActions = new HashSet<>();
    private transient String failedWindow;

    private transient Seat seat;
    private final transient Deque<QueryContext> contexts = new ArrayDeque<>();
    private transient volatile JsonObject lastAction;

    public BridgePlayer(String name) {
        super(name, RangeOfInfluence.ONE, 0);
    }

    public BridgePlayer(final BridgePlayer player) {
        super(player);
        this.seat = player.seat;
    }

    @Override
    public BridgePlayer copy() {
        return new BridgePlayer(this);
    }

    public void attachSeat(Seat seat) {
        this.seat = seat;
    }

    public Seat getSeat() {
        return seat;
    }

    public QueryContext currentContext() {
        return contexts.peek();
    }

    private boolean canFeedback(Game game) {
        return seat != null && !game.inCheckPlayableState() && !game.isSimulation();
    }

    // ------------------------------------------------------------------------------------------------
    // structured decisions
    // ------------------------------------------------------------------------------------------------

    /**
     * Publishes a structured decision and blocks the game thread until it is answered.
     *
     * @return the answer, or null if the wait was interrupted (concede, abort, game end)
     */
    private JsonObject ask(Game game, Decision decision) {
        decision.responder = new Responder(decision);
        prepareForResponse(game);
        lastAction = null;
        seat.session().publish(seat, decision);
        waitForResponse(game);
        JsonObject answer = lastAction;
        lastAction = null;
        return answer;
    }

    /**
     * Validates structured answers against the decision and wakes the game thread.
     */
    private final class Responder implements Decision.Responder {
        private final Decision decision;

        Responder(Decision decision) {
            this.decision = decision;
        }

        @Override
        public String validate(JsonObject action) {
            switch (decision.kind) {
                case Decision.ATTACKERS:
                    return validatePairs(action, "attackers", "attacker", "defender", decision);
                case Decision.BLOCKERS:
                    return validatePairs(action, "blocks", "blocker", "attacker", decision);
                default:
                    String choice = Json.getString(action, "choice", null);
                    if (!decision.hasOption(choice)) {
                        return "unknown option: " + choice;
                    }
                    return null;
            }
        }

        @Override
        public void apply(JsonObject action) {
            lastAction = action;
            setResponseString(WAKE);
        }
    }

    private static String validatePairs(JsonObject action, String listKey, String firstKey, String secondKey, Decision decision) {
        if (action.has("choices") && !action.has(listKey)) {
            // shorthand: list of attacker/blocker ids with default counterpart
            return null;
        }
        if (!action.has(listKey) || !action.get(listKey).isJsonArray()) {
            return "expected '" + listKey + "' list";
        }
        JsonObject allowed = decision.extra.getAsJsonObject("_allowed");
        for (JsonElement el : action.getAsJsonArray(listKey)) {
            if (!el.isJsonObject()) {
                return "each entry of '" + listKey + "' must be an object";
            }
            JsonObject pair = el.getAsJsonObject();
            String first = Json.getString(pair, firstKey, null);
            String second = Json.getString(pair, secondKey, null);
            if (first == null || !allowed.has(first)) {
                return "illegal " + firstKey + ": " + first;
            }
            JsonArray legal = allowed.getAsJsonArray(first);
            if (second != null && !legal.contains(Json.prim(second))) {
                return first + " can't be assigned to " + second;
            }
            if (second == null && legal.size() == 0) {
                return first + " has no legal " + secondKey;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------
    // priority
    // ------------------------------------------------------------------------------------------------

    @Override
    public boolean priority(Game game) {
        passed = false;
        if (!canRespond()) {
            return false;
        }
        if (!canFeedback(game)) {
            pass(game);
            return false;
        }

        // yield after own spell/ability (human convenience, like "auto pass after cast")
        if (getJustActivatedType() != null && !holdingPriority) {
            setJustActivatedType(null);
            if (seat.config().yieldAfterCast) {
                pass(game);
                return false;
            }
        }

        while (canRespond()) {
            if (seat.passTurnActive(game)) {
                pass(game);
                return false;
            }

            Map<String, ActivatedAbility> actions = legalActions(game);
            if (actions.isEmpty() && seat.config().autoPassNoActions) {
                pass(game);
                return false;
            }
            if (!seat.shouldStop(game, !actions.isEmpty())) {
                pass(game);
                return false;
            }

            Decision d = seat.session().newDecision(Decision.PRIORITY, getId());
            d.prompt = game.canPlaySorcery(getId()) ? "Play spells and abilities" : "Play instants and activated abilities";
            JsonObject passOpt = d.addOption("pass", game.getStack().isEmpty() ? "Pass priority" : "Pass priority (resolve " + topOfStackName(game) + ")");
            passOpt.addProperty("action", "pass");
            JsonObject passTurn = d.addOption("pass_turn", "Pass until end of turn");
            passTurn.addProperty("action", "pass_turn");
            // canonical order (XMage's own order follows random object ids)
            List<Map.Entry<String, ActivatedAbility>> ordered = new ArrayList<>(actions.entrySet());
            ordered.sort(java.util.Comparator.comparing(e -> StateView.sortKey(game, e.getValue().getSourceId())
                    + "|" + e.getValue().getClass().getSimpleName() + "|" + ruleText(e.getValue(), "")));
            for (Map.Entry<String, ActivatedAbility> e : ordered) {
                describeAction(game, d, e.getKey(), e.getValue());
            }
            d.defaultAction = Decision.choiceAction("pass");

            JsonObject answer = ask(game, d);
            if (game.executingRollback()) {
                return true;
            }
            if (answer == null) {
                if (!canRespond()) {
                    return false;
                }
                continue;
            }
            String choice = Json.getString(answer, "choice", "pass");
            if ("pass".equals(choice)) {
                pass(game);
                return false;
            }
            if ("pass_turn".equals(choice)) {
                seat.startPassTurn(game);
                pass(game);
                return false;
            }
            ActivatedAbility ability = actions.get(choice);
            if (ability == null) {
                continue;
            }
            // activation may fail (e.g. no legal targets, cost not paid): XMage rolls the state back itself.
            // Hide a failed action until the game moves on, otherwise an agent can retry it forever.
            if (!activateAbility(ability.copy(), game)) {
                failedActions.add(choice);
            }
            return true;
        }
        return false;
    }

    private static String topOfStackName(Game game) {
        StackObject top = game.getStack().getFirstOrNull();
        return top == null ? "" : top.getName();
    }

    /**
     * Legal non-mana actions for this priority, keyed by ability id.
     */
    public Map<String, ActivatedAbility> legalActions(Game game) {
        String window = game.getTurnNum() + "|" + game.getTurnStepType() + "|" + game.getStack().size()
                + "|" + game.getBattlefield().getAllPermanents().size() + "|" + getHand().size();
        if (!window.equals(failedWindow)) {
            failedWindow = window;
            failedActions.clear();
        }
        Map<String, ActivatedAbility> result = new LinkedHashMap<>();
        for (ActivatedAbility ability : getPlayable(game, true, Zone.ALL, false)) {
            if (ability.isManaActivatedAbility()) {
                continue;
            }
            if (!failedActions.contains(ability.getId().toString())) {
                result.putIfAbsent(ability.getId().toString(), ability);
            }
        }
        return result;
    }

    private void describeAction(Game game, Decision d, String id, ActivatedAbility ability) {
        MageObject source = game.getObject(ability.getSourceId());
        String sourceName = source == null ? "" : source.getName();
        String kind;
        String label;
        if (ability instanceof PlayLandAbility) {
            kind = "play_land";
            label = "Play " + sourceName;
        } else if (ability instanceof SpellAbility) {
            kind = "cast";
            SpellAbility spell = (SpellAbility) ability;
            String rule = ruleText(ability, spell.getCardName());
            label = rule.startsWith("Cast ") ? rule : "Cast " + spell.getCardName();
            if (!rule.startsWith("Cast ") && spell.getSpellAbilityType() != null
                    && spell.getSpellAbilityType() != mage.constants.SpellAbilityType.BASE) {
                label = spell + ": " + rule;
            }
        } else if (ability instanceof SpecialAction) {
            kind = "special";
            label = ruleText(ability, sourceName);
        } else {
            kind = "activate";
            label = sourceName + ": " + ruleText(ability, sourceName);
        }
        JsonObject o = d.addOption(id, Json.plain(label));
        o.addProperty("action", kind);
        o.addProperty("source_id", Json.str(ability.getSourceId()));
        o.addProperty("source_name", sourceName);
        Zone zone = game.getState().getZone(ability.getSourceId());
        o.addProperty("zone", zone == null ? null : zone.name());
        if (source instanceof Card || source instanceof Permanent) {
            o.addProperty("mana_cost", ability.getManaCostsToPay().getText());
        }
        if (StateView.isHidden(game, ability.getSourceId())) {
            // the action may still fail (no target, cost not paid): don't name a hidden card to the opponent
            String where = zone == Zone.HAND ? "hand" : zone == Zone.LIBRARY ? "library" : "a hidden zone";
            switch (kind) {
                case "play_land":
                    d.markPrivate(id, "Play a land from " + where);
                    break;
                case "cast":
                    d.markPrivate(id, "Cast a spell from " + where);
                    break;
                default:
                    d.markPrivate(id, "Activate an ability of a hidden card");
            }
        }
    }

    private static String ruleText(Ability ability, String objectName) {
        String rule = ability.getRule(objectName);
        if (rule == null || rule.isEmpty()) {
            rule = ability.toString();
        }
        if (!rule.isEmpty()) {
            rule = Character.toUpperCase(rule.charAt(0)) + rule.substring(1);
        }
        return rule;
    }

    // ------------------------------------------------------------------------------------------------
    // combat
    // ------------------------------------------------------------------------------------------------

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        if (!canFeedback(game)) {
            return;
        }
        FilterCreatureForCombat filter = filterCreatureForCombat.copy();
        filter.add(new ControllerIdPredicate(attackingPlayerId));

        String error = null;
        if (game.getCombat().getAttackers().stream().anyMatch(id -> {
            Permanent p = game.getPermanent(id);
            return p != null && p.isControlledBy(getId());
        })) {
            error = "The previous attack declaration was illegal (attack restrictions). Declare again.";
        }

        while (canRespond()) {
            List<Permanent> possible = new ArrayList<>();
            for (Permanent p : game.getBattlefield().getActivePermanents(filter, attackingPlayerId, game)) {
                if (p.canAttack(null, game)) {
                    possible.add(p);
                }
            }
            if (possible.isEmpty()) {
                return;
            }
            possible.sort(java.util.Comparator.comparing(p -> StateView.sortKey(game, p.getId())));
            Set<UUID> defenders = game.getCombat().getDefenders();

            Decision d = seat.session().newDecision(Decision.ATTACKERS, getId());
            d.prompt = "Declare attackers";
            JsonArray attackersJson = new JsonArray();
            JsonObject allowed = new JsonObject();
            for (Permanent p : possible) {
                JsonObject a = StateView.describe(game, p.getId(), getId());
                JsonArray legal = new JsonArray();
                for (UUID def : defenders) {
                    if (p.canAttack(def, game)) {
                        legal.add(def.toString());
                    }
                }
                a.add("defenders", legal);
                a.addProperty("must_attack", game.getCombat().getCreaturesForcedToAttack().containsKey(p.getId()));
                attackersJson.add(a);
                allowed.add(p.getId().toString(), legal);
                d.addOption(p.getId().toString(), p.getName()).addProperty("kind", "attacker");
            }
            JsonArray defendersJson = new JsonArray();
            for (UUID def : defenders) {
                defendersJson.add(StateView.describe(game, def, getId()));
            }
            d.extra.add("attackers", attackersJson);
            d.extra.add("defenders", defendersJson);
            d.extra.add("_allowed", allowed);
            if (error != null) {
                d.extra.addProperty("error", error);
            }
            JsonObject none = new JsonObject();
            none.add("attackers", new JsonArray());
            d.defaultAction = none;

            JsonObject answer = ask(game, d);
            if (answer == null || game.executingRollback()) {
                if (!canRespond()) {
                    return;
                }
                continue;
            }

            // clear a previous (invalid) declaration
            for (UUID id : new ArrayList<>(game.getCombat().getAttackers())) {
                Permanent p = game.getPermanent(id);
                if (p != null && p.isControlledBy(getId())) {
                    game.getCombat().removeAttacker(id, game);
                }
            }
            for (JsonObject pair : pairs(answer, "attackers", "attacker", "defender")) {
                UUID attackerId = UUID.fromString(Json.getString(pair, "attacker", null));
                String defStr = Json.getString(pair, "defender", null);
                UUID defenderId;
                if (defStr != null) {
                    defenderId = UUID.fromString(defStr);
                } else {
                    JsonArray legal = allowed.getAsJsonArray(attackerId.toString());
                    defenderId = legal == null || legal.size() == 0 ? null : preferPlayer(game, legal);
                }
                if (defenderId != null) {
                    declareAttacker(attackerId, defenderId, game, true);
                }
            }
            error = attackersProblem(game);
            if (error == null) {
                return;
            }
        }
    }

    private static UUID preferPlayer(Game game, JsonArray legal) {
        for (JsonElement el : legal) {
            UUID id = UUID.fromString(el.getAsString());
            if (game.getPlayer(id) != null) {
                return id;
            }
        }
        return UUID.fromString(legal.get(0).getAsString());
    }

    /**
     * Normalizes answers: either {"attackers": [{"attacker": id, "defender": id}]} or the shorthand
     * {"choices": [id, ...]} which uses the default counterpart.
     */
    private static List<JsonObject> pairs(JsonObject answer, String listKey, String firstKey, String secondKey) {
        List<JsonObject> out = new ArrayList<>();
        if (answer.has(listKey) && answer.get(listKey).isJsonArray()) {
            for (JsonElement el : answer.getAsJsonArray(listKey)) {
                out.add(el.getAsJsonObject());
            }
        } else if (answer.has("choices") && answer.get("choices").isJsonArray()) {
            for (JsonElement el : answer.getAsJsonArray("choices")) {
                JsonObject pair = new JsonObject();
                pair.addProperty(firstKey, el.getAsString());
                out.add(pair);
            }
        }
        return out;
    }

    /**
     * Port of HumanPlayer#checkIfAttackersValid: returns null if the declaration satisfies attack requirements.
     */
    private String attackersProblem(Game game) {
        if (!game.getCombat().getCreaturesForcedToAttack().isEmpty()) {
            if (!game.getCombat().getAttackers().containsAll(game.getCombat().getCreaturesForcedToAttack().keySet())) {
                int forcedAttackers = 0;
                StringBuilder sb = new StringBuilder();
                for (UUID creatureId : game.getCombat().getCreaturesForcedToAttack().keySet()) {
                    boolean validForcedAttacker = false;
                    if (game.getCombat().getAttackers().contains(creatureId)) {
                        Set<UUID> possibleDefender = game.getCombat().getCreaturesForcedToAttack().get(creatureId);
                        if (possibleDefender.isEmpty()
                                || possibleDefender.contains(game.getCombat().getDefenderId(creatureId))) {
                            validForcedAttacker = true;
                        }
                    }
                    if (validForcedAttacker) {
                        forcedAttackers++;
                    } else {
                        Permanent creature = game.getPermanent(creatureId);
                        if (creature != null) {
                            sb.append(creature.getName()).append(' ');
                        }
                    }
                }
                if (game.getCombat().getMaxAttackers() > forcedAttackers) {
                    return "These creatures are forced to attack: " + sb.toString().trim();
                }
            }
        }
        Set<UUID> playersToAttackIfAble = new HashSet<>();
        boolean mustAttack = false;
        for (Map.Entry<RequirementEffect, Set<Ability>> entry : game.getContinuousEffects().getApplicableRequirementEffects(null, true, game).entrySet()) {
            RequirementEffect effect = entry.getKey();
            for (Ability ability : entry.getValue()) {
                UUID playerToAttack = effect.playerMustBeAttackedIfAble(ability, game);
                if (playerToAttack != null) {
                    playersToAttackIfAble.add(playerToAttack);
                }
                if (effect.mustAttack(game)) {
                    mustAttack = true;
                }
            }
        }
        if (!playersToAttackIfAble.isEmpty()) {
            Set<UUID> check = new HashSet<>(playersToAttackIfAble);
            for (CombatGroup group : game.getCombat().getGroups()) {
                check.remove(group.getDefendingPlayerId());
            }
            for (UUID forcedToAttackId : check) {
                for (Permanent attacker : game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, getId(), game)) {
                    if (game.getContinuousEffects().checkIfThereArePayCostToAttackBlockEffects(
                            new DeclareAttackerEvent(forcedToAttackId, attacker.getId(), attacker.getControllerId()), game)) {
                        continue;
                    }
                    if (game.getCombat().getCreaturesForcedToAttack().containsKey(attacker.getId())) {
                        Set<UUID> possibleDefenders = game.getCombat().getCreaturesForcedToAttack().get(attacker.getId());
                        if (!possibleDefenders.isEmpty() && !possibleDefenders.contains(forcedToAttackId)) {
                            continue;
                        }
                    }
                    UUID defendingPlayerId = game.getCombat().getDefendingPlayerId(attacker.getId(), game);
                    if (playersToAttackIfAble.contains(defendingPlayerId)) {
                        continue;
                    }
                    if (defendingPlayerId != null || attacker.canAttackInPrinciple(forcedToAttackId, game)) {
                        Player forced = game.getPlayer(forcedToAttackId);
                        return "You are forced to attack " + (forced == null ? "a player" : forced.getName())
                                + ", e.g. with " + attacker.getName();
                    }
                }
            }
        }
        if (mustAttack && game.getCombat().getAttackers().isEmpty()) {
            for (Permanent attacker : game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, getId(), game)) {
                if (attacker.canAttackInPrinciple(null, game)) {
                    return "You are forced to attack with at least one creature, e.g. " + attacker.getName();
                }
            }
        }
        return null;
    }

    // XMage validates a whole block declaration (menace, "must block", ...) and asks again when it is illegal,
    // with a new decision: these remember the previous answer in the same combat to report the rejection and
    // to stop an agent that keeps sending the same illegal assignment
    private transient String blockCombatKey;
    private transient String lastBlockAnswer;
    private transient int blockAsks;
    private transient int identicalBlockAnswers;

    static final int MAX_IDENTICAL_ILLEGAL_BLOCKS = 3;
    static final int MAX_BLOCK_ASKS_PER_COMBAT = 10;

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        if (!canFeedback(game)) {
            return;
        }
        FilterCreatureForCombatBlock filter = filterCreatureForCombatBlock.copy();
        filter.add(new ControllerIdPredicate(defendingPlayerId));
        List<Permanent> possible = new ArrayList<>(game.getBattlefield().getActivePermanents(filter, getId(), source, game));
        if (possible.isEmpty() || game.getCombat().getAttackers().isEmpty()) {
            return;
        }
        possible.sort(java.util.Comparator.comparing(p -> StateView.sortKey(game, p.getId())));

        String combatKey = game.getTurnNum() + "|" + game.getTurnStepType() + "|"
                + new java.util.TreeSet<>(game.getCombat().getAttackers());
        boolean reask = combatKey.equals(blockCombatKey);
        if (!reask) {
            blockCombatKey = combatKey;
            lastBlockAnswer = null;
            blockAsks = 0;
            identicalBlockAnswers = 0;
        }
        if (++blockAsks > MAX_BLOCK_ASKS_PER_COMBAT) {
            // not even "no blocks" satisfied the requirements: the game can't continue sensibly
            seat.session().stop("error", "seat " + seat.index + ": no legal block declaration after "
                    + MAX_BLOCK_ASKS_PER_COMBAT + " attempts");
            return;
        }

        Decision d = seat.session().newDecision(Decision.BLOCKERS, getId());
        d.prompt = "Declare blockers";
        JsonObject allowed = new JsonObject();
        JsonArray blockersJson = new JsonArray();
        for (Permanent blocker : possible) {
            JsonArray legal = new JsonArray();
            for (CombatGroup group : game.getCombat().getGroups()) {
                for (UUID attackerId : group.getAttackers()) {
                    if (group.canBlock(blocker, game)) {
                        legal.add(attackerId.toString());
                    }
                }
            }
            JsonObject b = StateView.describe(game, blocker.getId(), getId());
            b.add("attackers", legal);
            blockersJson.add(b);
            allowed.add(blocker.getId().toString(), legal);
            d.addOption(blocker.getId().toString(), blocker.getName()).addProperty("kind", "blocker");
        }
        JsonArray attackersJson = new JsonArray();
        for (CombatGroup group : game.getCombat().getGroups()) {
            for (UUID attackerId : group.getAttackers()) {
                JsonObject a = StateView.describe(game, attackerId, getId());
                a.addProperty("defender", Json.str(group.getDefenderId()));
                Permanent attacker = game.getPermanent(attackerId);
                if (attacker != null) {
                    // blocking requirements the engine enforces on the whole declaration (menace: 2, ...)
                    a.addProperty("min_blockers", Math.max(1, attacker.getMinBlockedBy()));
                    a.addProperty("max_blockers", attacker.getMaxBlockedBy());
                }
                attackersJson.add(a);
            }
        }
        d.extra.add("blockers", blockersJson);
        d.extra.add("attackers", attackersJson);
        d.extra.add("_allowed", allowed);
        String info = seat.takeInfo();
        if (info != null) {
            d.extra.addProperty("error", info);
        }
        if (reask) {
            d.extra.addProperty("rejection", info != null ? info
                    : "The previous block declaration was illegal as a whole (e.g. a creature with menace needs two or"
                    + " more blockers, or a creature must block). Declare again.");
        }
        JsonObject none = new JsonObject();
        none.add("blocks", new JsonArray());
        d.defaultAction = none;

        JsonObject answer = ask(game, d);
        if (answer == null || game.executingRollback()) {
            return;
        }
        String normalized = normalizedBlocks(answer);
        identicalBlockAnswers = reask && normalized.equals(lastBlockAnswer) ? identicalBlockAnswers + 1 : 0;
        lastBlockAnswer = normalized;
        if (identicalBlockAnswers >= MAX_IDENTICAL_ILLEGAL_BLOCKS - 1) {
            // the same illegal assignment again: declare no blocks instead and say so in the record
            seat.session().noteFallback(seat, d, "illegal_repeat",
                    "repeated an illegal block declaration " + MAX_IDENTICAL_ILLEGAL_BLOCKS + " times; no blocks declared");
            for (Permanent blocker : possible) {
                if (blocker.getBlocking() > 0) {
                    game.getCombat().removeBlocker(blocker.getId(), game);
                }
            }
            return;
        }
        // clear a previous (invalid) declaration of ours
        for (Permanent blocker : possible) {
            if (blocker.getBlocking() > 0) {
                game.getCombat().removeBlocker(blocker.getId(), game);
            }
        }
        for (JsonObject pair : pairs(answer, "blocks", "blocker", "attacker")) {
            String blocker = Json.getString(pair, "blocker", null);
            String attacker = Json.getString(pair, "attacker", null);
            if (attacker == null) {
                JsonArray legal = allowed.getAsJsonArray(blocker);
                if (legal == null || legal.size() == 0) {
                    continue;
                }
                attacker = legal.get(0).getAsString();
            }
            declareBlocker(defendingPlayerId, UUID.fromString(blocker), UUID.fromString(attacker), game);
        }
        // Combat validates the configuration and calls selectBlockers again if it is illegal
    }

    private static String normalizedBlocks(JsonObject answer) {
        List<String> pairs = new ArrayList<>();
        for (JsonObject pair : pairs(answer, "blocks", "blocker", "attacker")) {
            pairs.add(Json.getString(pair, "blocker", "") + ">" + Json.getString(pair, "attacker", ""));
        }
        Collections.sort(pairs);
        return String.join(",", pairs);
    }

    // ------------------------------------------------------------------------------------------------
    // mana payment
    // ------------------------------------------------------------------------------------------------

    private transient ManaCost manualUnpaid;

    /**
     * The cost being paid manually (for the pay_mana decision), or null.
     */
    public ManaCost manualUnpaid() {
        return manualUnpaid;
    }

    // guards against auto-payment loops (mana produced but not usable for this cost)
    private transient String autoPayKey;
    private transient int autoPayAttempts;

    @Override
    public boolean playMana(Ability ability, ManaCost unpaid, String promptText, Game game) {
        String key = ability.getId() + "|" + unpaid.getText();
        if (!key.equals(autoPayKey)) {
            autoPayKey = key;
            autoPayAttempts = 0;
        }
        if (canFeedback(game) && seat.config().autoPay && autoPayAttempts++ < 4) {
            boolean oldMode = payManaMode;
            payManaMode = true;
            ManaCost oldUnpaid = currentlyUnpaidMana;
            currentlyUnpaidMana = unpaid;
            try {
                if (tryAutoPay(ability, unpaid, game)) {
                    return true;
                }
            } finally {
                currentlyUnpaidMana = oldUnpaid;
                payManaMode = oldMode;
            }
        }
        // manual payment: raises a PLAY_MANA query (sources to tap, pool mana, special, cancel)
        manualUnpaid = unpaid;
        try {
            return super.playMana(ability, unpaid, promptText, game);
        } finally {
            manualUnpaid = null;
        }
    }

    /**
     * Mana sources the player could tap right now (for manual payment decisions).
     */
    public List<MageObject> manaSources(Game game) {
        List<MageObject> producers = new ArrayList<>(getAvailableManaProducers(game));
        producers.addAll(getAvailableManaProducersWithCost(game));
        // canonical order: which land auto-pay taps must not depend on random object ids
        producers.sort(java.util.Comparator.comparing(o -> StateView.sortKey(game, o.getId())));
        return producers;
    }

    /**
     * Port of ComputerPlayer#playManaHandling: activates one mana ability that helps paying the cost.
     *
     * @return true if a mana ability was activated
     */
    @SuppressWarnings("unchecked")
    private boolean tryAutoPay(Ability ability, ManaCost unpaid, Game game) {
        Set<ApprovingObject> approvingObjects = game.getContinuousEffects().asThough(ability.getSourceId(),
                AsThoughEffectType.SPEND_OTHER_MANA, ability, ability.getControllerId(), game);
        boolean hasApprovingObject = !approvingObjects.isEmpty();

        ManaCost cost;
        List<MageObject> producers;
        if (unpaid instanceof ManaCosts) {
            ManaCosts<ManaCost> manaCosts = (ManaCosts<ManaCost>) unpaid;
            if (manaCosts.isEmpty()) {
                return false;
            }
            cost = manaCosts.get(manaCosts.size() - 1);
            producers = getSortedProducers(manaCosts, game);
        } else {
            cost = unpaid;
            producers = manaSources(game);
        }

        // fully compatible colored producers first
        for (MageObject mageObject : producers) {
            ManaAbility:
            for (ActivatedManaAbilityImpl manaAbility : getManaAbilitiesSortedByManaCount(mageObject, game)) {
                boolean canPayColoredMana = false;
                for (Mana mana : manaAbility.getNetMana(game)) {
                    if (mana.getAny() == 0 && !unpaid.getMana().includesMana(mana)) {
                        continue ManaAbility;
                    }
                    if (mana.countColored() > 0 || mana.getAny() > 0) {
                        canPayColoredMana = true;
                    }
                }
                if (canPayColoredMana && (cost instanceof ColoredManaCost)) {
                    for (Mana netMana : manaAbility.getNetMana(game)) {
                        if (cost.testPay(netMana) || netMana.getAny() > 0) {
                            if (netMana instanceof ConditionalMana && !((ConditionalMana) netMana).apply(ability, game, getId(), cost)) {
                                continue;
                            }
                            if (hasApprovingObject && !canUseAsThoughManaToPayManaCost(cost, ability, netMana, manaAbility, mageObject, game)) {
                                continue;
                            }
                            if (activateAbility(manaAbility, game)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        // then any other producers, by cost type
        Class<?>[] order = {ColoredManaCost.class, SnowManaCost.class, ColorlessManaCost.class, HybridManaCost.class,
                ColorlessHybridManaCost.class, MonoHybridManaCost.class, GenericManaCost.class};
        for (MageObject mageObject : producers) {
            for (Class<?> costType : order) {
                if (!costType.isInstance(cost)) {
                    continue;
                }
                for (ActivatedManaAbilityImpl manaAbility : getManaAbilitiesSortedByManaCount(mageObject, game)) {
                    for (Mana netMana : manaAbility.getNetMana(game)) {
                        if (cost.testPay(netMana) || netMana.getAny() > 0 || hasApprovingObject) {
                            if (netMana instanceof ConditionalMana && !((ConditionalMana) netMana).apply(ability, game, getId(), cost)) {
                                continue;
                            }
                            if (hasApprovingObject && !canUseAsThoughManaToPayManaCost(cost, ability, netMana, manaAbility, mageObject, game)) {
                                continue;
                            }
                            if (activateAbility(manaAbility, game)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean canUseAsThoughManaToPayManaCost(ManaCost checkCost, Ability abilityToPay, Mana manaOption, Ability manaAbility, MageObject manaProducer, Game game) {
        ManaPoolItem possiblePoolItem;
        if (manaOption instanceof ConditionalMana) {
            ConditionalMana conditionalNetMana = (ConditionalMana) manaOption;
            possiblePoolItem = new ManaPoolItem(
                    conditionalNetMana,
                    manaAbility.getSourceObject(game),
                    conditionalNetMana.getManaProducerOriginalId() != null ? conditionalNetMana.getManaProducerOriginalId() : manaAbility.getOriginalId()
            );
        } else {
            possiblePoolItem = new ManaPoolItem(
                    manaOption.getRed(),
                    manaOption.getGreen(),
                    manaOption.getBlue(),
                    manaOption.getWhite(),
                    manaOption.getBlack(),
                    manaOption.getGeneric() + manaOption.getColorless(),
                    manaProducer,
                    manaAbility.getOriginalId(),
                    manaOption.getFlag()
            );
        }
        for (ManaType checkType : ManaUtil.getManaTypesInCost(checkCost)) {
            ManaType possibleAsThoughPoolManaType = game.getContinuousEffects().asThoughMana(checkType, possiblePoolItem,
                    abilityToPay.getSourceId(), abilityToPay, abilityToPay.getControllerId(), game);
            if (possibleAsThoughPoolManaType == null) {
                continue;
            }
            boolean canPay;
            if (possibleAsThoughPoolManaType == ManaType.COLORLESS) {
                canPay = possiblePoolItem.count() > 0;
            } else {
                canPay = possiblePoolItem.get(possibleAsThoughPoolManaType) > 0;
            }
            if (canPay) {
                return true;
            }
        }
        return false;
    }

    private Abilities<ActivatedManaAbilityImpl> getManaAbilitiesSortedByManaCount(MageObject mageObject, final Game game) {
        Abilities<ActivatedManaAbilityImpl> manaAbilities = mageObject.getAbilities().getAvailableActivatedManaAbilities(Zone.BATTLEFIELD, getId(), game);
        if (manaAbilities.size() > 1) {
            Collections.sort(manaAbilities, (a1, a2) -> {
                int a1Max = 0;
                for (Mana netMana : a1.getNetMana(game)) {
                    a1Max = Math.max(a1Max, netMana.count());
                }
                int a2Max = 0;
                for (Mana netMana : a2.getNetMana(game)) {
                    a2Max = Math.max(a2Max, netMana.count());
                }
                return CardUtil.overflowDec(a2Max, a1Max);
            });
        }
        return manaAbilities;
    }

    /**
     * Producers that can pay fewer of the unpaid costs come first, so flexible sources are saved.
     */
    private List<MageObject> getSortedProducers(ManaCosts<ManaCost> unpaid, Game game) {
        List<MageObject> unsorted = manaSources(game);
        Map<MageObject, Integer> scored = new LinkedHashMap<>(); // keeps the canonical order for equal scores
        for (MageObject mageObject : unsorted) {
            int score = 0;
            for (ManaCost cost : unpaid) {
                Abilities:
                for (ActivatedManaAbilityImpl ability : mageObject.getAbilities().getAvailableActivatedManaAbilities(Zone.BATTLEFIELD, getId(), game)) {
                    for (Mana netMana : ability.getNetMana(game)) {
                        if (cost.testPay(netMana)) {
                            score++;
                            break Abilities;
                        }
                    }
                }
            }
            if (score > 0) {
                score += mageObject.getAbilities().getAvailableActivatedManaAbilities(Zone.BATTLEFIELD, getId(), game).size();
                score += mageObject.getAbilities().getActivatedAbilities(Zone.BATTLEFIELD).size();
                if (!mageObject.getCardType(game).contains(CardType.LAND)) {
                    score += 2;
                } else if (mageObject.getCardType(game).contains(CardType.CREATURE)) {
                    score += 2;
                }
            }
            scored.put(mageObject, score);
        }
        List<Map.Entry<MageObject, Integer>> list = new LinkedList<>(scored.entrySet());
        list.sort(Map.Entry.comparingByValue());
        List<MageObject> result = new ArrayList<>();
        for (Map.Entry<MageObject, Integer> entry : list) {
            result.add(entry.getKey());
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------
    // dialogs raised by HumanPlayer: record rich context for the query translator
    // ------------------------------------------------------------------------------------------------

    private <T> T withContext(QueryContext ctx, Supplier<T> body) {
        contexts.push(ctx);
        try {
            return body.get();
        } finally {
            contexts.pop();
        }
    }

    @Override
    public boolean chooseMulligan(Game game) {
        return withContext(new QueryContext(Decision.MULLIGAN, null, null, null), () -> super.chooseMulligan(game));
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game) {
        return withContext(new QueryContext(null, null, source, null),
                () -> super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game));
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        return withContext(new QueryContext(null, target, source, null),
                () -> super.choose(outcome, target, source, game, options));
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        return withContext(new QueryContext(null, target, source, null),
                () -> super.chooseTarget(outcome, target, source, game));
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        return withContext(new QueryContext(null, target, source, cards),
                () -> super.choose(outcome, cards, target, source, game));
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        return withContext(new QueryContext(null, target, source, cards),
                () -> super.chooseTarget(outcome, cards, target, source, game));
    }

    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        return withContext(new QueryContext(null, target, source, null),
                () -> super.chooseTargetAmount(outcome, target, source, game));
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        return withContext(new QueryContext(null, null, null, null), () -> super.choose(outcome, choice, game));
    }

    @Override
    public int getAmount(int min, int max, String message, Ability source, Game game) {
        return withContext(new QueryContext(null, null, source, null), () -> super.getAmount(min, max, message, source, game));
    }

    @Override
    public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
        return withContext(new QueryContext("announce_x", null, source, null),
                () -> super.announceX(min, max, message, game, source, isManaPay));
    }

    /**
     * Current step helper for stop policies.
     */
    public static boolean isStep(Game game, PhaseStep step) {
        return game.getTurnStepType() == step;
    }
}
