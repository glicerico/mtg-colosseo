"""Browser tests of the opponent's turn shown step by step (web/js/game.js, web/js/fx.js), with Playwright.

The page is served from web/ and its WebSocket is mocked with one opponent turn recorded in a browser on a live game
(fixtures/ui_turn.json.gz; see its "_source"). Our agent draws, casts Stingerquill Charm on Semester Foreseer and
attacks with Ingris Stingerquill, whose trigger deals 1 damage to the human. The human blocks with Shatterwing
Pegasus, and combat damage follows. The turn's messages arrive at once, as the engine sends them; once the page
answers the block, what followed.

Other stretches of live games, which the page only watches (fixtures/ui_<name>.json.gz): a spell countered, a spell
that fizzles, deaths before the turn changes, discards, cards searched for and revealed (from a seat and as a
spectator).
"""
import gzip
import json
import mimetypes
import re
import time
from collections import namedtuple
from pathlib import Path

import pytest

sync_api = pytest.importorskip("playwright.sync_api")

pytestmark = pytest.mark.ui

WEB = Path(__file__).resolve().parents[2] / "web"
FIXTURES = Path(__file__).parent / "fixtures"
TURN = json.loads(gzip.decompress((FIXTURES / "ui_turn.json.gz").read_bytes()))
BASE = "http://colosseum.test"

# what the page shows at a moment: the step's caption and log links, what it draws, the log lines it marks, the prompt
SNAPSHOT = """() => {
  const cap = document.querySelector('.fx-caption');
  const names = (sel) => [...document.querySelectorAll(sel)].map((c) => c._card?.name || c.querySelector('.cf-name')?.textContent);
  return {
    caption: cap ? cap.querySelector('span').textContent : null,
    links: cap ? [...cap.querySelectorAll('.fx-log-link')].map((b) => b.textContent) : [],
    arrows: [...document.querySelectorAll('.fx-arrow')].map((a) => [...a.classList].find((c) => c !== 'fx-arrow')),
    spotlight: document.querySelector('.fx-spotlight .fx-title')?.textContent || null,
    spotlight_card: document.querySelector('.fx-spotlight .card .cf-name')?.textContent || null,
    targeted: names('.zone .card.fx-targeted'),
    source: names('.zone .card.fx-source'),
    floats: [...document.querySelectorAll('.fx-float')].map((f) => f.textContent),
    log_now: [...document.querySelectorAll('.log-line.log-now')].map((l) => +l.dataset.i),
    prompt: document.querySelector('.prompt-bar.active') ? document.querySelector('.prompt-bar .prompt-text').innerText : null,
    buttons: [...document.querySelectorAll('.prompt-bar .prompt-buttons .btn')].map((b) => b.textContent),
  };
}"""


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        b = p.chromium.launch()
        yield b
        b.close()


def new_page(browser, hello, size=(1600, 900)):
    """A page whose HTTP requests web/ answers (and the game's info, from hello)."""
    page = browser.new_page(viewport={"width": size[0], "height": size[1]})
    page.errors, page.sent = [], []
    page.on("pageerror", lambda e: page.errors.append(str(e)))

    def serve(route):
        path = route.request.url[len(BASE):].split("?")[0].split("#")[0]
        if path.startswith("/api/games/"):
            return route.fulfill(json=hello["game"])
        if path.startswith("/api/"):
            return route.fulfill(json={})
        f = WEB / (path.lstrip("/") or "index.html")
        if not f.is_file():
            return route.fulfill(status=404, body="not found")
        return route.fulfill(body=f.read_bytes(), content_type=mimetypes.guess_type(f.name)[0] or "text/plain")

    page.route(f"{BASE}/**", serve)
    return page


def go(page, gid, pace, seat=0):
    page.goto(BASE + "/")
    page.evaluate("p => { localStorage.setItem('colosseum.images', 'off'); localStorage.setItem('colosseum.pace', p); }", pace)
    page.goto(f"{BASE}/#/game/{gid}/seat/{seat}" if seat >= 0 else f"{BASE}/#/watch/{gid}")
    page.reload()


