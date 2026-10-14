package colosseum.engine;

import mage.cards.CardsImpl;
import mage.constants.Outcome;
import mage.filter.FilterCard;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.mulligan.LondonMulligan;
import mage.players.Player;
import mage.target.Target;
import mage.target.common.TargetCardInHand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The London mulligan, asking keep-first seats (seat option {@code mulligan_keep_first}, the default for human seats)
 * whether to keep before they put cards on the bottom.
 * <p>
 * XMage's {@link LondonMulligan} follows the wording of CR 103.5: a player who takes a mulligan draws a new hand of
 * seven and at once puts as many cards on the bottom as mulligans taken; only then does it declare again whether to
 * keep. For the players marked {@link #keepFirst(UUID)}, this asks in the order MTG Arena and MTGO
 * use: keep or mulligan, looking at the seven new cards, and on keeping, put that many cards on the bottom. Both
 * orders give a player the same choices and the same opening hands (cards put on the bottom before another mulligan
 * are shuffled back with the hand), and the game's random draws happen at the same points.
 * <p>
 * Every other player (agents, XMage's AI, whose mulligan rule counts on the reduced hand) gets exactly XMage's order:
 * {@link #mulligan} repeats {@link LondonMulligan#mulligan} step for step. The superclass's own bookkeeping is private,
 * so this class keeps its own and overrides every method that reads it.
 */
final class KeepFirstLondonMulligan extends LondonMulligan {

    private final Set<UUID> keepFirst = new HashSet<>();
    private final Map<UUID, Integer> startingHandSizes = new HashMap<>();
    private final Map<UUID, Integer> openingHandSizes = new HashMap<>();

    KeepFirstLondonMulligan(int freeMulligans) {
        super(freeMulligans);
    }

    private KeepFirstLondonMulligan(KeepFirstLondonMulligan mulligan) {
        super(mulligan.freeMulligans);
        this.usedFreeMulligans.putAll(mulligan.usedFreeMulligans);
        this.keepFirst.addAll(mulligan.keepFirst);
        this.startingHandSizes.putAll(mulligan.startingHandSizes);
        this.openingHandSizes.putAll(mulligan.openingHandSizes);
    }

    /** This player is asked keep-or-mulligan with the new seven cards, and puts cards on the bottom on keeping. */
    void keepFirst(UUID playerId) {
        keepFirst.add(playerId);
    }

    boolean isKeepFirst(UUID playerId) {
        return keepFirst.contains(playerId);
    }

    /** How many cards this keep-first player puts on the bottom if it keeps its current hand (0 when none). */
    int bottomOnKeep(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        Integer opening = openingHandSizes.get(playerId);
        if (player == null || opening == null || !keepFirst.contains(playerId)) {
            return 0;
        }
        return Math.max(0, player.getHand().size() - opening);
    }

    /**
     * {@link mage.game.mulligan.Mulligan#executeMulliganPhase} step for step, with one difference for keep-first
     * players: the log says they keep before they put cards on the bottom, in the order they were asked (XMage logs
     * the keep after {@link #endMulligan}, which asks for the bottom cards). {@link LondonMulligan}'s own version only
     * sets up its bookkeeping, which this class keeps itself.
     */
    @Override
    public void executeMulliganPhase(Game game, int startingHandSize) {
        for (UUID playerId : game.getState().getPlayerList(game.getStartingPlayerId())) {
            openingHandSizes.put(playerId, startingHandSize);
            startingHandSizes.put(playerId, startingHandSize);
        }
        List<UUID> keepPlayers = new ArrayList<>();
        List<UUID> mulliganPlayers = new ArrayList<>();
        do {
            mulliganPlayers.clear();
            for (UUID playerId : game.getState().getPlayerList(game.getStartingPlayerId())) {
                if (!keepPlayers.contains(playerId)) {
                    Player player = game.getPlayer(playerId);
                    boolean keep = true;
                    while (true) {
                        if (!canTakeMulligan(game, player)) {
                            break;
                        }
                        GameEvent event = new GameEvent(GameEvent.EventType.CAN_TAKE_MULLIGAN, null, null, playerId);
                        if (!game.replaceEvent(event)) {
                            game.fireEvent(event);
                            game.getState().setChoosingPlayerId(playerId);
                            if (player.chooseMulligan(game)) {
                                keep = false;
                            }
                            break;
                        }
                    }
                    if (keep) {
                        if (keepFirst.contains(playerId)) {
                            game.informPlayers(player.getLogName() + " keeps hand");
                            game.endMulligan(player.getId());
                        } else {
                            game.endMulligan(player.getId());
                            game.informPlayers(player.getLogName() + " keeps hand");
                        }
                        keepPlayers.add(playerId);
                    } else {
                        mulliganPlayers.add(playerId);
                        game.informPlayers(player.getLogName() + " decides to take mulligan");
                    }
                }
            }
            for (UUID mulliganPlayerId : mulliganPlayers) {
                mulligan(game, mulliganPlayerId);
            }
            game.saveState(false);
        } while (!mulliganPlayers.isEmpty());
    }

    @Override
    public int mulliganDownTo(Game game, UUID playerId) {
        return openingHandSizes.get(playerId) - (nextMulliganIsFree(playerId) ? 0 : 1);
    }

    @Override
    public boolean canTakeMulligan(Game game, Player player) {
        return !player.getHand().isEmpty() && openingHandSizes.get(player.getId()) > 0;
    }

    @Override
    public void mulligan(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        int numCards = startingHandSizes.get(player.getId());
        player.getLibrary().addAll(player.getHand().getCards(game), game);
        player.getHand().clear();
        player.shuffleLibrary(null, game);
        int deduction = 1;
        if (nextMulliganIsFree(playerId)) {
            deduction = 0;
            usedFreeMulligans.merge(playerId, 1, Integer::sum);
        }
        openingHandSizes.put(playerId, openingHandSizes.get(playerId) - deduction);
        int newHandSize = openingHandSizes.get(player.getId());
        if (deduction == 0) {
            game.informPlayers(player.getLogName() + " mulligans for free.");
        } else {
            game.informPlayers(player.getLogName() + " mulligans down to " + newHandSize + (newHandSize == 1 ? " card" : " cards"));
        }
        drawHand(numCards, player, game);
        if (!keepFirst.contains(playerId)) {
            putOnBottom(game, player, newHandSize); // XMage's order: before the next keep-or-mulligan
        }
    }

    @Override
    public void endMulligan(Game game, UUID playerId) {
        if (keepFirst.contains(playerId)) {
            putOnBottom(game, game.getPlayer(playerId), openingHandSizes.get(playerId)); // kept: now the bottom
        }
    }

    @Override
    public KeepFirstLondonMulligan copy() {
        return new KeepFirstLondonMulligan(this);
    }

    private boolean nextMulliganIsFree(UUID playerId) {
        return freeMulligans > 0 && usedFreeMulligans.getOrDefault(playerId, 0) < freeMulligans;
    }

    /** As {@link LondonMulligan#mulligan}: one card at a time until the hand is down to its opening size. */
    private static void putOnBottom(Game game, Player player, int handSize) {
        while (player.canRespond() && player.getHand().size() > handSize) {
            Target target = new TargetCardInHand(new FilterCard("card (" + (player.getHand().size() - handSize) + " more) to put on the bottom of your library"));
            player.chooseTarget(Outcome.Discard, target, null, game);
            player.putCardsOnBottomOfLibrary(new CardsImpl(target.getTargets()), game, null, true);
        }
    }
}
