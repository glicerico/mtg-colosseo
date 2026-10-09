package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.cards.Card;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.DamagedEvent;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import mage.game.stack.Spell;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The browser's account of what happens, one engine event at a time ({@code event} messages, browser views only):
 * spells cast and abilities activated or triggered, with their targets; attackers and blockers declared; damage and
 * life; cards moving between zones, and for a spell leaving the stack whether it resolved, was countered or fizzled;
 * abilities and copies of spells countered. XMage only reports state changes at priority rounds and
 * resolutions, so without this a browser never sees an opponent's spell on the stack, its targets or a trigger.
 * <p>
 * Events name objects by id only; names and pictures come from the viewer's state sent with each event, so they are
 * masked exactly as the state is. A card moving between zones the viewer can't see (the opponent drawing) is sent
 * without its id. Building an event only reads the game. Must be called from the game thread.
 */
final class EventFeed {

    static final Set<GameEvent.EventType> TYPES = EnumSet.of(
            GameEvent.EventType.SPELL_CAST, GameEvent.EventType.ACTIVATED_ABILITY, GameEvent.EventType.TRIGGERED_ABILITY,
            GameEvent.EventType.ATTACKER_DECLARED, GameEvent.EventType.BLOCKER_DECLARED,
            GameEvent.EventType.DAMAGED_PERMANENT, GameEvent.EventType.DAMAGED_PLAYER,
            GameEvent.EventType.GAINED_LIFE, GameEvent.EventType.LOST_LIFE,
            GameEvent.EventType.ZONE_CHANGE, GameEvent.EventType.COUNTERED);

    private long seq;
    /** per view (a seat, the spectators, the revealed spectators): the ids its last state showed */
    private final Map<String, Set<String>> lastVisible = new HashMap<>();

    /**
     * What happened, independent of the viewer, or null when there is nothing to show (e.g. an ability that doesn't
     * use the stack, a card moving within one zone). {@code logAt}: how many log lines there were when it happened;
     * XMage writes the line just before or just after the event.
     */
    JsonObject describe(Game game, GameEvent e, int logAt) {
        JsonObject o = new JsonObject();
        switch (e.getType()) {
            case SPELL_CAST: {
                Spell spell = spell(game, e);
                if (spell == null) {
                    return null;
                }
                o.addProperty("kind", "cast");
                o.addProperty("id", Json.str(spell.getId()));
                o.addProperty("card_id", Json.str(spell.getCard().getId()));
                o.addProperty("player", Json.str(e.getPlayerId()));
                o.addProperty("from", e.getZone() == null ? null : e.getZone().name());
                o.add("targets", Json.ids(StateView.targetsOf(spell.getSpellAbility())));
                break;
            }
            case ACTIVATED_ABILITY:
            case TRIGGERED_ABILITY: {
                StackAbility ability = ability(game, e);
                if (ability == null) {
                    return null;
                }
                o.addProperty("kind", e.getType() == GameEvent.EventType.TRIGGERED_ABILITY ? "trigger" : "ability");
                o.addProperty("id", Json.str(ability.getId()));
                o.addProperty("source_id", Json.str(ability.getSourceId()));
                o.addProperty("player", Json.str(ability.getControllerId()));
                o.add("targets", Json.ids(StateView.targetsOf(ability.getStackAbility())));
                break;
            }
            case ATTACKER_DECLARED:
                o.addProperty("kind", "attack");
                o.addProperty("attacker", Json.str(e.getSourceId()));
                o.addProperty("defender", Json.str(e.getTargetId()));
                o.addProperty("player", Json.str(e.getPlayerId()));
                break;
            case BLOCKER_DECLARED:
                o.addProperty("kind", "block");
                o.addProperty("blocker", Json.str(e.getSourceId()));
                o.addProperty("attacker", Json.str(e.getTargetId()));
                o.addProperty("player", Json.str(e.getPlayerId()));
                break;
            case DAMAGED_PERMANENT:
            case DAMAGED_PLAYER:
                o.addProperty("kind", "damage");
                o.addProperty("target", Json.str(e.getTargetId()));
                o.addProperty("source", Json.str(e.getSourceId()));
                o.addProperty("amount", e.getAmount());
                o.addProperty("combat", e instanceof DamagedEvent && ((DamagedEvent) e).isCombatDamage());
                o.addProperty("to_player", e.getType() == GameEvent.EventType.DAMAGED_PLAYER);
                break;
            case GAINED_LIFE:
            case LOST_LIFE:
                if (e.getAmount() == 0) {
                    return null;
                }
                o.addProperty("kind", "life");
                o.addProperty("player", Json.str(e.getPlayerId()));
                o.addProperty("amount", e.getType() == GameEvent.EventType.GAINED_LIFE ? e.getAmount() : -e.getAmount());
                o.addProperty("source", Json.str(e.getSourceId()));
                break;
            case ZONE_CHANGE: {
                ZoneChangeEvent z = (ZoneChangeEvent) e;
                if (z.getFromZone() == z.getToZone() || z.getToZone() == null) {
                    return null;
                }
                o.addProperty("kind", "move");
                o.addProperty("id", Json.str(z.getTargetId()));
                o.addProperty("from", zone(z.getFromZone()));
                o.addProperty("to", zone(z.getToZone()));
                o.addProperty("owner", Json.str(owner(game, z)));
                Permanent left = z.getTarget();
                Permanent entered = game.getPermanent(z.getTargetId());
                o.addProperty("token", left instanceof PermanentToken || entered instanceof PermanentToken);
                if (z.getFromZone() == Zone.STACK) {
                    // a spell leaving the stack: whether it resolved, was countered (by what) or fizzled
                    String reason = leftStack(game, z);
                    if (reason != null) {
                        o.addProperty("reason", reason);
                        if ("countered".equals(reason) && z.getSourceId() != null) {
                            o.addProperty("by", Json.str(z.getSourceId()));
                        }
                    }
                }
                break;
            }
            case COUNTERED: {
                // a countered spell's move off the stack already says so (reason, by): this is for what has no move,
                // an ability or a copy of a spell
                MageObject countered = game.getLastKnownInformation(e.getTargetId(), Zone.STACK);
                if (countered instanceof Spell && !((Spell) countered).isCopy()) {
                    return null;
                }
                o.addProperty("kind", "countered");
                o.addProperty("id", Json.str(e.getTargetId()));
                if (countered instanceof StackObject) {
                    o.addProperty("source_id", Json.str(((StackObject) countered).getSourceId()));
                }
                if (countered instanceof Spell) {
                    o.addProperty("copy", true);
                }
                o.addProperty("by", Json.str(e.getSourceId()));
                o.addProperty("player", Json.str(e.getPlayerId()));
                break;
            }
            default:
                return null;
        }
        o.addProperty("seq", ++seq);
        o.addProperty("log_at", logAt);
        return o;
    }

