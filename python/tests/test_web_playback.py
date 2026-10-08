"""Browser tests of the opponent's turn shown step by step (web/js/game.js, web/js/fx.js), with Playwright.

The page is served from web/ and its WebSocket is mocked with one opponent turn recorded in a browser on a live game
(fixtures/ui_turn.json.gz; see its "_source"). Our agent draws, casts Stingerquill Charm on Semester Foreseer and
attacks with Ingris Stingerquill, whose trigger deals 1 damage to the human. The human blocks with Shatterwing
Pegasus, and combat damage follows. The turn's messages arrive at once, as the engine sends them; once the page
answers the block, what followed.
"""
import gzip
import json
import mimetypes
import time
from pathlib import Path

import pytest

sync_api = pytest.importorskip("playwright.sync_api")

pytestmark = pytest.mark.ui

WEB = Path(__file__).resolve().parents[2] / "web"
TURN = json.loads(gzip.decompress((Path(__file__).parent / "fixtures" / "ui_turn.json.gz").read_bytes()))
BASE = "http://colosseo.test"

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


def open_turn(browser, pace="fast", size=(1600, 900)):
    hello = TURN["hello"]
    gid = hello["game_id"]
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

    page.route(f"{BASE}/**", serve)
    page.route_web_socket("**/ws/game/**", ws)
    page.goto(BASE + "/")
    page.evaluate("p => { localStorage.setItem('colosseo.images', 'off'); localStorage.setItem('colosseo.pace', p); }", pace)
    page.goto(f"{BASE}/#/game/{gid}/seat/0")
    page.reload()
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