def open_turn(browser, pace="fast", size=(1600, 900)):
    hello = TURN["hello"]
    page = new_page(browser, hello, size)

    def ws(route):
        route.send(json.dumps(hello))
        for m in TURN["before"]:
            route.send(json.dumps(m))

        def answered(text):
            msg = json.loads(text)
            page.sent.append(msg)
            if msg.get("type") == "action":
                for m in TURN["after"]:
                    route.send(json.dumps(m))

        route.on_message(answered)

    page.route_web_socket("**/ws/game/**", ws)
    go(page, hello["game_id"], pace)
    return page


def watch(page, until, timeout=30):
    """The steps the page shows, in order (each as first seen with its log links), until until(snapshot)."""
    steps = []
    end = time.time() + timeout
    while time.time() < end:
        s = page.evaluate(SNAPSHOT)
        if s["caption"] and (not steps or steps[-1]["caption"] != s["caption"]):
            steps.append(s)
        elif s["caption"] and steps and steps[-1]["caption"] == s["caption"]:
            if s["links"] and not steps[-1]["links"]:  # links and the log marks come together, just after the caption
                steps[-1]["links"], steps[-1]["log_now"] = s["links"], s["log_now"]
            for k in ("arrows", "targeted", "source", "floats"):
                if not steps[-1][k] and s[k]:
                    steps[-1][k] = s[k]
        if until(s):
            return steps, s
        page.wait_for_timeout(25)
    raise AssertionError(f"timed out; steps so far: {[x['caption'] for x in steps]}")


blockers_asked = lambda s: s["prompt"] is not None and "No blocks" in s["buttons"]


def test_the_opponents_turn_is_shown_step_by_step_in_the_engines_order(browser):
    page = open_turn(browser)
    steps, last = watch(page, blockers_asked)
    assert [(s["caption"], s["links"]) for s in steps] == [
        ("ours-heuristic draws a card", ["log 73"]),
        ("ours-heuristic casts Stingerquill Charm → Semester Foreseer", ["log 74"]),
        ("Stingerquill Charm resolves → graveyard", ["log 75"]),
        ("ours-heuristic attacks with Ingris Stingerquill", ["log 76"]),
        ("Ingris Stingerquill triggers", ["log 77"]),
        ("Ingris Stingerquill deals 1 damage to Human", ["log 78"]),
    ]
    # each step marks its own line in the log, which stays complete
    assert [s["log_now"] for s in steps] == [[73], [74], [75], [76], [77], [78]]
    # the human's decision is shown once the steps before it have been: never during them
    assert all(s["prompt"] is None for s in steps)
    assert page.errors == []


def test_a_cast_shows_its_card_and_its_targets(browser):
    page = open_turn(browser)
    steps, _ = watch(page, blockers_asked)
    cast = steps[1]
    assert cast["spotlight"] == "ours-heuristic casts" and cast["spotlight_card"] == "Stingerquill Charm"
    assert cast["arrows"].count("fx-target") == 1 and cast["targeted"] == ["Semester Foreseer"]


def test_a_trigger_shows_its_source(browser):
    page = open_turn(browser)
    steps, _ = watch(page, blockers_asked)
    trigger = steps[4]
    assert trigger["spotlight"] == "Ingris Stingerquill triggers" and trigger["spotlight_card"] == "Ingris Stingerquill"
    assert trigger["source"] == ["Ingris Stingerquill"] and "fx-source" in trigger["arrows"]


