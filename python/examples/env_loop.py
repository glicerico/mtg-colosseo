"""Step/reset loop (RL style) with a random policy, collecting (decision kind, reward) statistics."""
import collections
import random

from colosseo import ColosseoEnv

env = ColosseoEnv(deck="fdn:selesnya-pack", opponent="xmage:1", opponent_deck="fdn:rakdos-raiders", max_turns=40)
kinds = collections.Counter()
for episode in range(3):
    d, total = env.reset(), 0.0
    while d is not None:
        kinds[d.kind] += 1
        action = d.choose(random.choice(d.options)) if d.kind in ("priority", "target", "yes_no") and d.options else d.default()
        d, reward, done, info = env.step(action)
        total += reward
    print(f"episode {episode}: reward {total}, result {env.result}")
print(kinds)
