# Widescreen HUD layout

Wrecked Metal mode only (Play Fidelity keeps the N64's HUD). The race HUD is drawn by the
game in a 4:3 area in the middle of the screen; on a wide screen the port moves whole HUD
groups toward the screen's edges (`port/game/wm_mod.c` `WM_HudOffset`, applied by
`port/fast3d/gfx_pc.cpp` as a shift of the 4:3 area for that group's draws). One player
only; split screen keeps the N64's places.

![template](images/hud_template.png)

## Coordinates

Positions are the **top-left corner of each group in pixels of a 1280x720 (16:9) screen**:
3 pixels per N64 pixel, the N64's 4:3 area from x 160 to x 1120. On a wider phone the left
groups keep their distance from the left edge and the right groups from the right edge,
like the touch buttons do; `time` keeps to the centre.

Set them in `Data/wm_tuning.ini`:

```ini
[hud]
layout = 1                  ; 0 = the N64's HUD
start_lights = 0.40         ; size of the red / yellow / green start lights (1.00 = the N64's)
score = 12 12 1.00          ; left edge    (240x78 at 1.00)
time = 577 15 1.00          ; centre       (144x90)
cars_left = 928 12 1.00     ; right edge   (240x78)
map = 384 58 1.00           ; left edge    (96x153)
damage = 958 92 1.00        ; right edge   damage gauge (108x216)
pedals = 457 660 1.00       ; centre       T/B bars, side by side (366x36)
```

Each line is `x y size`: the group's top-left on the 1280x720 template and its size
(1.00 = the N64's; the group grows from its top-left). -1 -1 = the N64's place.

The file is read when a race starts, so changes apply from the next race.

## Groups and their elements (Ui_Draw ids, `include/ids.h`)

| Group | Elements | N64 top-left (N64 px) |
| --- | --- | --- |
| score | SCREEN_BOX1 (168), texts 436 444 413 518 | 21, 21 |
| time | SCREEN_BOX3 (170), texts 434 512 | 139, 22 |
| cars_left | SCREEN_BOX2 (169), texts 504 424 | 219, 21 |
| map | RADAR_SP (14), CAR_RADAR_* (15-29) | 32, 160 |
| damage | HEALTH_* / WIRE_* (40-55), speed text 522 | 258, 150 |
| pedals | Wrecked Metal T/B bars (LED segments, T fills left to right, B right to left) | 99, 193 |
| start lights (scaled) | READY/SET/GO (260-262), halos (263-265), orange light + halo (161, 162) | 111, 30 |

## Touch controls (Wrecked Metal mode)

Icons from Tabler Icons (MIT, `LICENSES/tabler-icons.txt`): A gas, B brake, R handbrake (round,
the size of C-Up), C-Up camera, Start a small pause button in the top right corner (centre
30 dp from the top and right edges, radius 20 dp). No L (it only hides the HUD) and no Z (look
back: the game reads it in `func_80024690`, `src/auto/25290.c`, as byte 2 of the player's
input at +0x18, races only; kept for a rear-view mirror later). The arrows' up and down show
only in menus and the pause (`Port_TouchDriving`). Play Fidelity keeps the N64 layout.

## Touch buttons on the template (arrows layout, S23 at 720 lines, before the icons)

From `TouchControlsView` (dp offsets from the edges, x1.85 px/dp): L 51,53-207,131;
START 567,37-712,110; arrows < 48,472-210,634, > 260,472-423,634, ^ 186,329-283,425,
v 194,623-275,705; C^ 880,301-976,398; R 1067,280-1233,363; A 1028,468-1198,638;
B 861,542-995,675; Z 684,597-839,675. With the analog stick instead of arrows, the stick
appears wherever the left 45% of the screen below the top quarter is touched.