def test_attacks_blocks_and_damage_are_drawn(browser):
    page = open_turn(browser)
    steps, _ = watch(page, blockers_asked)
    assert steps[3]["arrows"] == ["fx-attack"]
    assert steps[5]["floats"] == ["−1"]  # the trigger's damage, on the human
    # the human blocks Ingris Stingerquill with Shatterwing Pegasus (one attacker: the block is assigned at once)
    page.locator('.zone .card:has(.cf-name:text-is("Shatterwing Pegasus"))').click()
    page.locator(".prompt-bar .btn.primary").click()
    after, _ = watch(page, lambda s: s["prompt"] is not None and "Pass" in s["buttons"])
    caps = [(s["caption"], s["links"]) for s in after]
    assert ("Human blocks: Shatterwing Pegasus → Ingris Stingerquill", ["log 79"]) in caps
    damage = next(s for s in after if s["caption"].startswith("Combat damage"))
    assert damage["links"] == ["log 80", "log 81"] and sorted(damage["floats"]) == ["−1", "−2"]
    block = next(s for s in after if s["caption"].startswith("Human blocks"))
    assert "fx-block" in block["arrows"]
    sent = [m for m in page.sent if m.get("type") == "action"]
    assert len(sent) == 1 and len(sent[0]["blocks"]) == 1


def test_skip_and_escape_show_the_board_as_it_is_now(browser):
    page = open_turn(browser, pace="normal")
    page.wait_for_selector(".fx-caption")
    page.keyboard.press("Escape")
    page.wait_for_timeout(150)
    s = page.evaluate(SNAPSHOT)
    assert s["caption"] is None and s["arrows"] == [] and blockers_asked(s)
    assert page.locator(".player-info.bottom .life").inner_text() == "21"  # the trigger's damage is in
    assert page.locator(".skip-btn").is_hidden()


def test_pace_off_draws_no_steps(browser):
    page = open_turn(browser, pace="off")
    page.wait_for_selector(".prompt-bar.active")
    seen = [page.evaluate(SNAPSHOT) for _ in range(20) if not page.wait_for_timeout(50)]
    assert all(s["caption"] is None and s["arrows"] == [] for s in seen) and blockers_asked(seen[-1])


def test_a_steps_log_link_shows_its_line_and_log_lines_ring_their_cards(browser):
    page = open_turn(browser, pace="slow")
    steps, _ = watch(page, lambda s: s["caption"] and s["caption"].startswith("ours-heuristic casts") and s["links"])
    page.locator(".fx-log-link", has_text="log 74").click()
    assert "log-flash" in page.locator('.log-line[data-i="74"]').get_attribute("class")
    page.keyboard.press("Escape")
    # "... targeting Semester Foreseer [5f7] ...": hovering the line rings that card on the board
    page.locator('.log-line[data-i="74"]').hover()
    ring = page.locator('.zone .card.log-ring .cf-name')
    assert ring.all_inner_texts() == ["Semester Foreseer"]


# every caption the page shows, with the log links it gets just after and what the step draws as it starts: the cards
# shown large and the cards flying ("back": a card face down) (window.__caps); the stamps on cards leaving the stack
# (window.__stamps); the blockers' tags (window.__blocks)
CAPTIONS = """
  window.__caps = [];
  window.__stamps = [];
  window.__blocks = [];
  new MutationObserver((ms) => { for (const m of ms) for (const n of m.addedNodes) {
    if (n.nodeType !== 1) continue;
    for (const b of n.matches('.badge') ? [n] : n.querySelectorAll('.badge')) {
      if (/^block/.test(b.textContent) && !window.__blocks.includes(b.textContent)) window.__blocks.push(b.textContent);
    }
    if (n.classList.contains('fx-ghost') && n.querySelector('.fx-stamp')) {
      window.__stamps.push(n.querySelector('.fx-stamp').textContent);
    } else if (n.classList.contains('fx-caption')) {
      const names = (sel) => [...document.querySelectorAll(sel)].map((c) => c.querySelector('.cf-name')?.textContent || 'back');
      const rec = { text: n.querySelector('span').textContent, links: [], large: names('.fx-spotlight .card'), flying: names('.fx-ghost .card') };
      window.__caps.push(rec);
      n.__rec = rec;
    } else if (n.classList.contains('fx-log-link') && n.parentElement && n.parentElement.__rec) {
      n.parentElement.__rec.links.push(+n.textContent.split(' ')[1]);
    }
  } }).observe(document, { childList: true, subtree: true });
"""


