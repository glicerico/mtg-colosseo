package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.abilities.TriggeredAbility;
import mage.cards.Card;
import mage.choices.Choice;
import mage.constants.ManaType;
import mage.game.Game;
import mage.game.events.PlayerQueryEvent;
import mage.game.permanent.Permanent;
import mage.players.ManaPool;
import mage.target.Target;
import mage.util.MultiAmountMessage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Translates XMage {@link PlayerQueryEvent}s raised by {@link BridgePlayer} (via HumanPlayer dialogs) into
 * protocol {@link Decision}s, and decision answers back into HumanPlayer responses.
 * <p>
 * Runs on the game thread.
 */
final class QueryTranslator {

    private QueryTranslator() {
    }

    static Decision translate(GameSession session, Seat seat, BridgePlayer player, Game game, PlayerQueryEvent e) {
        BridgePlayer.QueryContext ctx = player.currentContext();
        switch (e.getQueryType()) {
            case ASK:
                return ask(session, player, e, ctx);
            case PICK_TARGET:
                return target(session, player, game, e, ctx);
            case PICK_ABILITY:
                return triggerOrder(session, player, game, e);
            case CHOOSE_ABILITY:
                return chooseAbility(session, player, game, e);
            case CHOOSE_MODE:
                return chooseMode(session, player, e);
            case CHOOSE_CHOICE:
                return choice(session, player, e);
            case AMOUNT:
                return amount(session, player, game, e, ctx);
            case MULTI_AMOUNT:
                return multiAmount(session, player, e);
            case CHOOSE_PILE:
                return pile(session, player, game, e);
            case PLAY_MANA:
            case PLAY_X_MANA:
                return mana(session, player, game, e);
            case SELECT:
                // priority/attack/block are overridden by BridgePlayer; this is a generic "OK" prompt
                return select(session, player, e);
            default:
                return null;
        }
    }

    private static String prompt(PlayerQueryEvent e) {
        StringBuilder sb = new StringBuilder(Json.plain(e.getMessage()));
        Map<String, Serializable> options = e.getOptions();
        if (options != null && options.get("secondMessage") instanceof String) {
            String second = Json.plain((String) options.get("secondMessage"));
            if (!second.isEmpty() && sb.indexOf(second) < 0) {
                sb.append(" (").append(second).append(')');
            }
        }
        return sb.toString();
    }

    private static String option(PlayerQueryEvent e, String key, String def) {
        Map<String, Serializable> options = e.getOptions();
        if (options != null && options.get(key) instanceof String) {
            return (String) options.get(key);
        }
        return def;
    }

    // ---------------------------------------------------------------------------------------------

    private static Decision ask(GameSession session, BridgePlayer player, PlayerQueryEvent e, BridgePlayer.QueryContext ctx) {
        boolean mulligan = ctx != null && Decision.MULLIGAN.equals(ctx.kindHint);
        Decision d = session.newDecision(mulligan ? Decision.MULLIGAN : Decision.YES_NO, player.getId());
        d.prompt = prompt(e);
        if (mulligan) {
            d.addOption("keep", "Keep");
            d.addOption("mulligan", "Mulligan");
            d.extra.addProperty("hand_size", player.getHand().size());
        } else {
            d.addOption("yes", Json.plain(option(e, "UI.left.btn.text", "Yes")));
            d.addOption("no", Json.plain(option(e, "UI.right.btn.text", "No")));
        }
        if (ctx != null && ctx.source != null) {
            d.extra.addProperty("source_id", Json.str(ctx.source.getSourceId()));
        }
        d.defaultAction = Decision.choiceAction(mulligan ? "keep" : "no");
        d.responder = new ChoiceResponder(d) {
            @Override
            public void apply(JsonObject action) {
                String c = Json.getString(action, "choice", "");
                player.setResponseBoolean(c.equals("yes") || c.equals("mulligan"));
            }
        };
        return d;
    }

