# Weather (Wrecked Metal mode)

Random weather on the race tracks: rain, thunderstorms and snow. It only runs in
**PLAY WRECKED METAL**; **Play Fidelity** keeps the N64's weather (snow on the glacier
track, nothing else). Code: `port/game/wm_weather.c`, `port/src/wm_thunder.c` and
`WM_WeatherRoll` / `WM_WeatherPick` in `port/src/wm_tuning.c` (engine fork).

## Track table

Tracks are listed by the game's track id (`D_800958B4`) and by their place in the
race menu, counting from 0 (0 = Seascape Sprint). Ids 0-3 are the arenas: no weather
there, nor in multiplayer (game type 3, where the game draws no weather at all).

| Menu # | Track id | Looks like | Weather (weights: clear / rain / storm / snow) |
| --- | --- | --- | --- |
| 0 | 5 | Seascape Sprint (coast) | random: 40 / 30 / 30 / 0 |
| 1 | 4 | city, daytime | random: 40 / 30 / 30 / 0 |
| 2 | 7 | city, dusk | random: 40 / 30 / 30 / 0 |
| 3 | 8 | figure 8 | random: 40 / 30 / 30 / 0 |
| 4 | 11 | city at night | always snow |
| 5 | 6 | dirt canyon | always rain |
| 6 | 10 | glacier (the N64's snow track) | always snow |
| 7 | 9 | desert | never rain (always clear) |

> The request numbered the tracks 0, 1, 3, 4, 5, 6, 7, 8 and skipped 2. There are 8
> race tracks, and with "7 = snow" and "8 = desert" landing on the glacier and the
> desert only if the count skipped a number, the table above reads 3-8 as menu
> places 2-7. If that reading is wrong, change the `track_N` lines (below): no
> rebuild needed.

The race menu order comes from the cups table (`D_80095CA8`): 5 4 0 7 8 1 11 6 2 10 9 3,
arenas included; without the arenas, 5 4 7 8 11 6 10 9.

## Settings: `Data/wm_tuning.ini`, `[weather]`

```ini
[weather]
random_weather = 1      ; 0 = the N64's weather (snow on the glacier only)
layers = 3              ; 1-3 particle depths (parallax): 1 = the N64's look
density = 1.00          ; particle count multiplier (0.1-2.0)
track_5 = 40 30 30 0    ; weights for clear rain storm snow, by track id (4-11)
...
```

Edits apply from the next race. Debug: `DD64_WEATHER=clear|rain|storm|snow` forces a
kind on every race track; `DD64_STRIKE=n` makes storms strike every n frames. The log
says what each race got: `weather: track 5 gametype 1 -> storm`.

## How it looks

- **Snow:** three layers of flakes at different scales and distances (far, mid, near),
  each fixed in the world, so near flakes are big and cross the screen fast and far
  ones are small and slow. A soft round 32x32 flake replaces the N64's 8x8 cross, and
  each flake sways on its own slow sine instead of the N64's random jitter.
- **Rain:** the N64's streak texture (never used by the retail game) in the same three
  layers, with one wind per race.
- **Storm:** heavier rain, a lightning flash over the 3D view every 4-14 s (the HUD
  stays readable; far strikes are dimmer) and a thunder 0.1-1.8 s later, synthesized
  in code (no samples): a crack and a heavy roll when near, a low rumble when far.

## Notes

- The game's own lightning (`Weather_UpdateLightning`) is dead code: no track sets the
  flag, and the sky block it flashes is disabled on every race track. That is why the
  storm draws its own flash.
- The scene is set up twice as a race loads (two `Weather_Init` calls), so the roll is
  kept for 15 s and the same race gets the same weather.
- Textures handed to Fast3D must live in game RAM (`seg_addr` treats host addresses
  below 0x10000000 as segments), so the flake and the flash texture are allocated from
  the game heap and written as u32 (host order for the shadow, `port/src/shadow.c`).
