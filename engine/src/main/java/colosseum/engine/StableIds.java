package colosseum.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.cards.Card;
import mage.counters.Counter;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Names for game objects that are the same in every run of a seed, unlike XMage's random object ids: a player is
 * {@code p<seat>}, a deck card {@code c<seat>.<n>} (its position in the deck as loaded), a token {@code t<n>.<k>}
 * (its creation number, and its battlefield order among the tokens made together), a spell {@code s(<card>)} and
 * an ability on the stack {@code a(<source>)#<k>}. Used by {@link ReplayableAiPlayer} to record XMage AI moves in
 * one run and apply them in another.
 */
final class StableIds {

    /** by game id (copies of a game share it), while the game's session runs */
    private static final Map<UUID, StableIds> BY_GAME = new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<UUID, String> fixed = new HashMap<>();

    static void register(Game game, StableIds ids) {
        BY_GAME.put(game.getId(), ids);
    }

    static void unregister(Game game) {
        BY_GAME.remove(game.getId());
    }

    /**
     * The stable name of an object, to order objects that are otherwise equal ({@link StateView#sortKey}); empty for
     * a game without a session.
     */
    static String tiebreak(Game game, UUID id) {
        StableIds ids = game == null ? null : BY_GAME.get(game.getId());
        return ids == null ? "" : ids.key(game, id);
    }
    private final Map<String, UUID> back = new HashMap<>();

    void addPlayer(UUID id, int seat) {
        put(id, "p" + seat);
    }

    void addDeck(int seat, Collection<Card> cards) {
        int n = 0;
        for (Card card : cards) {
            put(card.getId(), "c" + seat + "." + n++);
        }
    }

    private void put(UUID id, String key) {
        fixed.put(id, key);
        back.put(key, id);
    }

    String key(Game game, UUID id) {
        if (id == null) {
            return null;
        }
        String k = fixed.get(id);
        if (k != null) {
            return k;
        }
        Permanent perm = game.getPermanent(id);
        if (perm != null) {
            // tokens made by one effect share their creation number: k-th of them in battlefield order
            int before = 0;
            for (Permanent p : game.getBattlefield().getAllPermanents()) {
                if (p == perm || p.getId().equals(id)) {
                    break;
                }
                if (p.getCreateOrder() == perm.getCreateOrder() && !fixed.containsKey(p.getId())) {
                    before++;
                }
            }
            return "t" + perm.getCreateOrder() + "." + before;
        }
        StackObject so = null;
        for (StackObject candidate : game.getStack()) {
            // not SpellStack.getStackObject: it also matches a spell by its card's id
            if (candidate.getId().equals(id)) {
                so = candidate;
                break;
            }
        }
        if (so != null) {
            if (so instanceof Spell) {
                return "s(" + key(game, so.getSourceId()) + ")";
            }
            int ordinal = 0;
            for (StackObject other : game.getStack()) {
                if (other == so) {
                    break;
                }
                if (!(other instanceof Spell) && other.getSourceId() != null && other.getSourceId().equals(so.getSourceId())) {
                    ordinal++;
                }
            }
            return "a(" + key(game, so.getSourceId()) + ")#" + ordinal;
        }
        MageObject obj = game.getObject(id);
        return "u:" + (obj == null ? "?" : obj.getName());
    }

    /**
     * The live id of a stable name, or null when nothing in this game has that name.
     */
    UUID resolve(Game game, String key) {
        if (key == null) {
            return null;
        }
        UUID id = back.get(key);
        if (id != null) {
            return id;
        }
        if (key.startsWith("t")) {
            int order;
            int k;
            try {
                int dot = key.indexOf('.');
                order = Integer.parseInt(key.substring(1, dot));
                k = Integer.parseInt(key.substring(dot + 1));
            } catch (RuntimeException e) {
                return null;
            }
            for (Permanent p : game.getBattlefield().getAllPermanents()) {
                if (p.getCreateOrder() == order && !fixed.containsKey(p.getId()) && k-- == 0) {
                    return p.getId();
                }
            }
            return null;
        }
        if (key.startsWith("s(") && key.endsWith(")")) {
            UUID src = resolve(game, key.substring(2, key.length() - 1));
            for (StackObject so : game.getStack()) {
                if (so instanceof Spell && src != null && src.equals(so.getSourceId())) {
                    return so.getId();
                }
            }
            return null;
        }
        if (key.startsWith("a(")) {
            int close = key.lastIndexOf(")#");
            if (close < 0) {
                return null;
            }
            UUID src = resolve(game, key.substring(2, close));
            int want = Integer.parseInt(key.substring(close + 2));
            int ordinal = 0;
            for (StackObject so : game.getStack()) {
                if (!(so instanceof Spell) && src != null && src.equals(so.getSourceId())) {
                    if (ordinal++ == want) {
                        return so.getId();
                    }
                }
            }
        }
        return null;
    }

    private List<String> keys(Game game, Collection<UUID> ids, boolean sort) {
        List<String> out = new ArrayList<>();
        for (UUID id : ids) {
            out.add(key(game, id));
        }
        if (sort) {
            Collections.sort(out);
        }
        return out;
    }

    private static JsonArray arr(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    /**
     * The visible and hidden state of the game in stable names, compared between a recorded run and its replay
     * at every decision of a replayed XMage seat.
     */
    JsonObject state(Game game) {
        JsonObject s = new JsonObject();
        s.addProperty("turn", game.getTurnNum());
        s.addProperty("step", game.getTurnStepType() == null ? null : game.getTurnStepType().name());
        s.addProperty("active", key(game, game.getActivePlayerId()));
        s.addProperty("priority", key(game, game.getState().getPriorityPlayerId()));
        JsonArray players = new JsonArray();
        for (Player p : game.getPlayers().values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", key(game, p.getId()));
            o.addProperty("life", p.getLife());
            o.addProperty("counters", p.getCountersAsCopy().getTotalCount());
            o.add("hand", arr(keys(game, p.getHand(), true)));
            o.addProperty("library", p.getLibrary().size());
            // the library's order (hidden, but what later draws and searches find) as a short hash
            o.addProperty("library_order", DeckLibrary.sha256(String.join(",", keys(game, p.getLibrary().getCardList(), false))).substring(0, 16));
            o.add("graveyard", arr(keys(game, p.getGraveyard(), false)));
            o.addProperty("lands_played", p.getLandsPlayed());
            players.add(o);
        }
        s.add("players", players);
        List<String> exile = new ArrayList<>();
        for (Card c : game.getExile().getAllCards(game)) {
            exile.add(key(game, c.getId()));
        }
        Collections.sort(exile);
        s.add("exile", arr(exile));
        List<String> bf = new ArrayList<>();
        for (Permanent p : game.getBattlefield().getAllPermanents()) {
            StringBuilder b = new StringBuilder(key(game, p.getId()));
            b.append('|').append(p.getName()).append('|').append(key(game, p.getControllerId()))
                    .append('|').append(p.isTapped() ? "T" : "U").append('|').append(p.getDamage());
            if (p.isCreature(game)) {
                b.append('|').append(p.getPower().getValue()).append('/').append(p.getToughness().getValue());
            }
            List<String> counters = new ArrayList<>();
            for (Counter c : p.getCounters(game).values()) {
                counters.add(c.getName() + "=" + c.getCount());
            }
            Collections.sort(counters);
            b.append('|').append(String.join(",", counters));
            if (p.getAttachedTo() != null) {
                b.append("|on:").append(key(game, p.getAttachedTo()));
            }
            bf.add(b.toString());
        }
        Collections.sort(bf);
        s.add("battlefield", arr(bf));
        List<String> stack = new ArrayList<>();
        for (StackObject so : game.getStack()) {
            stack.add(key(game, so.getId()) + "|" + so.getName());
        }
        s.add("stack", arr(stack));
        List<String> combat = new ArrayList<>();
        game.getCombat().getGroups().forEach(g -> combat.add(String.join(",", keys(game, g.getAttackers(), true))
                + ">" + key(game, g.getDefenderId()) + "|blocked by:" + String.join(",", keys(game, g.getBlockers(), true))));
        Collections.sort(combat);
        s.add("combat", arr(combat));
        return s;
    }
}
