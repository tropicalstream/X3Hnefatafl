# X3Hnefatafl 👑

**Hnefatafl** — Viking chess — for the **RayNeo X3 Pro** AR glasses. A lone,
brightly glowing **King** and his small ring of white guards stand besieged at
the centre by a massive host of dark-red attackers. The King runs for a corner;
the host closes in. It's an asymmetric duel, and the game leans into the drama.

Part of the X3 game suite, so it inherits every on-device-proven pattern:
640×480 logical canvas, binocular side-by-side rendering, pure-black waveguide
background, one-swipe-per-step navigation, dwell-to-commit moves,
auto-save/resume — zero vendor AARs, zero permissions, zero binary assets
(engine, AI, and sound are pure Kotlin, synthesized at runtime).

## The rules (11×11 Fetlar)

- **Attackers** (24, dark red) besiege from the edges and **move first**.
- **Defenders** (12 white guards + the King) start ringed around the central
  throne.
- Every piece moves orthogonally any distance, like a rook — no jumping.
- Only the **King** may stop on the throne or the four corners.
- **Capture** is custodial: sandwich an enemy soldier between two of your
  pieces (a corner or the empty throne counts as one of yours).
- The **King is captured** only when hemmed in on all four sides by attackers
  or the throne — the board edge is safe.
- **Defenders win** if the King reaches a corner. **Attackers win** if they
  capture him.

Illegal moves are explained ("Pieces move in straight lines, like a rook",
"Only the King may enter the throne or a corner", …).

## Controls (right temple pad)

| Gesture | Action |
|---|---|
| **Swipe ↑ ↓ ← →** | Move the cursor one square (menus: navigate) |
| **Click** (temple tap) | Lift a piece · place it · confirm |
| **Double-tap** | Open / close **settings** any time |
| Left temple pad | System volume (ignored) |

The **cursor and the turn indicator switch color to whoever is moving** — a
red host icon on the attackers' turn, the glowing King on the defenders'. Pick
a piece (its legal squares light up), swipe to a target, let the ring fill,
then click to commit. Also plays on a touchscreen.

Choose your side in settings — **Defender** (the King's guard, the classic
role) or **Attacker** (the besieging host).

## Computer difficulty

Five Norse tiers — **Thrall · Warrior · Jarl · Berserker · Konungr** — an
alpha-beta search with an asymmetric evaluation (the host wants the King boxed
and far from the corners; the defenders want open lanes and a clear run),
capped by a per-move time budget and run on a background thread.

## Speed mode & settings (double-tap)

Optional per-side clock (**10:00 / 5:00 / 3:00 / 1:00**). Plus difficulty,
side, show legal moves, sound, swipe sensitivity, flips, safe tap, particles,
frame cap, new game, undo, resign, reset stats, reset settings (at the bottom).
The game auto-saves after every move and resumes on next launch.

## Build & install

```bash
cd ~/Projects/X3Hnefatafl
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A built `X3Hnefatafl.apk` also ships in this repo's root for a quick sideload.

Toolchain: gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 · compileSdk 35 /
minSdk 29.

## X3 specifics honored

- Black is transparency: the board and its glowing King float as light on the world
- No `ar_mode` meta-data (it would halve the display to one lens)
- Temple click read as a KEY event; swipes classified on finger-up; one gesture
  = one step everywhere
- `cyttsp6` (left volume pad) filtered out by device *name*
- RayNeo hardware detected by manufacturer/brand/product, not `Build.MODEL`
  (the X3 Pro reports `ARGF20`); SBS auto-defaults on
- Dwell-to-commit; AI on a background thread; the sleep button auto-pauses into
  settings; the game persists after every move
