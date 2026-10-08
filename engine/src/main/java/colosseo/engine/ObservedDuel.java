package colosseo.engine;

import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameState;
import mage.game.TwoPlayerDuel;
import mage.game.events.GameEvent;
import mage.game.mulligan.Mulligan;

/**
 * The live game: a two-player duel that also tells the session about each game event once XMage has handled or
 * queued it (for the browser's event feed, {@link EventFeed}), and about its bookmarks and roll-backs: what a
 * roll-back undoes (a cancelled cast, a previewed block) didn't happen. Only this object reports: {@link #copy()} is
 * XMage's own, so the copies XMage's AI simulates on are plain duels; and nothing is added to the game state.
 */
final class ObservedDuel extends TwoPlayerDuel {

    interface Observer {
        void event(Game game, GameEvent event);

        void bookmarked(int bookmark);

        /** The game went back to this bookmark: everything since it is undone. */
        void restored(int bookmark);
    }

    private final transient Observer observer;

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
        if (restored != null && observer != null) {
            observer.restored(bookmark);
        }
        return restored;
    }
}