    private static Decision target(GameSession session, BridgePlayer player, Game game, PlayerQueryEvent e, BridgePlayer.QueryContext ctx) {
        Decision d = session.newDecision(Decision.TARGET, player.getId());
        d.prompt = prompt(e);
        Map<String, Serializable> options = e.getOptions();

        Set<UUID> chosen = new LinkedHashSet<>();
        if (options != null && options.get("chosenTargets") instanceof Collection) {
            for (Object o : (Collection<?>) options.get("chosenTargets")) {
                if (o instanceof UUID) {
                    chosen.add((UUID) o);
                }
            }
        }

        Set<UUID> selectable = new LinkedHashSet<>();
        if (e.getCards() != null) {
            Set<UUID> possible = null;
            if (options != null && options.get("possibleTargets") instanceof Collection) {
                possible = new LinkedHashSet<>();
                for (Object o : (Collection<?>) options.get("possibleTargets")) {
                    if (o instanceof UUID) {
                        possible.add((UUID) o);
                    }
                }
            }
            JsonArray shown = new JsonArray();
            for (Card card : e.getCards().getCards(game)) {
                JsonObject cj = StateView.card(game, card);
                boolean ok = possible == null || possible.contains(card.getId());
                cj.addProperty("selectable", ok);
                shown.add(cj);
                if (ok) {
                    selectable.add(card.getId());
                }
            }
            // cards may live in hidden zones (library search, opponent hand): send them in full
            d.extra.add("cards", shown);
        } else if (e.getTargets() != null) {
            selectable.addAll(e.getTargets());
        } else if (e.getPerms() != null) {
            for (Permanent p : e.getPerms()) {
                selectable.add(p.getId());
            }
        }
        selectable.addAll(chosen);

        for (UUID id : selectable) {
            JsonObject o = StateView.describe(game, id, player.getId());
            if (e.getCards() != null) {
                Card card = game.getCard(id);
                if (card != null) {
                    o.addProperty("name", card.getName());
                }
            }
            String label = Json.getString(o, "name", id.toString());
            JsonObject opt = d.addOption(id.toString(), label);
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                if (!en.getKey().equals("id")) {
                    opt.add(en.getKey(), en.getValue());
                }
            }
            if (chosen.contains(id)) {
                opt.addProperty("selected", true);
            }
        }
        boolean required = e.isRequired();
        if (!required) {
            d.addOption("done", Json.plain(option(e, "UI.right.btn.text", chosen.isEmpty() ? "Skip" : "Done")));
        }
        Target target = ctx == null ? null : ctx.target;
        if (target != null) {
            d.extra.addProperty("min", target.getMinNumberOfTargets());
            d.extra.addProperty("max", target.getMaxNumberOfTargets());
        }
        d.extra.addProperty("required", required);
        d.extra.add("chosen", Json.ids(chosen));
        if (options != null && options.get("targetZone") != null) {
            d.extra.addProperty("zone", options.get("targetZone").toString());
        }
        if (ctx != null && ctx.source != null) {
            MageObject src = game.getObject(ctx.source.getSourceId());
            d.extra.addProperty("source_id", Json.str(ctx.source.getSourceId()));
            if (src != null) {
                d.extra.addProperty("source_name", src.getName());
            }
        }

        // default: first unselected legal option, or done
        String def = required ? null : "done";
        if (def == null) {
            for (UUID id : selectable) {
                if (!chosen.contains(id)) {
                    def = id.toString();
                    break;
                }
            }
        }
        d.defaultAction = Decision.choiceAction(def == null ? (d.options.isEmpty() ? "done" : d.options.keySet().iterator().next()) : def);

