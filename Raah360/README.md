# Raah360 — navigation beyond the blue dot (LAST50)

Android app (Kotlin, Jetpack Compose, ARCore) for the last 50–200 m of navigation:

**Locate → Route → Guide → Verify**

- **Visual Pin** says *what* the destination is (name, map node, exact point, facing, reference photo + visual signature).
- **Walkable graph + A\*** says *how* to get there. No edge = no way through (walls, the atrium void, rooms).
- **ARCore + landmarks** say *where you are*: an Augmented-Image landmark aligns the fresh AR session to the venue map, ARCore tracks between landmarks, every landmark you pass corrects drift (two landmarks also fix the heading).
- **Guidance** never points at the pin: it points at a look-ahead point ~3 m further **along the route**, smoothed, then quantised into **8 stable sectors** with hysteresis. Text + optional **voice** come from the same state.
- **Verification** at the end combines map position, facing and the photo signature → *Exact arrival* or *Wrong spot — Room C2 is about 4 m to your left*.

## Open & run

1. Android Studio (Ladybug 2024.2 or newer) → **Open** → this `Raah360` folder → let Gradle sync.
2. Run on an ARCore-supported phone (Google Play Services for AR is installed automatically).
3. No AR phone? Use **Simulator** — same engine, voice and dial, drag yourself on the map.

Unit tests for the engine (`app/src/test`): routing around the atrium, noisy simulated walk (stability, voice, arrival), reroute, tracking loss, landmark alignment and drift correction, verification. Run with `./gradlew test`.

## What's pre-loaded

`app/src/main/assets/venues/default_venue.json` — Floor 1 of the atrium venue from the survey: the 23.84 / 9.52 / 23.46 / 11.33 m loop (origin = Room A corner, side A along +x), 22 corridor nodes, 17 places (Room A, The Scalers, A3, A4, F, G, Fire exit, Main staircase, Washrooms, C1–C4, Escalator, Green booths, Bar table, B rooms) and 8 planned marker spots (M1–M8). Room widths are still estimates — correct them on site with the Mapper.

## Demo day checklist

1. **Print markers**: Landmarks → *Share printable markers (PDF)*. Print at 100 % (18 cm wide). Tape each at the spot written on its page, chest height.
2. **Measure them** (≈5 min): Mapper → scan M1 (or stand at the Room A corner facing towards Room A4 and tap *Set origin here*) → walk the loop, and when each marker shows up tap **Save pose**.
3. **Fix the map** where the survey was off: stand at a door, *Move nearest here*; add junctions with *Drop node* / *Auto path*, close loops with *Join nearest*.
4. **Reference photos**: stand in front of each place you'll demo, *Photo for nearest place* (or *Pin here + photo* for new ones).
5. **Share**: Home ⋮ → *Share this map* → import on the demo phone. Share a single place with the share icon (deep link `raah360://pin/<venue>/<pin>`).
6. **Demo**: pick a destination across the atrium (e.g. Room F → Bar table): the naive arrow points across the void, the route goes round. Turn on *developer info* (Home ⋮) to show live pose, look-ahead, landmark corrections and match score.

## Code map

| Package | What |
|---|---|
| `core/` | Pure Kotlin, unit-tested: geometry & transforms, venue model + JSON, A\* planner & route maneuvers, guidance engine (look-ahead, smoothing, 8 sectors, off-route), voice prompter, localizer (landmark alignment + drift correction), image signature + arrival verifier, mapper edit ops |
| `ar/` | ARCore session, camera background renderer, Augmented Image landmark DB, YUV → RGB frame capture |
| `nav/` | Navigator (AR/sim → core → UI state), MapperController, TTS + haptics |
| `ui/` | Compose screens: Home, Place, Navigate (camera), Simulator, Mapper, Landmarks; 8-sector dial; map canvas |

Not in scope (by design): dynamic obstacles, cloud, LLMs. Multi-floor routing is supported by the engine (stairs/lift/escalator edges, "take the stairs, then scan a landmark"), but only Floor 1 is surveyed.
