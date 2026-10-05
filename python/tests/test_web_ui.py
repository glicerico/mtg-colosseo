"""Browser tests of the game board (web/), with Playwright.

The page is served from web/ through Playwright routes and its WebSocket is mocked: each test plays back real
seat-0 messages recorded from an engine game (fixtures/ui_board.json; see its "_source"), so no server is needed.

    pip install playwright pytest && playwright install chromium
    python -m pytest python/tests -m ui
"""
import copy
import json
import mimetypes
from pathlib import Path

import pytest

sync_api = pytest.importorskip("playwright.sync_api")

pytestmark = pytest.mark.ui

WEB = Path(__file__).resolve().parents[2] / "web"
FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "ui_board.json").read_text())
BASE = "http://colosseo.test"


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        b = p.chromium.launch()
        yield b
        b.close()


def open_board(browser, decision, size=(1920, 1080), card_scale=None, mutate=None):
    """Opens seat 0 of the fixture game showing one recorded decision; returns the page."""
    hello = copy.deepcopy(FIXTURE["hello"])
    msg = copy.deepcopy(FIXTURE["decisions"][str(decision)])
    if mutate:
        mutate(msg["state"])
    gid = hello["game_id"]
    page = browser.new_page(viewport={"width": size[0], "height": size[1]})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.errors = errors

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
        route.send(json.dumps(msg))

    page.route(f"{BASE}/**", serve)
    page.route_web_socket("**/ws/game/**", ws)
    page.goto(BASE + "/")
    page.evaluate("s => { localStorage.setItem('colosseo.images', 'off');"
                  " if (s) localStorage.setItem('colosseo.cardScale', s); else localStorage.removeItem('colosseo.cardScale'); }",
                  str(card_scale) if card_scale else None)
    page.goto(f"{BASE}/#/game/{gid}/seat/0")
    page.reload()  # a hash change keeps the document: reload so the settings above are read
    page.wait_for_selector(".prompt-bar.active")
    page.wait_for_timeout(200)
    return page


def zone_names(page, side, zone):
    return page.eval_on_selector_all(f".side.{side} .zone.{zone} .card .cf-name", "els => els.map(e => e.textContent)")


def card(page, name):
    return page.locator(f'.zone .card:has(.cf-name:text-is("{name}"))').first


def test_zones_separate_creatures_lands_and_other_permanents(browser):
    page = open_board(browser, 68)
    # opponent (top): its Treasure is not in the creature row
    assert "Treasure Token" in zone_names(page, "top", "others")
    assert "Treasure Token" not in zone_names(page, "top", "creatures")
    assert set(zone_names(page, "top", "creatures")) == {"Erudite Wizard", "Bear Cub", "Eager Trufflesnout"}
    assert set(zone_names(page, "top", "lands")) == {"Forest", "Mountain"}
    assert zone_names(page, "bottom", "creatures") == ["Inspiring Overseer"]
    assert set(zone_names(page, "bottom", "lands")) == {"Island", "Plains"}
    assert not page.is_visible(".side.bottom .zone.others")  # nothing there: the area is hidden
    assert page.errors == []


def test_identical_tokens_stack_with_a_count(browser):
    page = open_board(browser, 8)
    stacks = page.locator(".side.top .zone.creatures .token-stack")
    assert stacks.count() == 1
    assert stacks.first.get_attribute("data-count") == "2"
    assert stacks.first.locator(".stack-count").inner_text() == "×2"
    assert stacks.first.locator(".card").count() == 1  # one card width for both tokens
    # a stack splits when its tokens differ: tap one of them
    def tap_one(st):
        opp = next(p for p in st["players"] if p["seat"] == 1)
        next(c for c in opp["battlefield"] if c["name"] == "Goblin Token")["tapped"] = True
    page2 = open_board(browser, 8, mutate=tap_one)
    assert page2.locator(".side.top .token-stack").count() == 0
    assert len([n for n in zone_names(page2, "top", "creatures") if n == "Goblin Token"]) == 2


def attach_test_permanents(state):
    """Adds an aura (controlled by the opponent) and an equipment to seat 0's creature: synthetic objects, as the
    fixture game's decks have no auras or equipment; the layout only reads attached_to and controller."""
    me = next(p for p in state["players"] if p["seat"] == 0)
    opp = next(p for p in state["players"] if p["seat"] == 1)
    host = next(c for c in me["battlefield"] if c["name"] == "Inspiring Overseer")
    base = {"set": None, "number": None, "mana_cost": "", "mana_value": 1, "supertypes": [], "colors": "", "rules": [],
            "keywords": [], "counters": {}, "token": False, "tapped": False, "damage": 0, "attacking": False,
            "blocking": False, "summoning_sick": False, "face_down": False, "attachments": [], "attached_to": host["id"]}
    opp["battlefield"].append({**base, "id": "00000000-0000-4000-8000-0000000000a1", "name": "Test Aura",
                               "types": ["Enchantment"], "subtypes": ["Aura"], "controller": opp["id"], "owner": opp["id"]})
    me["battlefield"].append({**base, "id": "00000000-0000-4000-8000-0000000000e1", "name": "Test Sword",
                              "types": ["Artifact"], "subtypes": ["Equipment"], "controller": me["id"], "owner": me["id"]})


