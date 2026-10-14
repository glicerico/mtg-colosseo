# Reality Fracture (FRA) precons

Ten 40-card decks, one per FRA two-colour archetype, built from the engine's 263 implemented FRA nonland
cards (numbers 1-401) plus basic lands. Auto-discovered by `DeckLibrary` — no engine change was needed;
dropping `decks/fra/*.dck` is enough (`decks/fra/wu-fatehold.dck` → deck id `fra:wu-fatehold`).

Each deck: ~23 nonland cards (14-15 creatures), 17 lands (3 copies of the guild's common tapland, the rare
check land where one is printed, basics for the rest), 2-3 nonland rares/mythics, at most 2 copies of a
rare/mythic and at most 3 of anything else. Never uses any of the goal's 17 missing cards.

## The ten archetypes

Identified from the set's gold uncommons/legendary signposts (cross-checked against
`Mage.Sets/src/mage/sets/RealityFracture.java`): every two-colour pair has an implemented legendary
"signpost" creature except U/B, whose signpost (**Uldaros Theorix**) is on the goal's missing-card list —
that deck is built around its Charm and the surveil/mill/prepared cards instead. **W/G's signpost, Kwia
Vigorbloom (a 6/6 flying/vigilance/lifelink/ward-2 mythic for 5), was cut for balance**: it alone swung
several pairings to 9-1 or worse. The deck's rare slots go to two on-plan lifegain rares instead,
**Solarium Sentry** (the pair's G/W rare) and **Germinate Recruits** (white rare that turns life gained into
tokens). The deck keeps its name and lifegain/counters plan; it just doesn't run its namesake card, the same
way `ub-theorix` doesn't run its (missing) one.

| Deck (`fra:<id>`) | Archetype | Plan | Key cards | Mechanics shown |
|---|---|---|---|---|
| `wu-fatehold` | Fatehold Scryfliers (W/U) | Scry/surveil triggers pump a team of fliers; flash and bounce buy time. | Denzilore Fatehold, Proctor of Potential, Variable Chaser | scry/surveil, prepared, flash |
| `wb-edgar` | Edgar's Mourners (W/B) | Lifegain and death triggers turn every ETB/death into value. | Edgar Ancient Bloodlord, Enlightened Confidant, Darklight Phoenix | lifegain, prepared |
| `wr-mabel` | Mabel's Vanguard (W/R) | Cheap bodies get +1/+1 counters as they enter; equipment closes it out. | Mabel Valley Hero, Stingcaster Mage, Warrior's Blades | +1/+1 counters, Empower Jace |
| `wg-kwia` | Kwia's Bloom (W/G) | Lifegain fuels +1/+1 counters on a team of vigilant bodies (signpost cut for power, see above). | Solarium Sentry, Germinate Recruits, Titanbones Towering Heart, Unflinching Hortimancer | lifegain, counters, prepared |
| `ub-theorix` | Theorix's Archive (U/B) | Surveil/mill fills the yard for recursion and control (no signpost: see above). | Theorix Charm, Bloodline Recollector, Sphinx of False Conclusions | surveil/mill, prepared, threshold, Empower Jace |
| `ur-saheeli` | Saheeli's Workshop (U/R) | Casting spells makes Thopters and grows prowess bodies. | Saheeli Jewel of Avishkar, Pia Determined Rebuilder, Draconic Visitor | artifacts matter, prowess, Empower Jace |
| `ug-avatar` | Avatar's Echo (U/G) | Landfall value and ramp into big fights. | Avatar of Burgeoning Echoes, Tam the Possibility, Mind Meanderer | landfall, Empower Jace, prepared |
| `br-ingris` | Ingris's Thornguard (B/R) | Aggressive sacrifice/attack triggers ping the opponent down. | Ingris Stingerquill, Stinging Vitriol, Gallia Tragic Host | prepared, direct damage, counters |
| `bg-vraska` | Vraska's Undergrowth (B/G) | Mill/graveyard value powers deathtouch and land recursion. | Vraska the Cutting Glare, Tarmogoyf, Primal Witchstalker | graveyard payoffs, deathtouch, mill |
| `rg-aerid` | Aerid's Foundry (R/G) | Artifact creatures and Heartwood tokens overrun the board. | Aerid Konstrari, Craftwork Crusher, Tenured Tethermage | artifacts matter, counters, prepared |

Across the ten decks the set's two new mechanics both show up repeatedly: **Empower Jace** (bonus clauses
across W/U/B/R/G spells; two Jace planeswalkers exist in the pool for it to key off of) and **prepared**
(a cycle of hybrid creatures, one or more per guild, plus several mono-colour ones concentrated in U/R/B).

## Checks run

All checks below were run on these exact lists; details, per-game logs and scripts are in the project
report's evidence folder (`projects/mtg-player/reports/2026-10-07-fra-1-evidence/` in the workspace).

- **Load check**: all ten load via `GET /api/decks/<id>` (40 FRA cards, 17 lands, 14-16 creatures, 2-3
  nonland rares/mythics, copy limits respected, none of the 17 missing cards) and one XMage mirror game
  per deck finished with no error (`load-check.txt`).
- **Cast coverage**: all 230 nonland cards (23 x 10) were seen cast in a 900-game round robin, spectated
  live over each game's WebSocket log. `cast-coverage.tsv` maps every card to its cast count, the first
  `game_id` it was cast in and the exact log line; `round-robin-games.jsonl.gz` holds every game's full
  cast log.
- **Balance**: 45-pairing round robin (XMage skill 3 vs itself, 20 games per pairing, starting seat swapped
  every game), 900 games, none unfinished. Overall win rates run from 42.2% to 63.9%; the most lopsided
  pairing is 15-5 (75%). Table in `round-robin-table.txt`.
- **Human play**: one scripted-human browser game (`adapters/colosseum/sandbox/human_browser.py` from
  mtg-player), `fra:wg-kwia` (human seat) vs `fra:rg-aerid` (XMage AI), ran end to end and finished with no
  script errors; the lobby lists all ten FRA decks.
