package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.cards.Card;
import mage.cards.Cards;
import mage.constants.CardType;
import mage.constants.ManaType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.Counter;
import mage.counters.CounterType;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.game.command.CommandObject;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import mage.game.stack.Spell;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.players.ManaPool;
import mage.players.Player;
import mage.target.Target;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Serializes the game state into the JSON observation shared by agents, the web UI and spectators.
 * <p>
 * Hidden information is respected: a player sees its own hand but only the size of the opponent's hand,
 * face-down objects are masked for non-controllers and libraries are never revealed. Spectators may
 * request an omniscient view (all hands visible) which is useful to watch agents play.
 * <p>
 * Must be called from the game thread.
 */
public final class StateView {

    private StateView() {
    }

    /**
     * @param viewerId  player the view is built for (null for spectators)
     * @param revealAll show every hand (spectator omniscient mode)
     */
    public static JsonObject build(Game game, UUID viewerId, boolean revealAll, Map<UUID, Integer> seatOf) {
        JsonObject o = new JsonObject();
        o.addProperty("turn", game.getTurnNum());
        o.addProperty("phase", game.getTurnPhaseType() == null ? null : game.getTurnPhaseType().name());
        o.addProperty("step", game.getTurnStepType() == null ? null : game.getTurnStepType().name());
        o.addProperty("active_player", Json.str(game.getActivePlayerId()));
        o.addProperty("priority_player", Json.str(game.getState().getPriorityPlayerId()));
        o.addProperty("you", Json.str(viewerId));

        JsonArray players = new JsonArray();
        for (Player player : game.getState().getPlayers().values()) {
            players.add(player(game, player, viewerId, revealAll, seatOf));
        }
        o.add("players", players);

        JsonArray stack = new JsonArray();
        for (StackObject so : game.getStack()) {
            stack.add(stackObject(game, so, viewerId));
        }
        o.add("stack", stack);

        o.add("combat", combat(game));

        JsonArray exile = new JsonArray();
        for (ExileZone zone : game.getExile().getExileZones()) {
            for (Card card : zone.getCards(game)) {
                JsonObject c = hiddenOr(game, card, viewerId, revealAll);
                c.addProperty("owner", Json.str(card.getOwnerId()));
                c.addProperty("exile_zone", zone.getName());
                exile.add(c);
            }
        }
        o.add("exile", exile);

        JsonArray revealed = new JsonArray();
        for (Map.Entry<String, Cards> e : game.getState().getRevealed().entrySet()) {
            JsonObject r = new JsonObject();
            r.addProperty("name", e.getKey());
            r.add("cards", cards(game, e.getValue().getCards(game)));
            revealed.add(r);
        }
        o.add("revealed", revealed);

        JsonArray lookedAt = new JsonArray();
        if (viewerId != null) {
            for (Map.Entry<String, Cards> e : game.getState().getLookedAt(viewerId).entrySet()) {
                JsonObject r = new JsonObject();
                r.addProperty("name", e.getKey());
                r.add("cards", cards(game, e.getValue().getCards(game)));
                lookedAt.add(r);
            }
        }
        o.add("looked_at", lookedAt);

        JsonArray command = new JsonArray();
        for (CommandObject co : game.getState().getCommand()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", Json.str(co.getId()));
            c.addProperty("name", co.getName());
            c.addProperty("controller", Json.str(co.getControllerId()));
            command.add(c);
        }
        o.add("command", command);
        return o;
    }

    private static JsonObject player(Game game, Player player, UUID viewerId, boolean revealAll, Map<UUID, Integer> seatOf) {
        JsonObject p = new JsonObject();
        p.addProperty("id", Json.str(player.getId()));
        p.addProperty("name", player.getName());
        if (seatOf != null && seatOf.containsKey(player.getId())) {
            p.addProperty("seat", seatOf.get(player.getId()));
        }
        p.addProperty("life", player.getLife());
        p.addProperty("library_count", player.getLibrary().size());
        p.addProperty("hand_count", player.getHand().size());
        p.addProperty("lands_played", player.getLandsPlayed());
        p.addProperty("is_active", player.getId().equals(game.getActivePlayerId()));
        p.addProperty("has_priority", player.getId().equals(game.getState().getPriorityPlayerId()));
        p.addProperty("has_lost", player.hasLost());
        p.addProperty("has_won", player.hasWon());
        p.addProperty("has_left", player.hasLeft());

        JsonObject counters = new JsonObject();
        for (Counter c : player.getCountersAsCopy().values()) {
            counters.addProperty(c.getName(), c.getCount());
        }
        p.add("counters", counters);

        ManaPool pool = player.getManaPool();
        JsonObject mana = new JsonObject();
        mana.addProperty("W", pool.get(ManaType.WHITE));
        mana.addProperty("U", pool.get(ManaType.BLUE));
        mana.addProperty("B", pool.get(ManaType.BLACK));
        mana.addProperty("R", pool.get(ManaType.RED));
        mana.addProperty("G", pool.get(ManaType.GREEN));
        mana.addProperty("C", pool.get(ManaType.COLORLESS));
        p.add("mana_pool", mana);

        boolean seesHand = revealAll || player.getId().equals(viewerId);
        if (seesHand) {
            p.add("hand", cards(game, player.getHand().getCards(game)));
        }
        p.add("graveyard", cards(game, player.getGraveyard().getCards(game)));

        JsonArray battlefield = new JsonArray();
        for (Permanent perm : game.getBattlefield().getAllPermanents()) {
            if (perm.isControlledBy(player.getId()) && perm.isPhasedIn()) {
                battlefield.add(permanent(game, perm, viewerId, revealAll));
            }
        }
        p.add("battlefield", battlefield);
        return p;
    }