Replay = namedtuple("Replay", "steps log stamps blocks drawn")


def replay(browser, name):
    """The board watching a stretch of a live game (fixtures/ui_<name>.json.gz; see its "_source"): hello, then every
    message the seat (or the spectator) got, at once. Returns, once the steps are over: the steps it showed,
    [(caption, [log lines it links])]; the log, {index: text}; the stamps on cards that left the stack countered or
    fizzled; the blockers' tags it showed; what each step drew, {caption: (cards shown large, cards flying)}."""
    fx = json.loads(gzip.decompress((FIXTURES / f"ui_{name}.json.gz").read_bytes()))
    page = new_page(browser, fx["hello"])
    page.add_init_script(CAPTIONS)

    def ws(route):
        for m in [fx["hello"], *fx["messages"]]:
            route.send(json.dumps(m))

    page.route_web_socket("**/ws/game/**", ws)
    go(page, fx["hello"]["game_id"], "fast", fx["hello"]["seat"])
    page.wait_for_selector(".fx-caption")
    page.wait_for_function("() => document.querySelector('.skip-btn').classList.contains('hidden')", timeout=90000)
    page.wait_for_timeout(200)
    caps = page.evaluate("() => window.__caps")
    steps = [(c["text"], c["links"]) for c in caps]
    log = dict(page.evaluate("() => [...document.querySelectorAll('.log-line')].map((l) => [+l.dataset.i, l.textContent])"))
    stamps, blocks = page.evaluate("() => [window.__stamps, window.__blocks]")
    assert page.errors == []
    page.close()
    return Replay(steps, log, stamps, blocks, {c["text"]: (c["large"], c["flying"]) for c in caps})


def name_alike(caption, line):
    """Whether a log line names one of the cards or players a caption names (capitalised words in common)."""
    words = lambda s: set(re.findall(r"[A-Z][\w',-]+", s)) - {"Combat", "Ability", "TURN"}
    return bool(words(caption) & words(line))


def test_a_countered_spell_is_shown_countered_never_as_resolving(browser):
    """A countered spell leaves the stack for its owner's graveyard, as a spell that resolved does: the engine says
    why (the move's reason), and the step says what the log says, linking both lines. Opp's Eardrum Rattler is
    countered by Human's Icy Reception, which itself resolves."""
    steps, log, stamps, *_ = replay(browser, "counter")
    assert steps == [
        ("Opp draws a card", [24]),
        ("Opp plays Mountain", [25, 26]),
        ("Opp casts Eardrum Rattler", [27]),
        ("Human casts Icy Reception → Eardrum Rattler", [28]),
        ("Eardrum Rattler is countered by Icy Reception → graveyard", [30, 31]),
        ("Icy Reception resolves → graveyard", [32]),
    ]
    assert log[30].startswith("Opp puts Eardrum Rattler [") and " from stack into their graveyard" in log[30]
    assert re.fullmatch(r"Eardrum Rattler \[\w+\] is countered by Icy Reception \[\w+\]", log[31])
    assert stamps == ["Countered"]  # on the Rattler's card, as it flies to the graveyard


def test_a_spell_that_fizzles_is_shown_fizzling_never_as_resolving(browser):
    """Opp's Last Gasp targets Human's Cryotheory Adept; Human returns the Adept to its hand with Unsummon, so Last
    Gasp has no legal target when it resolves, and fizzles (XMage counters it then): the step says so."""
    steps, log, stamps, *_ = replay(browser, "fizzle")
    assert steps == [
        ("Opp draws a card", [36]),
        ("Opp plays Swamp", [37, 38]),
        ("Opp casts Last Gasp → Cryotheory Adept", [39]),
        ("Human casts Unsummon → Cryotheory Adept", [40]),
        ("Cryotheory Adept triggers", [41]),
        ("Cryotheory Adept → hand", [42]),
        ("Unsummon resolves → graveyard", [43]),
        ("Last Gasp fizzles: no legal target → graveyard", [44, 45]),
    ]
    assert log[44] == "Last Gasp has been fizzled."
    assert stamps == ["Fizzled"]