def test_attachments_stay_with_their_host(browser):
    page = open_board(browser, 68, mutate=attach_test_permanents)
    group = page.locator(".side.bottom .zone.creatures .attach-group")
    assert group.count() == 1
    names = group.first.locator(".cf-name").all_inner_texts()
    assert names[-1] == "Inspiring Overseer" and set(names[:-1]) == {"Test Aura", "Test Sword"}
    # the opponent's aura is drawn with its host, marked as the other player's, and not in the opponent's rows
    assert "foreign" in group.first.locator('.card:has(.cf-name:text-is("Test Aura"))').get_attribute("class")
    assert "Test Aura" not in zone_names(page, "top", "others") + zone_names(page, "top", "creatures")
    assert "Test Sword" not in zone_names(page, "bottom", "others")
    assert page.errors == []


def test_modified_power_toughness_is_marked(browser):
    page = open_board(browser, 68)
    pt = card(page, "Erudite Wizard").locator(".card-pt")
    assert pt.inner_text() == "7/5"
    assert "pt-up" in pt.get_attribute("class")
    assert "printed 2/3" in pt.get_attribute("title")
    assert card(page, "Erudite Wizard").locator(".badge-effect").count() == 1
    # unmodified creatures are not marked
    bear = card(page, "Bear Cub").locator(".card-pt")
    assert bear.inner_text() == "2/2" and "pt-" not in bear.get_attribute("class")
    assert card(page, "Bear Cub").locator(".badge-effect").count() == 0


def test_hover_explains_printed_counters_and_effects(browser):
    page = open_board(browser, 68)
    card(page, "Erudite Wizard").hover(force=True)
    preview = page.locator("#preview")
    preview.wait_for(state="visible")
    text = preview.locator(".explain").inner_text()
    assert "7/5" in text and "printed 2/3" in text
    assert "+2/+2" in text and "2 +1/+1 counters" in text
    assert "+3/+0" in text and "Sure Strike, until end of turn" in text
    assert "Sure Strike: +3/+0, first strike until end of turn" in text
    assert "Involuntary Employment: control changed, haste until end of turn" in text


def test_instant_pump_is_explained(browser):
    page = open_board(browser, 59)
    pt = card(page, "Inspiring Overseer").locator(".card-pt")
    assert pt.inner_text() == "4/3" and "pt-up" in pt.get_attribute("class")
    card(page, "Inspiring Overseer").hover(force=True)
    text = page.locator("#preview .explain").inner_text()
    assert "printed 2/1" in text
    assert "Adamant Will: +2/+2, indestructible until end of turn" in text


def test_click_pins_the_explanation(browser):
    page = open_board(browser, 68)
    card(page, "Bear Cub").click(force=True)  # nothing to do with it now: the click pins its preview
    assert "pinned" in page.get_attribute("#preview", "class")
    page.mouse.move(5, 5)
    assert page.is_visible("#preview")
    card(page, "Bear Cub").click(force=True)
    assert not page.is_visible("#preview")


def set_slider(page, value):
    page.eval_on_selector("input.card-size", "(el, v) => { el.value = v; el.dispatchEvent(new Event('input')); }", str(value))
    page.wait_for_timeout(300)  # cards move with a short transition


def card_width(page):
    """The card width the board is laid out with (a creature's, unless its row had to shrink)."""
    return layout(page)["w"]


SIZES = [(1366, 768), (1920, 1080), (2560, 1440), (3840, 2160)]

IN_VIEW = """() => [...document.querySelectorAll('.zone.creatures .card')].map(c => {
    const b = c.getBoundingClientRect(), s = c.closest('.side').getBoundingClientRect();
    return b.top >= s.top - 1 && b.bottom <= s.bottom + 1 && b.left >= s.left - 1 && b.right <= s.right + 1
        && b.top >= 0 && b.bottom <= innerHeight; })"""

NO_SCROLL = ("() => [...document.querySelectorAll('.side')].every(s => s.scrollHeight <= s.clientHeight + 1)"
             " && document.documentElement.scrollHeight <= innerHeight + 1")


def crowd(st):
    """Fills the opponent's side: 14 more creatures, 6 more lands and 4 more Treasures (copies of its own)."""
    opp = next(p for p in st["players"] if p["seat"] == 1)
    for name, n in (("Bear Cub", 14), ("Forest", 6), ("Treasure Token", 4)):
        src = next(c for c in opp["battlefield"] if c["name"] == name)
        for i in range(n):
            opp["battlefield"].append({**src, "id": f"00000000-0000-4000-8000-{len(opp['battlefield']):012d}",
                                       "name": f"{name} {i}" if name == "Bear Cub" else name})