    public static JsonArray cards(Game game, Iterable<? extends Card> cards) {
        JsonArray arr = new JsonArray();
        for (Card card : cards) {
            if (card != null) {
                arr.add(card(game, card));
            }
        }
        return arr;
    }

    private static JsonObject hiddenOr(Game game, Card card, UUID viewerId, boolean revealAll) {
        if (card.isFaceDown(game) && !revealAll && (viewerId == null || !card.isOwnedBy(viewerId))) {
            JsonObject c = new JsonObject();
            c.addProperty("id", Json.str(card.getId()));
            c.addProperty("name", "Face-down card");
            c.addProperty("hidden", true);
            return c;
        }
        return card(game, card);
    }

    /**
     * Static + dynamic properties of a card-like object (card, permanent, spell card, token).
     */
    public static JsonObject card(Game game, MageObject obj) {
        JsonObject c = new JsonObject();
        c.addProperty("id", Json.str(obj.getId()));
        c.addProperty("name", obj.getName());
        c.addProperty("set", obj.getExpansionSetCode());
        c.addProperty("number", obj.getCardNumber());
        c.addProperty("mana_cost", obj.getManaCost().getText());
        c.addProperty("mana_value", obj.getManaValue());

        JsonArray types = new JsonArray();
        for (CardType t : obj.getCardType(game)) {
            types.add(t.toString());
        }
        c.add("types", types);
        JsonArray subtypes = new JsonArray();
        for (SubType t : obj.getSubtype(game)) {
            subtypes.add(t.toString());
        }
        c.add("subtypes", subtypes);
        JsonArray supertypes = new JsonArray();
        for (SuperType t : obj.getSuperType(game)) {
            supertypes.add(t.toString());
        }
        c.add("supertypes", supertypes);
        c.addProperty("colors", obj.getColor(game).toString());

        if (obj.isCreature(game)) {
            c.addProperty("power", obj.getPower().getValue());
            c.addProperty("toughness", obj.getToughness().getValue());
        }
        c.add("rules", rules(game, obj));

        JsonObject counters = new JsonObject();
        if (obj instanceof Card) {
            Card card = (Card) obj;
            for (Counter counter : card.getCounters(game).values()) {
                counters.addProperty(counter.getName(), counter.getCount());
            }
            if (obj.isPlaneswalker(game)) {
                c.addProperty("loyalty", card.getCounters(game).getCount(CounterType.LOYALTY));
            }
            c.addProperty("owner", Json.str(card.getOwnerId()));
        }
        c.add("counters", counters);
        c.addProperty("token", obj instanceof PermanentToken);
        return c;
    }

    private static JsonArray rules(Game game, MageObject obj) {
        List<String> raw;
        if (obj instanceof Permanent) {
            raw = ((Permanent) obj).getRules(game);
        } else if (obj instanceof Card) {
            raw = ((Card) obj).getRules(game);
        } else {
            raw = new ArrayList<>();
        }
        JsonArray arr = new JsonArray();
        for (String line : raw) {
            String t = Json.plain(line);
            if (!t.isEmpty()) {
                arr.add(t);
            }
        }
        return arr;
    }

    public static JsonObject permanent(Game game, Permanent perm, UUID viewerId, boolean revealAll) {
        JsonObject c;
        boolean masked = perm.isFaceDown(game) && !revealAll && (viewerId == null || !perm.isControlledBy(viewerId));
        if (masked) {
            // face-down permanents are 2/2 creatures with no name/abilities for opponents
            c = new JsonObject();
            c.addProperty("id", Json.str(perm.getId()));
            c.addProperty("name", "Face-down creature");
            c.addProperty("hidden", true);
            JsonArray types = new JsonArray();
            for (CardType t : perm.getCardType(game)) {
                types.add(t.toString());
            }
            c.add("types", types);
            c.addProperty("power", perm.getPower().getValue());
            c.addProperty("toughness", perm.getToughness().getValue());
        } else {
            c = card(game, perm);
        }
        c.addProperty("face_down", perm.isFaceDown(game));
        c.addProperty("controller", Json.str(perm.getControllerId()));
        c.addProperty("owner", Json.str(perm.getOwnerId()));
        c.addProperty("tapped", perm.isTapped());
        c.addProperty("summoning_sick", perm.isCreature(game) && perm.hasSummoningSickness());
        c.addProperty("damage", perm.getDamage());
        c.addProperty("attacking", perm.isAttacking());
        c.addProperty("blocking", perm.getBlocking() > 0);
        c.addProperty("attached_to", Json.str(perm.getAttachedTo()));
        c.add("attachments", Json.ids(perm.getAttachments()));
        c.addProperty("transformed", perm.isTransformed());
        return c;
    }