        d.responder = new Decision.Responder() {
            @Override
            public String validate(JsonObject action) {
                if (action.has("choices") && action.get("choices").isJsonArray()) {
                    JsonArray arr = action.getAsJsonArray("choices");
                    if (arr.size() == 0) {
                        return d.hasOption("done") ? null : "at least one target is required";
                    }
                    // only the first is checked now, the rest is validated by later dialogs
                    String first = arr.get(0).getAsString();
                    return d.hasOption(first) ? null : "unknown option: " + first;
                }
                String c = Json.getString(action, "choice", null);
                return d.hasOption(c) ? null : "unknown option: " + c;
            }

            @Override
            public void apply(JsonObject action) {
                String c;
                if (action.has("choices") && action.get("choices").isJsonArray()) {
                    JsonArray arr = action.getAsJsonArray("choices");
                    List<String> rest = new ArrayList<>();
                    for (int i = 1; i < arr.size(); i++) {
                        rest.add(arr.get(i).getAsString());
                    }
                    c = arr.size() == 0 ? "done" : arr.get(0).getAsString();
                    if (arr.size() > 0) {
                        rest.add("done");
                    }
                    Set<String> expected = new LinkedHashSet<>(Json.ids(chosen).asList().stream().map(JsonElement::getAsString).toList());
                    if (!"done".equals(c)) {
                        expected.add(c);
                    }
                    seat(session, player).queueChoices(d.prompt, rest, expected);
                } else {
                    c = Json.getString(action, "choice", "done");
                }
                if ("done".equals(c)) {
                    player.setResponseBoolean(true);
                } else {
                    player.setResponseUUID(UUID.fromString(c));
                }
            }
        };
        return d;
    }

    private static Seat seat(GameSession session, BridgePlayer player) {
        return player.getSeat();
    }

    private static Decision triggerOrder(GameSession session, BridgePlayer player, Game game, PlayerQueryEvent e) {
        Decision d = session.newDecision(Decision.TRIGGER_ORDER, player.getId());
        d.prompt = prompt(e);
        for (Ability ability : e.getAbilities()) {
            MageObject source = game.getObject(ability.getSourceId());
            String name = source == null ? "" : source.getName();
            JsonObject o = d.addOption(ability.getId().toString(), Json.plain(name + ": " + ability.getRule(name)));
            o.addProperty("source_id", Json.str(ability.getSourceId()));
        }
        d.defaultAction = Decision.choiceAction(d.options.keySet().iterator().next());
        d.responder = uuidResponder(d, player);
        return d;
    }

    private static Decision chooseAbility(GameSession session, BridgePlayer player, Game game, PlayerQueryEvent e) {
        Decision d = session.newDecision(Decision.ABILITY, player.getId());
        d.prompt = prompt(e);
        String objectName = e.getChoices() != null && !e.getChoices().isEmpty() ? e.getChoices().iterator().next() : null;
        for (Ability ability : e.getAbilities()) {
            String rule;
            if (ability instanceof SpellAbility) {
                SpellAbility spell = (SpellAbility) ability;
                rule = spell.getRule(spell.getCardName());
                if (rule == null || rule.isEmpty()) {
                    rule = spell.toString();
                }
                if (!rule.startsWith("Cast ")) {
                    rule = spell + ": " + rule;
                }
            } else {
                rule = ability.getRule(objectName);
                if (rule == null || rule.isEmpty()) {
                    rule = ability.toString();
                }
            }
            JsonObject o = d.addOption(ability.getId().toString(), Json.plain(rule));
            o.addProperty("source_id", Json.str(ability.getSourceId()));
        }
        d.addOption("cancel", "Cancel");
        d.defaultAction = Decision.choiceAction(d.options.keySet().iterator().next());
        d.responder = new ChoiceResponder(d) {
            @Override
            public void apply(JsonObject action) {
                String c = Json.getString(action, "choice", "cancel");
                if ("cancel".equals(c)) {
                    player.setResponseBoolean(false);
                } else {
                    player.setResponseUUID(UUID.fromString(c));
                }
            }
        };
        return d;
    }

    private static Decision chooseMode(GameSession session, BridgePlayer player, PlayerQueryEvent e) {
        Decision d = session.newDecision(Decision.MODE, player.getId());
        d.prompt = prompt(e);
        for (Map.Entry<UUID, String> mode : e.getModes().entrySet()) {
            d.addOption(mode.getKey().toString(), Json.plain(mode.getValue()));
        }
        d.defaultAction = Decision.choiceAction(d.options.keySet().iterator().next());
        d.responder = uuidResponder(d, player);
        return d;
    }

    private static Decision choice(GameSession session, BridgePlayer player, PlayerQueryEvent e) {
        Choice choice = e.getChoice();
        Decision d = session.newDecision(Decision.CHOICE, player.getId());
        StringBuilder msg = new StringBuilder(Json.plain(choice.getMessage()));
        if (choice.getSubMessage() != null && !choice.getSubMessage().isEmpty()) {
            msg.append(" (").append(Json.plain(choice.getSubMessage())).append(')');
        }
        d.prompt = msg.toString();
        if (choice.isKeyChoice()) {
            for (Map.Entry<String, String> en : choice.getKeyChoices().entrySet()) {
                d.addOption(en.getKey(), Json.plain(en.getValue()));
            }
        } else {
            for (String value : choice.getChoices()) {
                d.addOption(value, Json.plain(value));
            }
        }
        boolean required = choice.isRequired();
        if (!required) {
            d.addOption("__cancel__", "Cancel");
        }
        d.extra.addProperty("required", required);
        d.extra.addProperty("searchable", d.options.size() > 20);
        d.defaultAction = Decision.choiceAction(d.options.keySet().iterator().next());
        d.responder = new ChoiceResponder(d) {
            @Override
            public void apply(JsonObject action) {
                String c = Json.getString(action, "choice", "__cancel__");
                player.setResponseString("__cancel__".equals(c) ? "" : c);
            }
        };
        return d;
    }

    private static Decision amount(GameSession session, BridgePlayer player, Game game, PlayerQueryEvent e, BridgePlayer.QueryContext ctx) {
        Decision d = session.newDecision(Decision.AMOUNT, player.getId());
        d.prompt = prompt(e);
        int min = e.getMin();
        int max = e.getMax();
        d.extra.addProperty("min", min);
        d.extra.addProperty("max", max);
        if (ctx != null && "announce_x".equals(ctx.kindHint)) {
            // the engine allows any X; tell the agent how much it can actually pay
            d.extra.addProperty("announce_x", true);
            int available = 0;
            for (mage.Mana m : player.getManaAvailable(game)) {
                available = Math.max(available, m.count());
            }
            int otherCosts = ctx.source == null ? 0 : ctx.source.getManaCostsToPay().manaValue();
            d.extra.addProperty("max_affordable", Math.max(min, Math.min(max, available - otherCosts)));
        }
        JsonObject def = new JsonObject();
        def.addProperty("amount", min);
        d.defaultAction = def;
        d.responder = new Decision.Responder() {
            @Override
            public String validate(JsonObject action) {
                if (!action.has("amount")) {
                    return "expected 'amount'";
                }
                int v = Json.getInt(action, "amount", Integer.MIN_VALUE);
                if (v < min || v > max) {
                    return "amount must be between " + min + " and " + max;
                }
                return null;
            }

            @Override
            public void apply(JsonObject action) {
                player.setResponseInteger(Json.getInt(action, "amount", min));
            }
        };
        return d;
    }

    private static Decision multiAmount(GameSession session, BridgePlayer player, PlayerQueryEvent e) {
        Decision d = session.newDecision(Decision.MULTI_AMOUNT, player.getId());
        Map<String, Serializable> options = e.getOptions();
        String title = options != null && options.get("title") != null ? options.get("title").toString() : "Distribute";
        String header = options != null && options.get("header") != null ? options.get("header").toString() : "";
        d.prompt = Json.plain(title + (header.isEmpty() ? "" : ": " + header));
        List<MultiAmountMessage> messages = e.getMessages();
        JsonArray items = new JsonArray();
        JsonArray defaults = new JsonArray();
        for (MultiAmountMessage m : messages) {
            JsonObject it = new JsonObject();
            it.addProperty("label", Json.plain(m.message));
            it.addProperty("min", m.min);
            it.addProperty("max", m.max);
            it.addProperty("default", m.defaultValue);
            items.add(it);
        }
        for (Integer v : mage.constants.MultiAmountType.prepareDefaultValues(messages, e.getMin(), e.getMax())) {
            defaults.add(v);
        }
        d.extra.add("items", items);
        d.extra.addProperty("total_min", e.getMin());
        d.extra.addProperty("total_max", e.getMax());
        boolean canCancel = options != null && Boolean.TRUE.equals(options.get("canCancel"));
        d.extra.addProperty("can_cancel", canCancel);
        JsonObject def = new JsonObject();
        def.add("amounts", defaults);
        d.defaultAction = def;
        d.responder = new Decision.Responder() {
            @Override
            public String validate(JsonObject action) {
                if (canCancel && "cancel".equals(Json.getString(action, "choice", null))) {
                    return null;
                }
                if (!action.has("amounts") || !action.get("amounts").isJsonArray()) {
                    return "expected 'amounts' list";
                }
                List<Integer> values = new ArrayList<>();
                for (JsonElement el : action.getAsJsonArray("amounts")) {
                    values.add(el.getAsInt());
                }
                if (!mage.constants.MultiAmountType.isGoodValues(values, messages, e.getMin(), e.getMax())) {
                    return "invalid amounts: need " + messages.size() + " values within each item's range and a total between "
                            + e.getMin() + " and " + e.getMax();
                }
                return null;
            }

            @Override
            public void apply(JsonObject action) {
                if (action.has("amounts")) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonElement el : action.getAsJsonArray("amounts")) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(el.getAsInt());
                    }
                    player.setResponseString(sb.toString());
                } else {
                    player.setResponseBoolean(false);
                }
            }
        };
        return d;
    }

    private static Decision pile(GameSession session, BridgePlayer player, Game game, PlayerQueryEvent e) {
        Decision d = session.newDecision(Decision.PILE, player.getId());
        d.prompt = prompt(e);
        d.addOption("pile1", "Pile 1");
        d.addOption("pile2", "Pile 2");
        d.extra.add("pile1", StateView.cards(game, e.getPile1()));
        d.extra.add("pile2", StateView.cards(game, e.getPile2()));
        d.defaultAction = Decision.choiceAction("pile1");
        d.responder = new ChoiceResponder(d) {
            @Override
            public void apply(JsonObject action) {
                player.setResponseBoolean("pile1".equals(Json.getString(action, "choice", "pile1")));
            }
        };
        return d;
    }

    private static Decision mana(GameSession session, BridgePlayer player, Game game, PlayerQueryEvent e) {
        Decision d = session.newDecision(Decision.MANA, player.getId());
        d.prompt = prompt(e);
        for (MageObject source : player.manaSources(game)) {
            JsonObject o = d.addOption(source.getId().toString(), "Tap " + source.getName());
            o.addProperty("kind", "source");
        }
        ManaPool pool = player.getManaPool();
        String[][] types = {{"W", "WHITE"}, {"U", "BLUE"}, {"B", "BLACK"}, {"R", "RED"}, {"G", "GREEN"}, {"C", "COLORLESS"}};
        for (String[] t : types) {
            int n = pool.get(ManaType.valueOf(t[1]));
            if (n > 0) {
                JsonObject o = d.addOption("pool:" + t[0], "Use {" + t[0] + "} from mana pool (" + n + ")");
                o.addProperty("kind", "pool");
            }
        }
        if (!game.getState().getSpecialActions().getControlledBy(player.getId(), true).isEmpty()) {
            d.addOption("special", "Special payment (convoke, delve, ...)").addProperty("kind", "special");
        }
        d.addOption("cancel", "Cancel");
        d.defaultAction = Decision.choiceAction("cancel");
        d.responder = new ChoiceResponder(d) {
            @Override
            public void apply(JsonObject action) {
                String c = Json.getString(action, "choice", "cancel");
                if ("cancel".equals(c)) {
                    player.setResponseBoolean(false);
                } else if ("special".equals(c)) {
                    player.setResponseString("special");
                } else if (c.startsWith("pool:")) {
                    ManaType type;
                    switch (c.substring(5)) {
                        case "W":
                            type = ManaType.WHITE;
                            break;
                        case "U":
                            type = ManaType.BLUE;
                            break;
                        case "B":
                            type = ManaType.BLACK;
                            break;
                        case "R":
                            type = ManaType.RED;
                            break;
                        case "G":
                            type = ManaType.GREEN;
                            break;
                        default:
                            type = ManaType.COLORLESS;
                    }
                    player.setResponseManaType(player.getId(), type);
                } else {
                    player.setResponseUUID(UUID.fromString(c));
                }
            }
        };
        return d;
    }

    private static Decision select(GameSession session, BridgePlayer player, PlayerQueryEvent e) {
        Decision d = session.newDecision(Decision.YES_NO, player.getId());
        d.prompt = prompt(e);
        d.addOption("yes", "OK");
        d.defaultAction = Decision.choiceAction("yes");
        d.responder = new ChoiceResponder(d) {
            @Override
            public void apply(JsonObject action) {
                player.setResponseBoolean(true);
            }
        };
        return d;
    }

    // ---------------------------------------------------------------------------------------------

    private abstract static class ChoiceResponder implements Decision.Responder {
        final Decision d;

        ChoiceResponder(Decision d) {
            this.d = d;
        }

        @Override
        public String validate(JsonObject action) {
            String c = Json.getString(action, "choice", null);
            if (c == null && action.has("choices") && action.get("choices").isJsonArray()
                    && action.getAsJsonArray("choices").size() == 1) {
                c = action.getAsJsonArray("choices").get(0).getAsString();
                action.addProperty("choice", c);
            }
            return d.hasOption(c) ? null : "unknown option: " + c;
        }
    }

    private static Decision.Responder uuidResponder(Decision d, BridgePlayer player) {
        return new ChoiceResponder(d) {
            @Override
            public void apply(JsonObject action) {
                player.setResponseUUID(UUID.fromString(Json.getString(action, "choice", null)));
            }
        };
    }

    static boolean isTriggered(Ability ability) {
        return ability instanceof TriggeredAbility;
    }
}