def layout(page):
    return json.loads(page.get_attribute(".board", "data-layout"))


@pytest.mark.parametrize("size", SIZES)
def test_cards_scale_with_the_window_without_scrolling(browser, size):
    page = open_board(browser, 68, size=size)
    w = card_width(page)
    assert 48 <= w <= 480
    # the board fits: neither side scrolls, and the page does not; every creature is in view
    assert page.evaluate(NO_SCROLL)
    assert all(page.evaluate(IN_VIEW))
    assert page.errors == []


def test_cards_grow_on_larger_windows(browser):
    widths = {s: card_width(open_board(browser, 68, size=s)) for s in SIZES}
    w = [widths[s] for s in sorted(widths)]
    assert w == sorted(w) and w[-1] >= 2.5 * w[0], widths
    assert widths[(2560, 1440)] >= 1.25 * widths[(1920, 1080)], widths


@pytest.mark.parametrize("size", [(2560, 1440), (3840, 2160)])
def test_large_screens_are_used(browser, size):
    # a typical board (the operator's game never had more than 10 permanents a side): its rows are sized from
    # what is on them, and lands go beside the creatures, so the cards take the width of the board
    page = open_board(browser, 68, size=size)
    assert layout(page)["beside"] == {"top": True, "bottom": True}
    assert card_width(page) >= 0.085 * size[0]  # 218 px at 2560, 326 at 3840 (164 and 246 before)
    used = page.evaluate("""() => { const b = document.querySelector('.board').getBoundingClientRect();
        const r = Math.max(...[...document.querySelectorAll('.side.top .card')].map(c => c.getBoundingClientRect().right));
        return (r - b.left) / b.width; }""")
    assert used >= 0.9


def test_full_boards_put_lands_behind_the_creatures(browser):
    page = open_board(browser, 68, size=(1920, 1080), mutate=crowd)
    assert layout(page)["beside"]["top"] is False
    assert page.evaluate(NO_SCROLL) and all(page.evaluate(IN_VIEW))


def test_card_size_control(browser):
    page = open_board(browser, 68)
    base = card_width(page)
    slider = page.locator("input.card-size")
    assert slider.input_value() == "1"
    set_slider(page, 1.2)
    assert card_width(page) == pytest.approx(base * 1.2, abs=3)
    assert page.locator(".card-size-value").inner_text() == "120%"
    assert page.evaluate("() => localStorage.getItem('colosseo.cardScale')") == "1.2"
    set_slider(page, 0.6)
    assert card_width(page) == pytest.approx(base * 0.6, abs=3)
    # the setting survives a reload
    page2 = open_board(browser, 68, card_scale=0.6)
    assert card_width(page2) == pytest.approx(base * 0.6, abs=3)


@pytest.mark.parametrize("size", SIZES)
@pytest.mark.parametrize("decision,mutate", [(8, None), (59, None), (68, None), (68, crowd)], ids=["d8", "d59", "d68", "d68-crowded"])
def test_creature_rows_stay_in_view_at_every_card_size(browser, size, decision, mutate):
    page = open_board(browser, decision, size=size, mutate=mutate)
    base = card_width(page)
    for value in (0.6, 1, 1.3, 1.6):
        set_slider(page, value)
        assert page.evaluate(NO_SCROLL), value
        assert all(page.evaluate(IN_VIEW)), value
        hand = page.evaluate("() => [...document.querySelectorAll('.hand-row.bottom .card')].every(c => c.getBoundingClientRect().bottom <= innerHeight)")
        assert hand, value
    # the largest size is larger than the window's own unless the creatures already fill the width, and says
    # when it is limited by the window
    assert card_width(page) >= base if mutate else card_width(page) > base
    label = page.locator(".card-size-value").inner_text()
    assert label == "160%" or label.endswith("% max")
    # the cards are drawn at that size: no creature row had to shrink (unless even the smallest cards don't fit)
    assert card_width(page) == 48 or page.evaluate("() => [...document.querySelectorAll('.zone.creatures')].every(z => !z.style.getPropertyValue('--fit'))")
    assert page.errors == []


def test_crowded_rows_shrink_instead_of_wrapping(browser):
    def crowd(st):
        opp = next(p for p in st["players"] if p["seat"] == 1)
        bear = next(c for c in opp["battlefield"] if c["name"] == "Bear Cub")
        for i in range(14):
            opp["battlefield"].append({**bear, "id": f"00000000-0000-4000-8000-0000000001{i:02d}", "name": f"Bear {i}"})
    page = open_board(browser, 68, size=(1366, 768), mutate=crowd)
    row = page.locator(".side.top .zone.creatures")
    assert row.evaluate("r => r.scrollWidth <= r.clientWidth + 1")
    tops = row.evaluate("r => new Set([...r.querySelectorAll(':scope > .card')].map(c => c.offsetTop)).size")
    assert tops == 1  # one line