    private static JsonObject stackObject(Game game, StackObject so, UUID viewerId) {
        JsonObject s;
        if (so instanceof Spell) {
            Spell spell = (Spell) so;
            s = card(game, spell.getCard());
            s.addProperty("id", Json.str(spell.getId()));
            s.addProperty("card_id", Json.str(spell.getCard().getId()));
            s.addProperty("kind", "spell");
            s.addProperty("name", spell.getName());
            s.add("targets", Json.ids(targetsOf(spell.getSpellAbility())));
        } else {
            s = new JsonObject();
            s.addProperty("id", Json.str(so.getId()));
            s.addProperty("kind", "ability");
            MageObject source = game.getObject(so.getSourceId());
            String sourceName = source == null ? "" : source.getName();
            s.addProperty("name", "Ability of " + sourceName);
            s.addProperty("source_id", Json.str(so.getSourceId()));
            s.addProperty("source_name", sourceName);
            if (source != null) {
                s.addProperty("set", source.getExpansionSetCode());
                s.addProperty("number", source.getCardNumber());
            }
            String rule = so instanceof StackAbility ? ((StackAbility) so).getRule() : so.getStackAbility().getRule();
            JsonArray rules = new JsonArray();
            rules.add(Json.plain(rule));
            s.add("rules", rules);
            s.add("targets", Json.ids(targetsOf(so.getStackAbility())));
        }
        s.addProperty("controller", Json.str(so.getControllerId()));
        return s;
    }

    public static Set<UUID> targetsOf(Ability ability) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (ability == null) {
            return ids;
        }
        for (UUID modeId : ability.getModes().getSelectedModes()) {
            Mode mode = ability.getModes().get(modeId);
            if (mode == null) {
                continue;
            }
            for (Target t : mode.getTargets()) {
                ids.addAll(t.getTargets());
            }
        }
        return ids;
    }

    private static JsonObject combat(Game game) {
        JsonObject c = new JsonObject();
        c.addProperty("attacking_player", Json.str(game.getCombat().getAttackingPlayerId()));
        JsonArray groups = new JsonArray();
        for (CombatGroup group : game.getCombat().getGroups()) {
            JsonObject g = new JsonObject();
            g.add("attackers", Json.ids(group.getAttackers()));
            g.add("blockers", Json.ids(group.getBlockers()));
            g.addProperty("defender", Json.str(group.getDefenderId()));
            g.addProperty("blocked", group.getBlocked());
            groups.add(g);
        }
        c.add("groups", groups);
        return c;
    }

    /**
     * Short description of any game object id (player, permanent, card, stack object), used for decision options.
     */
    public static JsonObject describe(Game game, UUID id, UUID viewerId) {
        JsonObject o = new JsonObject();
        o.addProperty("id", Json.str(id));
        Player player = game.getPlayer(id);
        if (player != null) {
            o.addProperty("kind", "player");
            o.addProperty("name", player.getName());
            o.addProperty("life", player.getLife());
            return o;
        }
        Permanent perm = game.getPermanent(id);
        if (perm != null) {
            boolean masked = perm.isFaceDown(game) && (viewerId == null || !perm.isControlledBy(viewerId));
            o.addProperty("kind", "permanent");
            o.addProperty("name", masked ? "Face-down creature" : perm.getName());
            o.addProperty("zone", Zone.BATTLEFIELD.name());
            o.addProperty("controller", Json.str(perm.getControllerId()));
            if (perm.isCreature(game)) {
                o.addProperty("power", perm.getPower().getValue());
                o.addProperty("toughness", perm.getToughness().getValue());
            }
            o.addProperty("tapped", perm.isTapped());
            return o;
        }
        StackObject so = game.getStack().getStackObject(id);
        if (so != null) {
            o.addProperty("kind", so instanceof Spell ? "spell" : "ability");
            o.addProperty("name", so.getName());
            o.addProperty("zone", Zone.STACK.name());
            o.addProperty("controller", Json.str(so.getControllerId()));
            return o;
        }
        Card card = game.getCard(id);
        if (card != null) {
            Zone zone = game.getState().getZone(id);
            boolean hidden = (zone == Zone.HAND && viewerId != null && !card.isOwnedBy(viewerId))
                    || (zone == Zone.LIBRARY) && false;
            o.addProperty("kind", "card");
            o.addProperty("name", hidden ? "Hidden card" : card.getName());
            o.addProperty("zone", zone == null ? null : zone.name());
            o.addProperty("owner", Json.str(card.getOwnerId()));
            return o;
        }
        MageObject obj = game.getObject(id);
        if (obj != null) {
            o.addProperty("kind", "object");
            o.addProperty("name", obj.getName());
            return o;
        }
        o.addProperty("kind", "unknown");
        o.addProperty("name", id == null ? "" : id.toString());
        return o;
    }
}
