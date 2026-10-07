# Reality Fracture (FRA) precons

Ten 40-card decks, one per FRA two-colour archetype, built from the engine's 263 implemented FRA nonland
cards (numbers 1-401) plus basic lands. Auto-discovered by `DeckLibrary` — no engine change was needed;
dropping `decks/fra/*.dck` is enough (`decks/fra/wu-fatehold.dck` → deck id `fra:wu-fatehold`).

Each deck: ~23 nonland cards (14-15 creatures), 17 lands (3 copies of the guild's common tapland, the rare
check land where one is printed, basics for the rest), 2-3 rares/mythics, at most 2 copies of a
rare/mythic and at most 3 of anything else. Never uses any of the goal's 17 missing cards.

## The ten archetypes

Identified from the set's gold uncommons/legendary signposts (cross-checked against
`Mage.Sets/src/mage/sets/RealityFracture.java`): every two-colour pair has an implemented legendary
"signpost" creature except U/B, whose signpost (**Uldaros Theorix**) is on the goal's missing-card list —
that deck is built around its Charm and the surveil/mill/prepared cards instead.

| Deck (`fra:<id>`) | Archetype | Plan | Key cards | Mechanics shown |
|---|---|---|---|---|
| `wu-fatehold` | Fatehold Scryfliers (W/U) | Scry/surveil triggers pump a team of fliers; flash and bounce buy time. | Denzilore Fatehold, Proctor of Potential, Variable Chaser | scry/surveil, prepared, flash |
| `wb-edgar` | Edgar's Mourners (W/B) | Lifegain and death triggers turn every ETB/death into value. | Edgar Ancient Bloodlord, Enlightened Confidant, Darklight Phoenix | lifegain, prepared |
| `wr-mabel` | Mabel's Vanguard (W/R) | Cheap bodies get +1/+1 counters as they enter; equipment closes it out. | Mabel Valley Hero, Stingcaster Mage, Warrior's Blades | +1/+1 counters, Empower Jace |
| `wg-kwia` | Kwia's Bloom (W/G) | Lifegain fuels counters and card draw on big vigilant fliers. | Kwia Vigorbloom, Bloombrute, Solarium Sentry | lifegain, counters, prepared |
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

- **Load check**: all ten load via `GET /api/decks/<id>` and in a real game (XMage AI vs itself) with zero
  unknown-card errors.
- **Cast coverage**: every one of the 230 nonland cards (23 × 10) was seen cast in a logged game (spectated
  over the games' WebSocket log). See `2026-10-07-fra-1-evidence/cast-coverage.txt` and
  `cast-coverage-notes.md` in the project report's evidence folder.
- **Balance**: 45-pairing round robin (XMage skill 3 vs itself, 10+ games per pairing, starting seat
  swapped). Every deck's overall win rate lands between 40% and 65%; no pairing is 9-1 or worse. First pass
  found `wg-kwia` overperforming (71%) and `ur-saheeli`/`bg-vraska` underperforming (33-34%), including one
  9-1 pairing (`wb-edgar` vs `wg-kwia`); a handful of same-cost card swaps (see the git history of this
  folder) fixed it. Table in `2026-10-07-fra-1-evidence/round-robin-table.txt`.
- **Human play**: one sandboxed-browser game (`adapters/colosseo/sandbox/human_browser.py` from mtg-player)
  ran end to end, `fra:wu-fatehold` (human seat) vs `fra:br-ingris` (XMage AI), finished with no errors; the
  lobby screenshot confirms all ten FRA decks are pickable in the web UI.
