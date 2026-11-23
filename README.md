# DataDial for Wear OS

Watch face for the Google Pixel Watch 5 (Wear OS 7), ported from the Connect IQ
[DataDial](https://github.com/ls6777/garmin-datadial) for the Garmin fēnix 9. Eight stats
around the dial, with the time shown either as analog hands or as a large digital time in
the middle (analog by default), and the same stats as the Garmin version.

Steps, calories and floors come from the **Google Health** app on the watch, so they show the
same numbers as the Google Health app on the phone. A small companion app, **DataDial Stats**,
supplies the elevation, which Wear OS doesn't give watch faces.

It is built with the [Watch Face Format](https://developer.android.com/training/wearables/wff)
(version 4), which Google requires for watch faces. It runs on any round Wear OS 6 or later
watch.

![DataDial on the Wear OS 7 emulator, analog and digital](docs/screenshot.png)

*Test build (`./build.sh test`) on the Wear OS 7 emulator: analog with the default blue
(left) and digital with the green accent (right), at the test build's fixed time of 8:19:11,
which keeps the hands clear of the stats. The test build shows the same fixed values as the
Garmin test build: heart rate 160, 100% battery while charging, sunny and 110 °F (hot; high
112 °F, low 95 °F) and 13,000 ft, plus UV index 11 (extreme). Steps, calories and floors now
come from Google Health, so the test build shows their real values; the screenshot is from
before, with fixed ones (25,000 steps, 3,500 calories, 110 floors).*

## The face

Same layout as the Garmin version: a dot for every minute around the edge, eight stats
divided by colored lines, each with an icon toward the center and a white value toward the
edge, see-through steel hands with a red seconds hand, or a digital time with seconds below.
The battery moves into the 12 o'clock section, under the date.

| Section | Garmin DataDial | Wear OS DataDial |
|---|---|---|
| 12 o'clock | Date, weekday | Same, plus the battery % under them |
| 1:30 | Heart rate | Same |
| 3 o'clock | Battery %, days left | **UV index** from the weather; the battery % is at 12 o'clock |
| 4:30 | Steps, with goal gauge | Same, from Google Health (its goal) |
| 6 o'clock | Calories | Same, from Google Health |
| 7:30 | Floors, with goal gauge | Same, from Google Health (its goal) |
| 9 o'clock | Elevation | Same, from DataDial Stats (barometer) |
| 10:30 | Temperature (watch sensor) | **Weather**: condition icon, current temperature, today's high/low |

### Steps, calories and floors

A Watch Face Format face is XML with no code. Of the health stats it can only read steps and
heart rate itself (besides the battery and the weather), and its steps are the watch's own
count, which starts again from 0 when the watch restarts and doesn't match the Google Health
app. So steps, calories and floors are
[complications](https://developer.android.com/training/wearables/wff/complications) from the
Google Health app on the watch (its package is still `com.fitbit.FitbitMobile`): today's
totals as Google Health counts them, with its step and floor goals for the gauges. Calories
include resting calories, as on Garmin. Google Health's icons have their own colors, which
the face can't turn into the accent color, so these sections show DataDial's own icons
instead, and they can't be switched to other sources.

They come from Google Health only, with no fallback: if Google Health is missing or disabled
on the watch, the three sections show `--`. (It can be disabled by accident in the watch's
Settings → Apps; it then also stops recording, and its tiles disappear.)

### DataDial Stats

**Elevation** comes from the barometer, in feet where the watch's region uses US units and
meters elsewhere. A Watch Face Format face can't read it (checked against Watch Face Format
version 5), so DataDial Stats reads it and hands it to the face as a complication. The
barometer only knows air pressure, which changes with the weather, so DataDial Stats
calibrates it to the altitude of a location fix: each time you open the app (if it may use
the location), and whenever it finds a recent fix while updating. Uncalibrated, it assumes
standard sea-level pressure, which can be off by a few hundred feet.

**Battery time left** is a second data source, for the
[Night Dial](https://github.com/ls6777/NightDial) watch face's battery gauge: the hours of
battery left at the rate it has been draining ("23h"). Each time DataDial Stats wakes (see
below) it records the battery level; once the battery has dropped 2% since it was last
unplugged, hours left = level ÷ drain rate, up to the last 12 hours of samples. Until then it
uses the last rate it measured. While charging, or before it has measured any rate, it sends
no data. DataDial itself doesn't use it.

**How often it updates.** DataDial Stats doesn't run all the time: Android starts it when it
has something to do, and it reads a value and stops. The system is supposed to ask for the
elevation every 5 minutes, but it mostly doesn't, and waking the screen doesn't trigger a
reading. So DataDial Stats also reads it whenever
[Health Services](https://developer.android.com/health-and-fitness/guides/health-services)
wakes the app with new activity data (daily calories, which go up every minute even at rest).
That's about once a minute on the emulator, less often on the Pixel Watch; Google doesn't
document the rate.
`./build.sh statslog` shows each request and each Health Services delivery with its time.

After installing, open DataDial Stats once and tap **Allow access**: it needs physical
activity (for the Health Services wake-ups) and, optionally, location (to calibrate the
elevation). The app's screen shows the elevation and when it was last calibrated.

The 9 o'clock section can show another stat: long-press the face, tap the edit button, and
pick any other data source an app on the watch provides. It's drawn in DataDial's style: the
source's icon in the accent color, its value in white, and the goal gauge along the edge for
sources that report progress toward a target (ranged or goal values). A source with no data
at the moment shows nothing; with no source chosen, the section shows `--`. Without DataDial
Stats installed, it shows a world clock.

- **Battery:** under the date and weekday, the percentage above an outline filled to the
  charge level; below 20% both turn red. A green bolt appears over the outline while charging.
- **UV index:** the current UV index from the watch's weather, in WHO risk colors from high
  up: orange from 6, red from 8, purple from 11 (white below 6). Without weather data it
  shows `--`.
- **Goals:** the steps and floors gauges (and the gauge of any goal or ranged complication)
  fill from their lower end toward the goal: red below 30%, yellow below 90%, green from 90%. Once the goal
  is met, the value turns green too.
- **Weather:** an icon for the current condition (sunny or clear, partly cloudy, cloudy, fog,
  mist, light rain, rain, heavy rain, light snow, snow, heavy snow, sleet, thunderstorm,
  windy; the sun becomes a moon at night) and the current temperature in the watch's
  weather unit. Below 40 °F (4.4 °C) the temperature turns ice blue; above 90 °F (32.2 °C)
  it turns red. Under it, today's high and low in gray ("72°/58°"). The three are stacked
  in a column: icon, temperature, high/low. Without weather data it shows just `--` (the
  thermometer appears only if the weather reports an unknown condition). The weather comes
  from the watch (which gets the location from the phone), the same data as the UV index.
- **Always-on mode:** only the dimmed dot dial and the hands (or the digital time) are shown,
  no stats, lines or seconds. The Garmin version's 2-pixel burn-in shift isn't ported.

## Settings

Long-press the face and tap the edit button (or use the watch face settings in the Pixel Watch
app on the phone):

| Setting | What it changes | Default |
|---|---|---|
| Clock style | Analog (hands) or Digital (time in the middle) | Analog |
| Lines and icons color | Divider lines, icons and the battery outline | Blue |
| Heart rate icon color | Heart icon only | Red |
| Complication | The 9 o'clock stat | Elevation |

The colors are the same ten as on Garmin: Blue, Cyan, Green, Yellow, Orange, Red, Pink,
Purple, White and Gray. The digital time follows the watch's 12/24-hour setting.

## How it's built

Watch Face Format is declarative XML with no code. DataDial's layout is computed from the
screen radius, so `tools/generate.py` writes the XML from the same proportions as the Garmin
version, and renders the images (icons from `art/`, the steel hands, the bolt and the
weather icons) with Pillow. It also writes DataDial Stats' icons from the same `art/` files.
Edit the script, not the generated files.

DataDial Stats (`stats/`) is a separate Kotlin app, because a Watch Face Format bundle can't
contain code.

| File | Purpose |
|---|---|
| `tools/generate.py` | Writes `res/raw/watchface.xml` and the images in `res/drawable/` |
| `art/` | Icon SVGs, shared with the Garmin version |
| `build.sh` | Generate, build, install, test build, runtime log |
| `watchface/src/main/AndroidManifest.xml` | Package, Watch Face Format version 4, no code |
| `watchface/src/main/res/raw/watchface.xml` | The watch face (generated) |
| `watchface/src/main/res/xml/watch_face_info.xml` | Preview image and picker settings |
| `watchface/src/main/res/values/strings.xml` | Names shown in the editor |
| `watchface/src/main/res/drawable/preview.png` | Picker and store preview (an emulator capture of the test build) |
| `stats/src/main/kotlin/.../Sources.kt` | DataDial Stats' elevation and battery time data sources |
| `stats/src/main/kotlin/.../Battery.kt` | Battery sampling and the time-left estimate |
| `stats/src/main/kotlin/.../Health.kt` | Health Services wake-ups that refresh the elevation |
| `stats/src/main/kotlin/.../Elevation.kt` | Barometric elevation and its calibration |
| `stats/src/main/kotlin/.../Sensors.kt` | One-shot sensor readings |
| `stats/src/main/kotlin/.../MainActivity.kt` | Permissions and status screen |
| `stats/src/debug/` | Debug build only: switch for the fixed test values |

## Build

Needs Android Studio (for its JDK; the Android SDK with API 36 platform and build tools), and
Python 3 with Pillow.

```sh
./build.sh          # generate and build the debug APKs (watch face and DataDial Stats)
./build.sh run      # also install both on the watch or emulator and make DataDial the active face
./build.sh test     # the same with fixed worst-case values, to check the layout
./build.sh release  # release bundles (.aab) for Google Play (set up release signing first)
./build.sh log      # the watch face runtime's log: XML and expression errors show up here
./build.sh statslog # DataDial Stats' log: when each stat is read, and its value
```

`./build.sh test` writes the normal XML back when it's done, so the test values never stay in
the source, and switches on DataDial Stats' test values (a switch only the debug build has).
Run `./build.sh run` afterwards to put the live face and live stats back on the watch.

DataDial Stats is installed before the face: the face only picks up its default data sources
if they're installed when it is.

With several devices connected (say the watch and the emulator), set `ANDROID_SERIAL` to the
one to install on, e.g. `ANDROID_SERIAL=192.168.4.46:38067 ./build.sh run`.

The project also opens in Android Studio, which checks the watch face XML as you edit it.

## Run on the emulator or the watch

- **Emulator:** create a Wear OS 7 large round device (Device Manager in Android Studio, or
  `avdmanager create avd -k "system-images;android-37.0;android-wear-signed;x86_64" -d wearos_large_round`),
  start it, then `./build.sh run`.
- **Pixel Watch 5:** turn on Developer options (Settings → System → About → Versions, tap
  Build number seven times), then Developer options → Wireless debugging → Pair new device.
  On the PC: `adb pair <ip>:<port>` with the shown code, then `adb connect <ip>:<port>` with
  the port shown on the Wireless debugging screen, and `./build.sh run`.

A watch face changed with `./build.sh run` keeps its settings and complications. To get the
defaults again, uninstall it first (`adb uninstall io.github.ls6777.datadial`). Do the same
after changing a slot's default source or adding or removing a slot: the runtime keeps the
old choices and matches them by slot order. On the
emulator, you can grant DataDial Stats' permissions without the app:
`adb shell pm grant io.github.ls6777.datadial.stats android.permission.ACTIVITY_RECOGNITION`.