def test_deaths_link_their_own_lines_not_the_next_turns(browser):
    """Combat kills two creatures at the end of Opp's turn, then Human's turn begins with a draw: each death links its
    own "died" line, and the draw its own line."""
    steps, log, *_ = replay(browser, "deaths")
    deaths = dict(steps)["Konstrari Improviser → graveyard · Cryotheory Adept → graveyard"]
    assert [log[i] for i in deaths] == ["Konstrari Improviser [fc3] died", "Cryotheory Adept [ed0] died"]
    assert log[deaths[-1] + 1].startswith("TURN 17 ")
    assert [log[i] for i in dict(steps)["Human draws Goblin Oriflamme"]] == ["Human draws a card"]
    assert all(links and all(name_alike(c, log[i]) for i in links) for c, links in steps), steps


def test_discards_draws_and_life_link_their_own_lines(browser):
    """Arc of Fortune: each player discards its hand and draws seven. Seize the Spoils: its discard is a cost, logged
    before the cast. Two Impact Tremors and Authority of the Consuls trigger together: a gain and two losses."""
    steps, log, *_ = replay(browser, "discards")
    linked = lambda caption: [log[i] for c, links in steps if c == caption for i in links]
    assert linked("Germinate Recruits → graveyard") == ["Opp discards Germinate Recruits [2f0] (source: Arc of Fortune [74a])"]
    assert linked("Heartstring Puller → graveyard") == ["Human discards Heartstring Puller [b46] (source: Arc of Fortune [74a])"]
    assert linked("Opp draws 7 cards") == ["Opp draws seven cards"]
    assert linked("Human draws 7 cards") == ["Human draws seven cards"]
    assert linked("Goblin Oriflamme → graveyard") == ["Human discards Goblin Oriflamme [893] (source: Seize the Spoils [c14])"]
    assert linked("Human casts Seize the Spoils") == ["Human casts Seize the Spoils [c14] from hand"]
    assert linked("Opp gains 1 life") == ["Opp gains 1 life (source: Authority of the Consuls [24e])"]
    assert linked("Impact Tremors deals 1 damage to Opp") == ["Opp loses 1 life from Impact Tremors [661]",
                                                             "Opp loses 1 life from Impact Tremors [ff5]"]
    assert all(links and all(name_alike(c, log[i]) for i in links) for c, links in steps), steps


def test_a_blocker_whose_attacker_died_is_tagged_blocking(browser):
    """Corsair Captain blocks Pia, Determined Rebuilder, which dies in combat: XMage takes Pia out of its combat group,
    and the Captain stays blocking (506.4). Its tag said "blocks ?"; it names Pia while Pia is there, then says
    "blocking". The death links its own line."""
    r = replay(browser, "combat")
    assert r.blocks == ["blocks Pia, Determined Rebuilder", "blocking"]
    assert [r.log[i] for i in dict(r.steps)["Pia, Determined Rebuilder → graveyard"]] == ["Pia, Determined Rebuilder [9fe] died"]
    assert all(links and all(name_alike(c, r.log[i]) for i in links) for c, links in r.steps), r.steps


def test_ward_counters_a_spell_and_an_ability_and_says_so(browser):
    """Opp aims two Burst Lightnings and then Fanatical Firebrand's ability at Unflinching Hortimancer, whose ward
    counters each one (Opp doesn't pay). A spell leaves the stack countered; an ability, which has no card to move,
    is countered in place: both are named with what countered them."""
    steps, log, stamps, *_ = replay(browser, "ward")
    assert [c for c, _ in steps].count("Burst Lightning is countered by Unflinching Hortimancer → graveyard") == 2
    assert stamps == ["Countered", "Countered"]
    ability = dict(steps)["Fanatical Firebrand's ability is countered by Unflinching Hortimancer"]
    assert [log[i] for i in ability] == ["Ability ({T}, Sacrifice Fanatical Firebrand [569]: It deals 1 damage to any target.) "
                                         "of Fanatical Firebrand [569] is countered by Unflinching Hortimancer [1c8]"]
    assert not [c for c, _ in steps if "resolves" in c]
    assert all(links and all(name_alike(c, log[i]) for i in links) for c, links in steps), steps