    /**
     * The message for one view: the event and the view's state right after it. A moved object the view saw neither
     * before nor after the move (a card going from the opponent's library to their hand) loses its id.
     */
    JsonObject message(JsonObject event, JsonObject state, String view) {
        Set<String> after = visible(state);
        Set<String> before = lastVisible.getOrDefault(view, Set.of());
        lastVisible.put(view, after);
        JsonObject ev = event;
        if ("move".equals(Json.getString(event, "kind", "")) && event.has("id") && !event.get("id").isJsonNull()) {
            String id = event.get("id").getAsString();
            if (!after.contains(id) && !before.contains(id)) {
                ev = event.deepCopy();
                ev.remove("id");
                ev.addProperty("hidden", true);
            }
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "event");
        msg.add("event", ev);
        msg.add("state", state);
        return msg;
    }

    /** Notes what a view's state shows (states sent outside events, e.g. at priority rounds). */
    void seen(String view, JsonObject state) {
        if (state != null) {
            lastVisible.put(view, visible(state));
        }
    }

    /** The ids of the objects a state shows: battlefields, graveyards, hands it reveals, the stack, exile. */
    static Set<String> visible(JsonObject state) {
        Set<String> ids = new HashSet<>();
        for (JsonElement p : array(state, "players")) {
            JsonObject player = p.getAsJsonObject();
            for (String zone : new String[]{"battlefield", "graveyard", "hand"}) {
                for (JsonElement c : array(player, zone)) {
                    add(ids, c.getAsJsonObject(), "id");
                }
            }
        }
        for (JsonElement s : array(state, "stack")) {
            add(ids, s.getAsJsonObject(), "id");
            add(ids, s.getAsJsonObject(), "card_id");
        }
        for (JsonElement c : array(state, "exile")) {
            JsonObject card = c.getAsJsonObject();
            if (!card.has("hidden")) {
                add(ids, card, "id");
            }
        }
        return ids;
    }

    private static JsonArray array(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static void add(Set<String> ids, JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            ids.add(o.get(key).getAsString());
        }
    }

    private static Spell spell(Game game, GameEvent e) {
        // SPELL_CAST names the spell's ability; its source is the card
        for (StackObject so : game.getStack()) {
            if (so instanceof Spell && (((Spell) so).getSpellAbility().getId().equals(e.getTargetId())
                    || so.getSourceId().equals(e.getSourceId()))) {
                return (Spell) so;
            }
        }
        return null;
    }

    private static StackAbility ability(Game game, GameEvent e) {
        StackAbility bySource = null;
        for (StackObject so : game.getStack()) {
            if (so instanceof StackAbility) {
                if (so.getId().equals(e.getTargetId()) || so.getStackAbility().getId().equals(e.getTargetId())) {
                    return (StackAbility) so;
                }
                if (bySource == null && so.getSourceId().equals(e.getSourceId())) {
                    bySource = (StackAbility) so;
                }
            }
        }
        return bySource;
    }

    /**
     * Why a spell left the stack, from the spell as it was then (XMage keeps it as last known information when it
     * leaves): "resolved"; "countered", by a spell or ability; or "fizzled", when all its targets were illegal as it
     * began to resolve (608.2b; XMage counters it then, while it resolves). Null for anything else, e.g. a spell an
     * effect exiled from the stack.
     */
    static String leftStack(Game game, ZoneChangeEvent z) {
        MageObject object = game.getLastKnownInformation(z.getTargetId(), Zone.STACK);
        if (!(object instanceof Spell)) {
            return null;
        }
        Spell spell = (Spell) object;
        if (spell.isCountered()) {
            return spell.isResolving() ? "fizzled" : "countered";
        }
        return spell.isResolving() ? "resolved" : null;
    }

    private static UUID owner(Game game, ZoneChangeEvent z) {
        if (z.getTarget() != null) {
            return z.getTarget().getOwnerId();
        }
        Card card = game.getCard(z.getTargetId());
        if (card != null) {
            return card.getOwnerId();
        }
        Permanent perm = game.getPermanent(z.getTargetId());
        return perm == null ? z.getPlayerId() : perm.getOwnerId();
    }

    private static String zone(Zone zone) {
        return zone == null ? null : zone.name();
    }
}
