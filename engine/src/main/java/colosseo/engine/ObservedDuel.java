package colosseo.engine;

import mage.cards.Cards;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameState;
import mage.game.TwoPlayerDuel;
import mage.game.events.GameEvent;
import mage.game.mulligan.Mulligan;
import mage.players.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The live game: a two-player duel that also tells the session about each game event once XMage has handled or
 * queued it (for the browser's event feed, {@link EventFeed}), about each draw that nothing replaced, about cards a
 * player reveals, and about its bookmarks and roll-backs: what a roll-back undoes (a cancelled cast, a previewed block)
 * didn't happen. Only this object reports: {@link #copy()} is XMage's own, so the copies XMage's AI simulates on are
 * plain duels; and nothing is added to the game state.
 */
final class ObservedDuel extends TwoPlayerDuel {

    interface Observer {
        void event(Game game, GameEvent event);

        /** A player revealed these cards (their ids). */
        void revealed(Game game, UUID playerId, List<UUID> cards);

        void bookmarked(int bookmark);

        /** The game went back to this bookmark: everything since it is undone. */
        void restored(int bookmark);
    }

    private final transient Observer observer;
    /** the cards among the game's revealed cards that have been reported (or were there without a reveal line) */
    private final transient Set<UUID> revealedSeen = new HashSet<>();

    ObservedDuel(MultiplayerAttackOption attackOption, RangeOfInfluence range, Mulligan mulligan, int minimumDeckSize,
                 int startLife, int startHandSize, Observer observer) {
        super(attackOption, range, mulligan, minimumDeckSize, startLife, startHandSize);
        this.observer = observer;
    }

    @Override
    public void fireEvent(GameEvent event) {
        super.fireEvent(event);
        if (observer != null) {
            observer.event(this, event);
        }
    }

    /** Zone changes, declared attackers, created tokens, ...: XMage queues them and handles them together later. */
    @Override
    public void addSimultaneousEvent(GameEvent event) {
        super.addSimultaneousEvent(event);
        if (observer != null) {
            observer.event(this, event);
        }
    }

    /**
     * A draw: XMage asks whether anything replaces each card a player would draw (PlayerImpl#drawCards, the only
     * place that makes a {@code DRAW_CARD} event), and when nothing does, the card it moves next from that player's
     * library to their hand is the one drawn. A replaced draw isn't one (dredge, ...).
     */
    @Override
    public boolean replaceEvent(GameEvent event) {
        boolean replaced = super.replaceEvent(event);
        if (!replaced && observer != null && event.getType() == GameEvent.EventType.DRAW_CARD) {
            observer.event(this, event);
        }
        return replaced;
    }

    /**
     * A player revealing cards: XMage adds them to the game's revealed cards, then writes "&lt;player&gt; reveals
     * &lt;cards&gt;" (PlayerImpl#revealCards); no event says so. Cards revealed without that line (a reveal window
     * updated) are noted, not reported.
     */
    @Override
    public void informPlayers(String message) {
        if (observer != null && !isSimulation()) {
            try {
                List<UUID> fresh = new ArrayList<>();
                synchronized (revealedSeen) {
                    for (Cards cards : getState().getRevealed().values()) {
                        for (UUID id : cards) {
                            if (revealedSeen.add(id)) {
                                fresh.add(id);
                            }
                        }
                    }
                }
                if (!fresh.isEmpty() && message != null) {
                    for (Player player : getState().getPlayers().values()) {
                        if (message.startsWith(player.getLogName() + " reveals ")) {
                            observer.revealed(this, player.getId(), fresh);
                            break;
                        }
                    }
                }
            } catch (RuntimeException e) {
                // only the browsers' event feed reads this: the game goes on
            }
        }
        super.informPlayers(message);
    }

    /** XMage forgets the revealed cards once players have seen them: at each update and priority. */
    @Override
    public void fireUpdatePlayersEvent() {
        super.fireUpdatePlayersEvent();
        noteRevealed();
    }

    @Override
    public void firePriorityEvent(UUID playerId) {
        super.firePriorityEvent(playerId);
        noteRevealed();
    }

    private void noteRevealed() {
        if (observer == null) {
            return; // also while XMage's constructor runs, before this object's fields are set
        }
        synchronized (revealedSeen) {
            revealedSeen.clear();
            try {
                for (Cards cards : getState().getRevealed().values()) {
                    revealedSeen.addAll(cards);
                }
            } catch (RuntimeException e) {
                // as above
            }
        }
    }

    @Override
    public int bookmarkState() {
        int bookmark = super.bookmarkState();
        if (observer != null) {
            observer.bookmarked(bookmark);
        }
        return bookmark;
    }

    @Override
    public GameState restoreState(int bookmark, String context) {
        GameState restored = super.restoreState(bookmark, context);
        if (restored != null) {
            noteRevealed();
            if (observer != null) {
                observer.restored(bookmark);
            }
        }
        return restored;
    }
}
