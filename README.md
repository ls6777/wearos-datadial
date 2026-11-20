# DataDial for Wear OS

Watch face for the Google Pixel Watch 5 (Wear OS 7), ported from the Connect IQ
[DataDial](https://github.com/ls6777/garmin-datadial) for the Garmin fēnix 9. Eight stats
around the dial, with the time shown either as analog hands or as a large digital time in
the middle (analog by default).

It is built with the [Watch Face Format](https://developer.android.com/training/wearables/wff)
(version 4), which Google requires for watch faces. It runs on any round Wear OS 6 or later
watch.

![DataDial on the Wear OS 7 emulator, analog and digital](docs/screenshot.png)

*Test build (`./build.sh test`) on the Wear OS 7 emulator: analog with the default blue
(left) and digital with the green accent (right). The test build shows fixed values: 25,000 steps (60% of the goal),
heart rate 160, 100% battery while charging, 110 °F (hot). The emulator has no location or
weather, so the 6 o'clock stat (sunrise/sunset) shows `--`.*

## The face

Same layout as the Garmin version: a dot for every minute around the edge, eight stats
divided by colored lines, each with an icon toward the center and a white value toward the
edge, see-through steel hands with a red seconds hand, or a digital time with seconds below.

| Section | Garmin DataDial | Wear OS DataDial |
|---|---|---|
| 12 o'clock | Date, weekday | Same |
| 1:30 | Heart rate | Same |
| 3 o'clock | Battery %, days left | Battery % only: watch faces can't read the battery estimate |
| 4:30 | Steps, with goal gauge | Same |
| 6 o'clock | Calories | **Complication**, sunrise/sunset by default |
| 7:30 | Floors, with goal gauge | **Complication**, unread notifications by default |
| 9 o'clock | Elevation | **Complication**, world clock by default |
| 10:30 | Temperature (watch sensor) | **Weather** temperature, in the watch's unit |

Watch faces on Wear OS can't read calories, floors or elevation, so those three sections are
[complication](https://developer.android.com/training/wearables/wff/complications) slots:
long-press the face, tap the edit button, and pick any data source an app on the watch
provides (for example from Fitbit or a fitness app). They're drawn in DataDial's style: the
source's icon in the accent color, its value in white, and the goal gauge along the edge
for sources that report progress toward a target (ranged or goal values). A source with no
data at the moment shows nothing; with no source chosen, the section shows `--`.

- **Battery:** the outline fills to the charge level; below 20% the outline and percentage
  turn red. A green bolt appears while charging.
- **Goals:** the steps gauge (and the gauge of a goal or ranged complication) fills from its
  lower end toward the goal: red below 30%, yellow below 90%, green from 90%. Once the goal
  is met, the value turns green too.
- **Temperature:** the weather temperature from the phone. Below 40 °F (4.4 °C) it turns ice
  blue with a snowflake above it; above 90 °F (32.2 °C) it turns red with heat waves. Without
  weather data it shows `--`.
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
| Complications | The 6, 7:30 and 9 o'clock stats | See above |

The colors are the same ten as on Garmin: Blue, Cyan, Green, Yellow, Orange, Red, Pink,
Purple, White and Gray. The digital time follows the watch's 12/24-hour setting.

## How it's built

Watch Face Format is declarative XML with no code. DataDial's layout is computed from the
screen radius, so `tools/generate.py` writes the XML from the same proportions as the Garmin
version, and renders the images (icons from `art/`, the steel hands, the bolt, snowflake and
heat waves) with Pillow. Edit the script, not the generated files.

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

## Build

Needs Android Studio (for its JDK; the Android SDK with API 36 platform and build tools), and
Python 3 with Pillow.

```sh
./build.sh          # generate and build the debug APK
./build.sh run      # also install it on the watch or emulator and make it the active face
./build.sh test     # the same with fixed worst-case values, to check the layout
./build.sh release  # release bundle (.aab) for Google Play (set up release signing first)
./build.sh log      # the watch face runtime's log: XML and expression errors show up here
```

`./build.sh test` writes the normal XML back when it's done, so the test values never stay in
the source. Run `./build.sh run` afterwards to put the live face back on the watch.

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
defaults again, uninstall it first (`adb uninstall io.github.ls6777.datadial`).