def test_a_search_is_not_a_draw_and_the_revealed_card_is_shown(browser):
    """Basic landcycling (Apex Witchstalker: search for a basic land card, reveal it, put it into your hand), by Human
    on turn 3 and by Opp on turn 4, seen from Human's seat. Only the turns' draws say "draws"; the engine says which
    moves are draws (and which lands were played). A searched card says what XMage's line says and links it, though
    that line names no card; the reveal that follows shows the card large and links its line. Opp's card is hidden
    until then."""
    r = replay(browser, "landcycle")
    assert r.steps == [
        ("Opp draws a card", [14]),
        ("Opp plays Swamp", [15, 16]),
        ("Human draws Swamp", [19]),
        ("Human plays Swamp", [20, 21]),
        ("Apex Witchstalker → graveyard", [22]),
        ("Human activates Apex Witchstalker", [23]),
        ("Human puts Swamp from library into hand", [25]),
        ("Human reveals Swamp", [26]),
        ("Opp draws a card", [30]),
        ("Opp plays Swamp", [31, 32]),
        ("Apex Witchstalker → graveyard", [33]),
        ("Opp activates Apex Witchstalker", [34]),
        ("Opp puts a card from library into hand", [36]),
        ("Opp reveals Swamp", [37]),
    ]
    assert [r.log[i] for i in (19, 25, 26, 30, 36, 37)] == [
        "Human draws a card", "Human puts a card from library into their hand", "Human reveals Swamp",
        "Opp draws a card", "Opp puts a card from library into their hand", "Opp reveals Swamp"]
    assert r.drawn["Human puts Swamp from library into hand"] == ([], ["Swamp"])
    assert r.drawn["Opp puts a card from library into hand"] == ([], ["back"])
    assert r.drawn["Human reveals Swamp"] == (["Swamp"], []) and r.drawn["Opp reveals Swamp"] == (["Swamp"], [])


def test_a_spectator_sees_both_searches_as_searches(browser):
    """The same turns, watched by a spectator, who sees neither hand: both searched cards fly face down and are named
    once revealed; the draws stay draws."""
    r = replay(browser, "landcycle_watch")
    linked = dict(r.steps)
    assert [c for c, _ in r.steps if "draws" in c] == ["Opp draws a card", "Human draws a card", "Opp draws a card"]
    assert (linked["Human puts a card from library into hand"], linked["Human reveals Swamp"]) == ([25], [26])
    assert (linked["Opp puts a card from library into hand"], linked["Opp reveals Swamp"]) == ([36], [37])
    assert r.drawn["Human puts a card from library into hand"] == ([], ["back"])
    assert r.drawn["Opp reveals Swamp"] == (["Swamp"], [])
    assert all(links and all(name_alike(c, r.log[i]) for i in links) for c, links in r.steps), r.steps


def test_a_revealed_hand_is_shown_whole(browser):
    """Solve for Disappointment makes the other seat reveal its hand: Opp's seven cards on turn 3, Human's five on turn
    4. Every card is shown large and named, as XMage's line names them, and the step links that line."""
    r = replay(browser, "reveal")
    opp = "Opp reveals Swamp, Swamp, Swamp, Swamp, Solve for Disappointment, Solve for Disappointment, Solve for Disappointment"
    human = "Human reveals Swamp, Swamp, Solve for Disappointment, Solve for Disappointment, Solve for Disappointment"
    linked = dict(r.steps)
    assert [r.log[i] for i in linked[opp]] == [opp] and [r.log[i] for i in linked[human]] == [human]
    assert r.drawn[opp] == (opp.split(" reveals ")[1].split(", "), [])
    assert r.drawn[human] == (human.split(" reveals ")[1].split(", "), [])
    assert all(links and all(name_alike(c, r.log[i]) for i in links) for c, links in r.steps), r.steps
