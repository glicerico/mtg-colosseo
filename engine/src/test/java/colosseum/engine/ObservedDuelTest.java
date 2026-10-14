package colosseum.engine;

import com.google.gson.JsonObject;
import mage.abilities.PlayLandAbility;
import mage.cards.CardsImpl;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.DrawCardEvent;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.players.Player;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Why a card moved, and what a player revealed, as the live game tells the browsers' event feed ({@link ObservedDuel},
 * {@link EventFeed}), on a duel that isn't started; live games: python/tests/test_integration.py.
 */
public class ObservedDuelTest {

    /** What the duel tells its observer: the events, and [player, card ids] for each reveal. */
    private static final class Seen implements ObservedDuel.Observer {
        final List<GameEvent> events = new ArrayList<>();
        final List<List<UUID>> reveals = new ArrayList<>();

        @Override
        public void event(Game game, GameEvent event) {
            events.add(event);
        }

        @Override
        public void revealed(Game game, UUID playerId, List<UUID> cards) {
            List<UUID> r = new ArrayList<>();
            r.add(playerId);
            r.addAll(cards);
            reveals.add(r);
        }

        @Override
        public void bookmarked(int bookmark) {
        }

        @Override
        public void restored(int bookmark) {
        }
    }

    private static ObservedDuel duel(Seen seen) {
        return new ObservedDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, new KeepFirstLondonMulligan(0), 40,
                20, 7, seen);
    }

    private static CardsImpl cards(UUID... ids) {
        CardsImpl cards = new CardsImpl();
        for (UUID id : ids) {
            cards.add(id);
        }
        return cards;
    }

    private static ZoneChangeEvent move(UUID card, UUID owner, Zone from, Zone to) {
        return new ZoneChangeEvent(card, null, owner, from, to);
    }

    @Test
    public void aDrawThatNothingReplacedIsReported() {
        Seen seen = new Seen();
        ObservedDuel game = duel(seen);
        UUID p = UUID.randomUUID();
        assertFalse(game.replaceEvent(new DrawCardEvent(p, null, null)));
        assertEquals(1, seen.events.size());
        assertEquals(GameEvent.EventType.DRAW_CARD, seen.events.get(0).getType());
        assertEquals(p, seen.events.get(0).getPlayerId());
        // other events XMage asks about (here a zone change) aren't
        game.replaceEvent(move(UUID.randomUUID(), p, Zone.LIBRARY, Zone.HAND));
        assertEquals(1, seen.events.size());
    }

    @Test
    public void onlyTheCardMovedFromLibraryToHandRightAfterADrawIsDrawn() {
        EventFeed feed = new EventFeed();
        Game game = duel(new Seen());
        UUID p = UUID.randomUUID();
        UUID q = UUID.randomUUID();
        assertNull(feed.describe(game, new DrawCardEvent(p, null, null), 3));
        JsonObject drawn = feed.describe(game, move(UUID.randomUUID(), p, Zone.LIBRARY, Zone.HAND), 3);
        assertEquals("draw", drawn.get("reason").getAsString());
        // a search or landcycling: no draw before it
        JsonObject searched = feed.describe(game, move(UUID.randomUUID(), p, Zone.LIBRARY, Zone.HAND), 5);
        assertFalse(searched.has("reason"));
        // the draw's card moved elsewhere first, or something else happened in between
        feed.describe(game, new DrawCardEvent(p, null, null), 6);
        feed.describe(game, move(UUID.randomUUID(), p, Zone.HAND, Zone.GRAVEYARD), 6);
        assertFalse(feed.describe(game, move(UUID.randomUUID(), p, Zone.LIBRARY, Zone.HAND), 7).has("reason"));
        feed.describe(game, new DrawCardEvent(p, null, null), 8);
        feed.describe(game, GameEvent.getEvent(GameEvent.EventType.GAINED_LIFE, p, null, p, 2), 8);
        assertFalse(feed.describe(game, move(UUID.randomUUID(), p, Zone.LIBRARY, Zone.HAND), 9).has("reason"));
        // another player's card
        feed.describe(game, new DrawCardEvent(p, null, null), 10);
        assertFalse(feed.describe(game, move(UUID.randomUUID(), q, Zone.LIBRARY, Zone.HAND), 10).has("reason"));
    }

    @Test
    public void aLandPlayedSaysSoOneAnEffectPutsOntoTheBattlefieldDoesnt() {
        EventFeed feed = new EventFeed();
        Game game = duel(new Seen());
        UUID p = UUID.randomUUID();
        ZoneChangeEvent played = new ZoneChangeEvent(UUID.randomUUID(), new PlayLandAbility("Forest"), p, Zone.HAND,
                Zone.BATTLEFIELD);
        assertEquals("played", feed.describe(game, played, 1).get("reason").getAsString());
        assertFalse(feed.describe(game, move(UUID.randomUUID(), p, Zone.HAND, Zone.BATTLEFIELD), 2).has("reason"));
    }

    @Test
    public void cardsAPlayerRevealsAreReportedWhenTheRevealIsWritten() {
        Seen seen = new Seen();
        ObservedDuel game = duel(seen);
        Player p = new BridgePlayer("P1");
        game.getState().addPlayer(p);
        UUID swamp = UUID.randomUUID();
        UUID island = UUID.randomUUID();
        game.getState().getRevealed().add("Apex Witchstalker [a7a]", cards(swamp));
        game.informPlayers(p.getLogName() + " reveals Swamp");
        assertEquals(List.of(List.of(p.getId(), swamp)), seen.reveals);
        // the next lines reveal nothing new
        game.informPlayers(p.getLogName() + "'s library is shuffled");
        assertEquals(1, seen.reveals.size());
        // a card among the revealed ones without a reveal line is noted, not reported
        game.getState().getRevealed().update("Top of library", cards(island));
        game.informPlayers(p.getLogName() + " skips attack");
        game.informPlayers(p.getLogName() + " reveals Swamp");
        assertEquals(1, seen.reveals.size());
        // XMage forgets the revealed cards at the next update: the same card revealed again is a new reveal
        game.fireUpdatePlayersEvent();
        game.getState().getRevealed().add("Apex Witchstalker [b8b]", cards(swamp));
        game.informPlayers(p.getLogName() + " reveals Swamp");
        assertEquals(2, seen.reveals.size());
        assertEquals(List.of(p.getId(), swamp), seen.reveals.get(1));
    }

    @Test
    public void aRevealIsSentToEveryViewWithItsIds() {
        EventFeed feed = new EventFeed();
        UUID p = UUID.randomUUID();
        UUID card = UUID.randomUUID();
        JsonObject ev = feed.reveal(p, List.of(card), 26);
        assertEquals("reveal", ev.get("kind").getAsString());
        assertEquals(p.toString(), ev.get("player").getAsString());
        assertEquals(card.toString(), ev.getAsJsonArray("ids").get(0).getAsString());
        assertEquals(26, ev.get("log_at").getAsInt());
        // the card is in the opponent's hand, which this view doesn't see: a reveal is public, its ids stay
        JsonObject empty = Json.parse("{\"players\": [{\"id\": \"p1\", \"hand\": []}, {\"id\": \"p2\"}]}");
        feed.seen("seat0", empty);
        JsonObject sent = feed.message(ev, empty, "seat0").getAsJsonObject("event");
        assertEquals(card.toString(), sent.getAsJsonArray("ids").get(0).getAsString());
        assertTrue(!sent.has("hidden"));
    }
}
