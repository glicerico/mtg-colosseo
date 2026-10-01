package colosseo.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.ObjectColor;
import mage.cards.Card;
import mage.cards.ExpansionSet;
import mage.cards.Sets;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.RateCard;
import mage.constants.ColoredManaSymbol;
import mage.constants.Rarity;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Deck registry: preconstructed decks from the decks directory (XMage .dck format) and generated
 * sealed decks ({@code sealed:FDN}).
 * <p>
 * Metadata is read from comment lines in the .dck file:
 * <pre>
 * # description: Flyers and tempo
 * # tags: limited, FDN
 * NAME:Azorius Fliers
 * 2 [FDN:1] Some Card
 * </pre>
 */
public final class DeckLibrary {

    private static final Logger LOG = Logger.getLogger(DeckLibrary.class);

    public static final class DeckInfo {
        public String id;
        public String name;
        public String description = "";
        public List<String> tags = new ArrayList<>();
        public Path file;
    }

    private final Path dir;
    private final Map<String, DeckInfo> decks = new LinkedHashMap<>();

    public DeckLibrary(Path dir) {
        this.dir = dir;
        reload();
    }

    public synchronized void reload() {
        decks.clear();
        if (!Files.isDirectory(dir)) {
            LOG.warn("deck directory not found: " + dir.toAbsolutePath());
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".dck")).sorted().collect(Collectors.toList())) {
                DeckInfo info = new DeckInfo();
                String rel = dir.relativize(f).toString().replace('\\', '/');
                info.id = rel.substring(0, rel.length() - 4).replace('/', ':');
                info.file = f;
                info.name = info.id;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("NAME:")) {
                        info.name = t.substring(5).trim();
                    } else if (t.toLowerCase().startsWith("# description:")) {
                        info.description = t.substring(t.indexOf(':') + 1).trim();
                    } else if (t.toLowerCase().startsWith("# tags:")) {
                        for (String tag : t.substring(t.indexOf(':') + 1).split(",")) {
                            if (!tag.trim().isEmpty()) {
                                info.tags.add(tag.trim());
                            }
                        }
                    }
                }
                decks.put(info.id, info);
            }
        } catch (IOException e) {
            LOG.error("can't read decks", e);
        }
    }

    public synchronized List<DeckInfo> list() {
        return new ArrayList<>(decks.values());
    }

    public synchronized DeckInfo get(String id) {
        return decks.get(id);
    }

    /**
     * Loads a deck by spec: a deck id, a path to a .dck/.txt file, or "sealed:SET[:SEED]".
     */
    private volatile boolean allowPaths = true;

    /**
     * Whether a deck may be given as a file path on the server (only sensible for trusted clients).
     */
    public void setAllowPaths(boolean allowPaths) {
        this.allowPaths = allowPaths;
    }

    public Deck load(String spec) {
        if (spec == null || spec.isEmpty()) {
            throw new IllegalArgumentException("no deck given");
        }
        if (spec.startsWith("sealed:")) {
            return sealed(spec.substring(7));
        }
        DeckInfo info = get(spec);
        if (info == null) {
            reload(); // decks may have been added since startup
            info = get(spec);
        }
        Path file = info != null ? info.file : allowPaths ? Path.of(spec) : null;
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("unknown deck: " + spec + (allowPaths ? "" : " (deck file paths are disabled on this server)"));
        }
        StringBuilder errors = new StringBuilder();
        DeckCardLists lists = DeckImporter.importDeckFromFile(file.toString(), errors, false);
        try {
            Deck deck = Deck.load(lists, true, false);
            if (deck.getMaindeckCards().size() < 40) {
                throw new IllegalArgumentException("deck " + spec + " has only " + deck.getMaindeckCards().size()
                        + " cards" + (errors.length() > 0 ? ": " + errors : ""));
            }
            if (errors.length() > 0) {
                LOG.warn("deck " + spec + ": " + errors);
            }
            return deck;
        } catch (mage.game.GameException e) {
            throw new IllegalArgumentException("can't load deck " + spec + ": " + e.getMessage());
        }
    }

    /**
     * Deck contents for the lobby (card list with counts).
     */
    public JsonObject describe(DeckInfo info) {
        JsonObject o = new JsonObject();
        o.addProperty("id", info.id);
        o.addProperty("name", info.name);
        o.addProperty("description", info.description);
        o.add("tags", Json.strings(info.tags));
        try {
            StringBuilder errors = new StringBuilder();
            DeckCardLists lists = DeckImporter.importDeckFromFile(info.file.toString(), errors, false);
            Map<String, JsonObject> cards = new LinkedHashMap<>();
            ObjectColor colors = new ObjectColor();
            int total = 0;
            for (DeckCardInfo dci : lists.getCards()) {
                total++;
                String key = dci.getSetCode() + ":" + dci.getCardNumber();
                JsonObject c = cards.get(key);
                if (c == null) {
                    c = new JsonObject();
                    c.addProperty("name", dci.getCardName());
                    c.addProperty("set", dci.getSetCode());
                    c.addProperty("number", dci.getCardNumber());
                    c.addProperty("count", 0);
                    CardInfo ci = CardRepository.instance.findCard(dci.getSetCode(), dci.getCardNumber());
                    if (ci != null) {
                        c.addProperty("mana_cost", String.join("", ci.getManaCosts(CardInfo.ManaCostSide.ALL)));
                        c.addProperty("mana_value", ci.getManaValue());
                        c.addProperty("types", ci.getTypes().stream().map(Object::toString).collect(Collectors.joining(" ")));
                        colors = colors.union(ci.getColor());
                    }
                    cards.put(key, c);
                }
                c.addProperty("count", c.get("count").getAsInt() + 1);
            }
            JsonArray arr = new JsonArray();
            cards.values().forEach(arr::add);
            o.add("cards", arr);
            o.addProperty("size", total);
            o.addProperty("colors", colors.toString());
        } catch (RuntimeException e) {
            o.addProperty("error", e.getMessage());
        }
        return o;
    }

    // ---------------------------------------------------------------------------------------------

    private static final ColoredManaSymbol[] COLORS = {ColoredManaSymbol.W, ColoredManaSymbol.U, ColoredManaSymbol.B,
            ColoredManaSymbol.R, ColoredManaSymbol.G};

    /**
     * Opens 6 boosters of a set and builds a 2-color 40 card deck (23 spells + 17 basics).
     */
    public Deck sealed(String setSpec) {
        String code = setSpec.split(":")[0].toUpperCase();
        ExpansionSet set = Sets.findSet(code);
        if (set == null) {
            throw new IllegalArgumentException("unknown set: " + code);
        }
        List<Card> pool = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pool.addAll(set.createBooster());
        }
        pool.removeIf(Card::isBasic);

        List<ColoredManaSymbol> bestPair = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < COLORS.length; i++) {
            for (int j = i + 1; j < COLORS.length; j++) {
                List<ColoredManaSymbol> pair = List.of(COLORS[i], COLORS[j]);
                int score = pickSpells(pool, pair).stream().mapToInt(c -> RateCard.rateCard(c, pair, false)).sum();
                if (score > bestScore) {
                    bestScore = score;
                    bestPair = pair;
                }
            }
        }
        List<Card> spells = pickSpells(pool, bestPair);
        Deck deck = new Deck();
        deck.setName("Sealed " + code + " " + bestPair.get(0) + bestPair.get(1));
        deck.getCards().addAll(spells);

        // basics by colored symbol count
        int[] symbols = new int[2];
        for (Card c : spells) {
            symbols[0] += c.getManaCost().getMana().getColor(bestPair.get(0));
            symbols[1] += c.getManaCost().getMana().getColor(bestPair.get(1));
        }
        int lands = 40 - deck.getCards().size();
        int total = Math.max(1, symbols[0] + symbols[1]);
        int first = Math.max(Math.min(lands - 5, (int) Math.round((double) lands * symbols[0] / total)), 5);
        addBasics(deck, basicName(bestPair.get(0)), first, code);
        addBasics(deck, basicName(bestPair.get(1)), lands - first, code);
        return deck;
    }

    private static List<Card> pickSpells(List<Card> pool, List<ColoredManaSymbol> pair) {
        return pool.stream()
                .filter(c -> !c.isLand() || !c.isBasic())
                .filter(c -> fitsColors(c, pair))
                .sorted(Comparator.comparingInt((Card c) -> RateCard.rateCard(c, pair, false)).reversed())
                .limit(23)
                .collect(Collectors.toList());
    }

    private static boolean fitsColors(Card card, List<ColoredManaSymbol> pair) {
        ObjectColor color = card.getColor();
        return (!color.isWhite() || pair.contains(ColoredManaSymbol.W))
                && (!color.isBlue() || pair.contains(ColoredManaSymbol.U))
                && (!color.isBlack() || pair.contains(ColoredManaSymbol.B))
                && (!color.isRed() || pair.contains(ColoredManaSymbol.R))
                && (!color.isGreen() || pair.contains(ColoredManaSymbol.G));
    }

    private static String basicName(ColoredManaSymbol symbol) {
        switch (symbol) {
            case W:
                return "Plains";
            case U:
                return "Island";
            case B:
                return "Swamp";
            case R:
                return "Mountain";
            default:
                return "Forest";
        }
    }

    private static void addBasics(Deck deck, String name, int count, String setCode) {
        CardCriteria criteria = new CardCriteria().name(name).rarities(Rarity.LAND).setCodes(setCode);
        List<CardInfo> found = CardRepository.instance.findCards(criteria);
        if (found.isEmpty()) {
            found = CardRepository.instance.findCards(new CardCriteria().name(name).rarities(Rarity.LAND));
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("basic land not found: " + name);
        }
        CardInfo info = found.get(RandomUtil.nextInt(found.size()));
        for (int i = 0; i < count; i++) {
            deck.getCards().add(info.createCard());
        }
    }
}
