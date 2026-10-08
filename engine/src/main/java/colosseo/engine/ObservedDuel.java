package colosseo.engine;

import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.TwoPlayerDuel;
import mage.game.events.GameEvent;
import mage.game.mulligan.Mulligan;

import java.util.function.BiConsumer;

/**
 * The live game: a two-player duel that also tells the session about each game event once XMage has handled or
 * queued it (for the browser's event feed, {@link EventFeed}). Only this object reports: {@link #copy()} is XMage's
 * own, so the copies XMage's AI simulates on are plain duels; and nothing is added to the game state.
 */
final class ObservedDuel extends TwoPlayerDuel {

    private final transient BiConsumer<Game, GameEvent> observer;

    ObservedDuel(MultiplayerAttackOption attackOption, RangeOfInfluence range, Mulligan mulligan, int minimumDeckSize,
                 int startLife, int startHandSize, BiConsumer<Game, GameEvent> observer) {
        super(attackOption, range, mulligan, minimumDeckSize, startLife, startHandSize);
        this.observer = observer;
    }

    @Override
    public void fireEvent(GameEvent event) {
        super.fireEvent(event);
        if (observer != null) {
            observer.accept(this, event);
        }
    }

    /** Zone changes, declared attackers, created tokens, ...: XMage queues them and handles them together later. */
    @Override
    public void addSimultaneousEvent(GameEvent event) {
        super.addSimultaneousEvent(event);
        if (observer != null) {
            observer.accept(this, event);
        }
    }
}
