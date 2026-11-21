#!/usr/bin/env python3
"""Generate the DataDial watch face: the Watch Face Format XML and its images.

DataDial's layout is computed from the screen radius, like the Connect IQ
version, so the XML is generated rather than written by hand. Run this after
changing anything below, then build with Gradle (build.sh does both).

Writes:
  watchface/src/main/res/raw/watchface.xml   the watch face
  watchface/src/main/res/drawable/*.png      icons, hands and symbols
  stats/src/main/res/drawable/*.xml          DataDial Stats' icons

With --test, the stats use fixed values (TEST_VALUES) instead of the
watch's data, to check the layout on the emulator. build.sh test does this
and then writes the normal XML back.

Needs Python 3 and Pillow.
"""

import math
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
ART = ROOT / "art"
RES = ROOT / "watchface" / "src" / "main" / "res"
DRAWABLE = RES / "drawable"

# --------------------------------------------------------------------- layout

#: Watch Face Format coordinate space. The face is scaled to the display.
SIZE = 450
#: Center and radius of the dial.
C = SIZE / 2
R = SIZE / 2

#: Stats around the dial, clockwise from 12 o'clock (45 degrees apart).
SLOT_DATE, SLOT_HEART, SLOT_UV, SLOT_STEPS = 0, 1, 2, 3
SLOT_BOTTOM, SLOT_LOWER_LEFT, SLOT_LEFT, SLOT_WEATHER = 4, 5, 6, 7
SLOT_COUNT = 8

#: Value text size per slot, as a fraction of the radius (as in DataDial).
#: The UV index (DataDial's battery cell) is one or two digits, so it uses
#: the large size of the other short values.
VALUE_SIZES = [0.165, 0.25, 0.25, 0.165, 0.21, 0.25, 0.165, 0.25]
LABEL_SIZE = 0.12  # weekday and complication titles
VALUE_RADIUS = 0.68  # value text center
ICON_RADIUS = 0.38  # icon center
#: The 12 o'clock cell, from the edge in: date, weekday, then the battery
#: (see BATTERY_TEXT_RADIUS). Distances from the center as fractions of the
#: radius.
DATE_RADIUS = 0.80
WEEKDAY_RADIUS = 0.675
ICON_SIZE = round(0.075 * SIZE)  # 7.5% of the screen, as in DataDial
STEPS_VALUE_SHIFT = 0.045  # steps value moves left to clear its gauge
LINE_START = 0.30  # divider lines, analog
DIGITAL_LINE_START = 0.40  # divider lines, digital (room for the time)
LINE_END = 0.86
LINE_WIDTH = 2
COMPLICATION_TEXT_WIDTH = 0.46  # complication value box, fraction of the radius

GAUGE_RADIUS = 0.855
GAUGE_PEN = 6
GAUGE_MARGIN = 5  # degrees left free at each end of a cell
GAUGE_YELLOW_FROM = 30  # percent of the goal
GAUGE_GREEN_FROM = 90

TIME_SIZE = 0.32
TIME_Y = -0.05
SECONDS_SIZE = 0.19
SECONDS_Y = 0.18

BATTERY_LOW = 20  # percent
#: The battery in the 12 o'clock cell, under the weekday: the percentage
#: above the icon. Percentage text size (DataDial's terms, see FONT_SCALE,
#: the same as the date), icon size as a fraction of ICON_SIZE, and the
#: distance of each from the center as a fraction of the radius.
BATTERY_TEXT_SIZE = 0.165
BATTERY_ICON_SCALE = 1.0
BATTERY_TEXT_RADIUS = 0.49
BATTERY_ICON_RADIUS = 0.37
#: Temperature thresholds, in each unit (DataDial's 40 F and 90 F). The
#: weather temperature is a whole number, so "below 4.44 C" is "below 5" and
#: "above 32.22 C" is "above 32".
COLD_BELOW_F, HOT_ABOVE_F = 40, 90
COLD_BELOW_C, HOT_ABOVE_C = 5, 32
#: Weather icons are drawn this much larger than the other icons (they are
#: more detailed).
WEATHER_ICON_SCALE = 1.1
#: The weather column in the 10:30 cell (icon, temperature, high/low), as
#: fractions of the radius from the center: its x, and the y of each line.
WEATHER_COLUMN_X = -0.44
WEATHER_COLUMN_Y = (-0.64, -0.46, -0.33)

#: Alpha of the dial and the time in always-on mode. White at this alpha is
#: about DataDial's sleep gray (0x9A9A9A).
AMBIENT_ALPHA = 154

# --------------------------------------------------------------------- colors

WHITE = "#FFFFFF"
RED = "#FF3B30"
MINUTE_DOT = "#B0B0B0"
CHARGING = "#3DDC57"
GOAL = "#3DDC57"
COLD = "#4FC3F7"
GAUGE_TRACK = "#333333"
GAUGE_YELLOW = "#FFD60A"
#: UV index colors from the WHO scale, highest first: (expression name,
#: lowest index, color). Lower values (low and moderate) are white.
UV_LEVELS = [("uvExtreme", 11, "#AF52DE"), ("uvVeryHigh", 8, "#FF3B30"),
             ("uvHigh", 6, "#FF9500")]
STEEL = "#D4D8DD"
SLEEP = "#9A9A9A"

#: Choices for the two color settings: (string id, color).
PALETTE = [
    ("color_blue", "#2EA3F2"),
    ("color_cyan", "#40E0FF"),
    ("color_green", "#3DDC57"),
    ("color_yellow", "#FFD60A"),
    ("color_orange", "#FF9500"),
    ("color_red", "#FF5A5A"),
    ("color_pink", "#FF2D92"),
    ("color_purple", "#AF52DE"),
    ("color_white", "#FFFFFF"),
    ("color_gray", "#9A9A9A"),
]
DEFAULT_CLOCK_STYLE = 0  # 0 analog, 1 digital
DEFAULT_ACCENT = 0  # blue
DEFAULT_HEART = 5  # red
ACCENT = "[CONFIGURATION.accent.0]"
HEART = "[CONFIGURATION.heart.0]"

#: The companion app (stats/) with the data source for the stat Watch Face
#: Format can't read: elevation.
STATS_PACKAGE = "io.github.ls6777.datadial.stats"
STATS_RES = ROOT / "stats" / "src" / "main" / "res"


def stats_source(name):
    """Component name of one of DataDial Stats' data sources."""
    return f"{STATS_PACKAGE}/{STATS_PACKAGE}.{name}"


#: Google Health's data sources on the watch (its package is still
#: com.fitbit.FitbitMobile): the same numbers as the Google Health app, with
#: its goals.
GOOGLE_HEALTH = "com.fitbit.FitbitMobile/com.google.android.wearable.fitbit.mainapp.complications.offloadable"
GOOGLE_HEALTH_STEPS = ("com.fitbit.FitbitMobile/"
                       "com.fitbit.complications.offloadable.steps.OffloadableStepsComplicationDataSourceService")

#: Complication slots for DataDial's steps, calories, floors and elevation:
#: slot, id, display name string, default data source and type, and the
#: system data source used when that one isn't installed. Steps, calories
#: and floors come from Google Health only (EMPTY: no other source, the slot
#: shows "--" without it). Elevation comes from DataDial Stats, or a world
#: clock without it (a source that always has data: one with nothing to
#: show, such as NEXT_EVENT with no calendar, sends NO_DATA, which Watch Face
#: Format draws as nothing at all). The steps slot comes last, so the older
#: slots keep their place (the runtime matches saved choices by order).
COMPLICATIONS = [
    (SLOT_BOTTOM, 1, "slot_bottom",
     f"{GOOGLE_HEALTH}.calories.OffloadableCaloriesComplicationDataSourceService", "SHORT_TEXT",
     "EMPTY"),
    (SLOT_LOWER_LEFT, 2, "slot_lower_left",
     f"{GOOGLE_HEALTH}.floors.OffloadableFloorsComplicationDataSourceService", "GOAL_PROGRESS",
     "EMPTY"),
    (SLOT_LEFT, 3, "slot_left", stats_source("ElevationSource"), "SHORT_TEXT", "WORLD_CLOCK"),
    (SLOT_STEPS, 4, "slot_steps", GOOGLE_HEALTH_STEPS, "GOAL_PROGRESS", "EMPTY"),
]

#: Slots that can't be changed in the editor, with DataDial's own icon for
#: each (the image drawn from art/). Google Health's icons have their own
#: colors, which tinting can't turn into the accent color, so these show
#: DataDial's white icons, tinted like the other stats.
FIXED_SLOTS = {SLOT_STEPS: "ic_steps", SLOT_BOTTOM: "ic_calories", SLOT_LOWER_LEFT: "ic_floors"}

FONT = {"family": "SYNC_TO_DEVICE", "weight": "BOLD", "width": "CONDENSED"}
#: Connect IQ font sizes are the full line height; Watch Face Format sizes
#: are the em size. This converts DataDial's sizes so the text looks the same.
FONT_SCALE = 0.72

# ------------------------------------------------------------------- geometry


def polar(degrees, distance):
    """Point at an angle (degrees clockwise from 12 o'clock) and distance from
    the center."""
    a = math.radians(degrees)
    return C + distance * math.sin(a), C - distance * math.cos(a)


def slot_angle(slot):
    """Angle of a slot's center line, in degrees clockwise from 12."""
    return slot * 360 / SLOT_COUNT


def value_position(slot):
    """Center of a slot's value text."""
    x, y = polar(slot_angle(slot), R * VALUE_RADIUS)
    if slot == SLOT_STEPS:
        x -= R * STEPS_VALUE_SHIFT
    return x, y


def icon_position(slot):
    """Center of a slot's icon."""
    return polar(slot_angle(slot), R * ICON_RADIUS)


def gauge_ends(slot):
    """A slot's goal gauge as (start, end) angles, start being the end the
    fill grows from: the lower end, or the left end for the bottom slot."""
    center = slot_angle(slot)
    half = 180 / SLOT_COUNT - GAUGE_MARGIN
    a, b = center - half, center + half
    low_a = polar(a, R)[1]
    low_b = polar(b, R)[1]
    if abs(low_a - low_b) < 1e-6:
        # Bottom slot: fill left to right, from the larger angle.
        return b, a
    return (a, b) if low_a > low_b else (b, a)


def fmt(v):
    """Short number for XML attributes."""
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


# ------------------------------------------------------------------ XML tools


def el(parent, tag, **attrs):
    """Add a child element. Attribute values may be numbers."""
    e = ET.SubElement(parent, tag)
    for k, v in attrs.items():
        e.set(k.rstrip("_"), fmt(v) if isinstance(v, float) else str(v))
    return e


def box(cx, cy, w, h):
    """Integer x, y, width, height of a box centered on (cx, cy)."""
    w, h = math.ceil(w), math.ceil(h)
    return {"x": round(cx - w / 2), "y": round(cy - h / 2), "width": w, "height": h}


def text(parent, cx, cy, width, size, template, params, color=WHITE,
         upper=False, auto_size=False, name=None, offset=(0, 0), align="CENTER"):
    """Centered single-line text. offset is subtracted from the position, for
    elements inside a complication slot. size is in DataDial's terms (see
    FONT_SCALE)."""
    size *= FONT_SCALE
    b = box(cx - offset[0], cy - offset[1], width, size * 1.3)
    part = el(parent, "PartText", **b)
    if name:
        part.set("name", name)
    t = el(part, "Text", align=align, ellipsis="TRUE")
    if auto_size:
        t.set("isAutoSize", "TRUE")
    font = el(t, "Font", size=float(round(size, 1)), color=color, **FONT)
    holder = el(font, "Upper") if upper else font
    tpl = el(holder, "Template")
    tpl.text = template
    for p in params:
        el(tpl, "Parameter", expression=p)
    return part


def image(parent, cx, cy, size, resource, tint=None, offset=(0, 0), name=None):
    """Square image centered on (cx, cy)."""
    part = el(parent, "PartImage", **box(cx - offset[0], cy - offset[1], size, size))
    if name:
        part.set("name", name)
    if tint:
        part.set("tintColor", tint)
    el(part, "Image", resource=resource)
    return part


def condition(parent, cases, default=True):
    """A Condition with named expressions. cases is a list of (name,
    expression). Returns the Compare elements, plus the Default element
    if default is true."""
    cond = el(parent, "Condition")
    exprs = el(cond, "Expressions")
    compares = []
    for name, expression in cases:
        e = el(exprs, "Expression", name=name)
        e.text = expression
    for name, _ in cases:
        compares.append(el(cond, "Compare", expression=name))
    if default:
        compares.append(el(cond, "Default"))
    return compares


def ambient_hide(parent):
    """Hide an element in always-on mode."""
    el(parent, "Variant", mode="AMBIENT", target="alpha", value="0")


def ambient_only(parent):
    """Show an element only in always-on mode (its alpha must be 0)."""
    el(parent, "Variant", mode="AMBIENT", target="alpha", value="255")


# ---------------------------------------------------------------- the scene


def dial(scene):
    """A dot for every minute, larger at the hours and largest at the
    quarters. Dimmed in always-on mode."""
    group = el(scene, "Group", name="dial", x=0, y=0, width=SIZE, height=SIZE)
    el(group, "Variant", mode="AMBIENT", target="alpha", value=AMBIENT_ALPHA)
    draw = el(group, "PartDraw", x=0, y=0, width=SIZE, height=SIZE)
    for i in range(60):
        x, y = polar(i * 6, R - 7)
        r = 5 if i % 15 == 0 else (4 if i % 5 == 0 else 2)
        e = el(draw, "Ellipse", x=x - r, y=y - r, width=2.0 * r, height=2.0 * r)
        el(e, "Fill", color=WHITE if i % 5 == 0 else MINUTE_DOT)


def divider_lines(parent, start):
    """The eight divider lines between the stats. Hidden in always-on mode,
    with the stats."""
    draw = el(parent, "PartDraw", x=0, y=0, width=SIZE, height=SIZE, name="lines")
    ambient_hide(draw)
    for i in range(SLOT_COUNT):
        a = slot_angle(i) + 180 / SLOT_COUNT
        x0, y0 = polar(a, R * start)
        x1, y1 = polar(a, R * LINE_END)
        line = el(draw, "Line", startX=x0, startY=y0, endX=x1, endY=y1)
        el(line, "Stroke", color=ACCENT, thickness=LINE_WIDTH)


def gauge(parent, slot, progress, met, color_cases, offset=(0, 0)):
    """A slot's goal gauge: a dim track along the outer edge of the cell and
    a fill growing from its start end. progress is an expression from 0 to
    1. met is an expression that is true once the goal is reached (the track
    is then covered). color_cases are (name, expression, color) for the fill,
    first match wins."""
    start, end = gauge_ends(slot)
    span = end - start  # signed
    diameter = 2 * R * GAUGE_RADIUS
    ox, oy = offset

    def arc(draw, a, b):
        # The PartDraw is moved by the offset, so the arc is centered on
        # the dial in the PartDraw's own coordinates.
        lo, hi = min(a, b), max(a, b)
        return el(draw, "Arc", centerX=C, centerY=C, width=diameter,
                  height=diameter, startAngle=lo, endAngle=hi)

    track_case, = condition(parent, [(f"track{slot}", f"!({met})")], default=False)
    draw = el(track_case, "PartDraw", x=-ox, y=-oy, width=SIZE, height=SIZE)
    el(arc(draw, start, end), "Stroke", color=GAUGE_TRACK, thickness=GAUGE_PEN)

    # The last color is the Default case.
    compares = condition(parent, [(f"g{slot}{n}", e) for n, e, _ in color_cases[:-1]])
    for compare, (_, _, color) in zip(compares, color_cases):
        draw = el(compare, "PartDraw", x=-ox, y=-oy, width=SIZE, height=SIZE)
        a = arc(draw, start, start)
        el(a, "Stroke", color=color, thickness=GAUGE_PEN)
        # The fill grows from start toward end: the angle on the far side
        # moves. Arcs run clockwise from startAngle to endAngle.
        value = f"{fmt(start)} + {fmt(span)} * clamp({progress}, 0, 1)"
        el(a, "Transform", target="endAngle" if span > 0 else "startAngle", value=value)


def goal_colors(percent, met):
    """Fill colors for a goal gauge (see DataDial's drawGauge). The last one
    has no expression: it applies when none of the others do."""
    return [
        ("green", f"({met}) || {percent} >= {GAUGE_GREEN_FROM}", GOAL),
        ("yellow", f"{percent} >= {GAUGE_YELLOW_FROM}", GAUGE_YELLOW),
        ("red", None, RED),
    ]


def date_slot(parent):
    """The date and weekday at the top of the 12 o'clock cell; the battery
    is under them (battery)."""
    x, y = polar(slot_angle(SLOT_DATE), R * DATE_RADIUS)
    text(parent, x, y, 0.75 * R, VALUE_SIZES[SLOT_DATE] * R, "%s %s",
         ["[DAY]", "[MONTH_S]"], upper=True, name="date")
    x, y = polar(slot_angle(SLOT_DATE), R * WEEKDAY_RADIUS)
    text(parent, x, y, 0.5 * R, LABEL_SIZE * R, "%s", ["[DAY_OF_WEEK_S]"],
         upper=True, name="weekday")


def heart_slot(parent):
    x, y = icon_position(SLOT_HEART)
    image(parent, x, y, ICON_SIZE, "ic_heart", tint=HEART, name="heartIcon")
    x, y = value_position(SLOT_HEART)
    size = VALUE_SIZES[SLOT_HEART] * R
    has, none = condition(parent, [("hasHeartRate", "[HEART_RATE] > 0")])
    text(has, x, y, 0.6 * R, size, "%d", ["round([HEART_RATE])"])
    text(none, x, y, 0.6 * R, size, "--", [])


def battery(parent):
    """The battery in the 12 o'clock cell, under the date and weekday: the
    percentage above an outline filled to the charge level. Red below
    BATTERY_LOW, with a green bolt over the outline while charging."""
    name = "battery"
    group = el(parent, "Group", name=name, x=0, y=0, width=SIZE, height=SIZE)
    size = round(ICON_SIZE * BATTERY_ICON_SCALE)
    pen = max(size // 14, 2)
    body_w, body_h, tip_w = round(size * 0.80), round(size * 0.48), round(size * 0.08)
    text_size = BATTERY_TEXT_SIZE * R
    text_w = math.ceil(text_size * FONT_SCALE * 3.0)  # room for "100%"
    text_y = C - R * BATTERY_TEXT_RADIUS
    icon_y = C - R * BATTERY_ICON_RADIUS
    left = round(C - (body_w + tip_w) / 2)
    top = round(icon_y - body_h / 2)
    inset = pen + 2
    fill_w = body_w - 2 * inset

    low = f"{BATTERY_LOW} > [BATTERY_PERCENT]"
    low_case, normal = condition(group, [(name + "Low", low)])
    for case, color in ((low_case, RED), (normal, ACCENT)):
        draw = el(case, "PartDraw", x=left, y=top, width=body_w + tip_w, height=body_h)
        outline = el(draw, "RoundRectangle", x=pen / 2, y=pen / 2,
                     width=float(body_w - pen), height=float(body_h - pen),
                     cornerRadiusX=float(pen * 2), cornerRadiusY=float(pen * 2))
        el(outline, "Stroke", color=color, thickness=pen)
        tip = el(draw, "Rectangle", x=float(body_w), y=body_h / 3,
                 width=float(tip_w), height=body_h / 3)
        el(tip, "Fill", color=color)
        level = el(draw, "Rectangle", x=float(inset), y=float(inset),
                   width=float(fill_w), height=float(body_h - 2 * inset))
        el(level, "Fill", color=color)
        el(level, "Transform", target="width",
           value=f"clamp({fill_w} * [BATTERY_PERCENT] / 100, 0.1, {fill_w})")
        text(case, C, text_y, text_w, text_size, "%d%%", ["[BATTERY_PERCENT]"],
             color=RED if case is low_case else WHITE)

    charging, = condition(group, [(name + "Charging", "[BATTERY_CHARGING_STATUS]")],
                          default=False)
    image(charging, C, icon_y, size * 1.15, "ic_bolt")


def uv_slot(parent):
    """The weather's current UV index, colored by the WHO risk scale from
    high up; "--" without weather data."""
    x, y = icon_position(SLOT_UV)
    image(parent, x, y, ICON_SIZE, "ic_uv", tint=ACCENT, name="uvIcon")
    x, y = value_position(SLOT_UV)
    size = VALUE_SIZES[SLOT_UV] * R
    uv = "[WEATHER.UV_INDEX]"
    cases = [("uvNone", "![WEATHER.IS_AVAILABLE]")]
    cases += [(name, f"{uv} >= {level}") for name, level, _ in UV_LEVELS]
    none, *levels, normal = condition(parent, cases)
    text(none, x, y, 0.6 * R, size, "--", [])
    for case, (_, _, color) in zip(levels, UV_LEVELS):
        text(case, x, y, 0.6 * R, size, "%d", [uv], color=color)
    text(normal, x, y, 0.6 * R, size, "%d", [uv])


def weather_slot(parent):
    """The current weather: an icon for the condition (day and night
    versions where it matters) and the temperature in the watch's unit, ice
    blue below 40 F (4.4 C) and red above 90 F (32.2 C). Without weather
    data, just "--" (no icon). The icon, the temperature and today's
    high/low are stacked in a column (see WEATHER_COLUMN_X and WEATHER_COLUMN_Y)."""
    x = C + WEATHER_COLUMN_X * R
    icon_y, temp_y, range_y = (C + f * R for f in WEATHER_COLUMN_Y)
    y = icon_y
    size = ICON_SIZE * WEATHER_ICON_SCALE
    # No icon without weather data; the thermometer for an unknown condition.
    available, = condition(parent, [("wxIcon", "[WEATHER.IS_AVAILABLE]")], default=False)
    *matches, unknown = condition(available, [(n, e) for n, e, _ in WEATHER_ICONS])
    for case, (_, _, resource) in zip(matches, WEATHER_ICONS):
        image(case, x, y, size, resource, tint=ACCENT)
    image(unknown, x, y, ICON_SIZE, "ic_thermometer", tint=ACCENT)

    y = temp_y
    size = VALUE_SIZES[SLOT_WEATHER] * R
    t = "[WEATHER.TEMPERATURE]"
    fahrenheit = "[WEATHER.TEMPERATURE_UNIT] == 2"
    cold = (f"[WEATHER.IS_AVAILABLE] && ({fahrenheit} ? {COLD_BELOW_F} > {t} "
            f": {COLD_BELOW_C} > {t})")
    hot = (f"[WEATHER.IS_AVAILABLE] && ({fahrenheit} ? {t} > {HOT_ABOVE_F} "
           f": {t} > {HOT_ABOVE_C})")
    cold_case, hot_case, normal_case, none = condition(parent, [
        ("cold", cold), ("hot", hot), ("weather", "[WEATHER.IS_AVAILABLE]")])
    text(cold_case, x, y, 0.6 * R, size, "%d°", [t], color=COLD)
    text(hot_case, x, y, 0.6 * R, size, "%d°", [t], color=RED)
    text(normal_case, x, y, 0.6 * R, size, "%d°", [t])
    text(none, x, y, 0.6 * R, size, "--", [])

    # Today's high and low, smaller and gray, under the temperature.
    weather, = condition(parent, [("weatherRange", "[WEATHER.IS_AVAILABLE]")], default=False)
    text(weather, x, range_y, 0.5 * R, LABEL_SIZE * R, "%d°/%d°",
         ["[WEATHER.DAY_TEMPERATURE_HIGH]", "[WEATHER.DAY_TEMPERATURE_LOW]"],
         color=MINUTE_DOT)


def cell_bounds(slot):
    """Integer box around a slot's cell, from the inner end of the divider
    lines to the outside of the gauge."""
    xs, ys = [], []
    half = 180 / SLOT_COUNT
    for k in range(0, 21):
        a = slot_angle(slot) - half + k * 2 * half / 20
        for d in (LINE_START, GAUGE_RADIUS + GAUGE_PEN / R):
            x, y = polar(a, R * d)
            xs.append(x)
            ys.append(y)
    x0, y0 = math.floor(min(xs)), math.floor(min(ys))
    return x0, y0, math.ceil(max(xs)) - x0, math.ceil(max(ys)) - y0


def source_icon(comp, slot, x, y, offset):
    """A complication's own icon, tinted, or its title if it has no icon."""
    icon, title = condition(comp, [
        (f"icon{slot}", "[COMPLICATION.MONOCHROMATIC_IMAGE] != null"),
        (f"title{slot}", "[COMPLICATION.TITLE] != null")], default=False)
    image(icon, x, y, ICON_SIZE, "[COMPLICATION.MONOCHROMATIC_IMAGE]", tint=ACCENT, offset=offset)
    text(title, x, y, 0.42 * R, LABEL_SIZE * R, "%s", ["[COMPLICATION.TITLE]"],
         color=ACCENT, auto_size=True, offset=offset)


def complication_slot(scene, slot, slot_id, display_name, source, source_type,
                      system_source):
    """A stat from a complication data source, drawn like DataDial's own:
    the source's icon (tinted) toward the center, or its title if it has no
    icon; its text toward the edge; and a gauge for ranged and goal values.
    A slot in FIXED_SLOTS can't be changed and shows DataDial's own icon."""
    x0, y0, w, h = cell_bounds(slot)
    offset = (x0, y0)
    own_icon = FIXED_SLOTS.get(slot)
    cs = el(scene, "ComplicationSlot", x=x0, y=y0, width=w, height=h,
            slotId=slot_id, displayName=display_name,
            supportedTypes="SHORT_TEXT RANGED_VALUE GOAL_PROGRESS EMPTY",
            isCustomizable="FALSE" if own_icon else "TRUE")
    el(cs, "DefaultProviderPolicy", defaultSystemProvider=system_source,
       defaultSystemProviderType="EMPTY" if system_source == "EMPTY" else "SHORT_TEXT",
       primaryProvider=source, primaryProviderType=source_type)
    el(cs, "BoundingBox", x=0, y=0, width=w, height=h)
    ambient_hide(cs)

    ix, iy = icon_position(slot)
    vx, vy = value_position(slot)
    size = VALUE_SIZES[slot] * R

    for ctype in ("SHORT_TEXT", "RANGED_VALUE", "GOAL_PROGRESS"):
        comp = el(cs, "Complication", type=ctype)
        if own_icon:
            image(comp, ix, iy, ICON_SIZE, own_icon, tint=ACCENT, offset=offset)
        else:
            source_icon(comp, slot, ix, iy, offset)

        met = None
        if ctype == "RANGED_VALUE":
            lo, hi, v = ("[COMPLICATION.RANGED_VALUE_MIN]", "[COMPLICATION.RANGED_VALUE_MAX]",
                         "[COMPLICATION.RANGED_VALUE_VALUE]")
            progress = f"({v} - {lo}) / ({hi} - {lo})"
            met = f"{v} >= {hi}"
            gauge(comp, slot, progress, met, goal_colors(f"(100 * {progress})", met),
                  offset=offset)
        elif ctype == "GOAL_PROGRESS":
            v, target = ("[COMPLICATION.GOAL_PROGRESS_VALUE]",
                         "[COMPLICATION.GOAL_PROGRESS_TARGET_VALUE]")
            progress = f"{v} / {target}"
            met = f"{v} >= {target}"
            gauge(comp, slot, progress, met, goal_colors(f"(100 * {progress})", met),
                  offset=offset)

        # Complication text can be long (a world clock's "11:50PM"), so it
        # shrinks to fit a box that stays inside the slot, which clips it.
        width = min(COMPLICATION_TEXT_WIDTH * R, 2 * min(vx - x0, x0 + w - vx) - 4)
        if met is None:
            text(comp, vx, vy, width, size, "%s", ["[COMPLICATION.TEXT]"],
                 auto_size=True, offset=offset)
        else:
            goal, normal = condition(comp, [(f"met{slot}{ctype}", met)])
            text(goal, vx, vy, width, size, "%s", ["[COMPLICATION.TEXT]"],
                 color=GOAL, auto_size=True, offset=offset)
            text(normal, vx, vy, width, size, "%s", ["[COMPLICATION.TEXT]"],
                 auto_size=True, offset=offset)

    # No source chosen, or the source has no data: "--", as DataDial shows
    # for a value that isn't available.
    empty = el(cs, "Complication", type="EMPTY")
    if own_icon:
        image(empty, ix, iy, ICON_SIZE, own_icon, tint=ACCENT, offset=offset)
    text(empty, vx, vy, 0.62 * R, size, "--", [], offset=offset)


def stats(scene):
    """The built-in stats. Hidden in always-on mode."""
    group = el(scene, "Group", name="stats", x=0, y=0, width=SIZE, height=SIZE)
    ambient_hide(group)
    date_slot(group)
    battery(group)
    heart_slot(group)
    uv_slot(group)
    weather_slot(group)


def hand_parts(clock, kind, resource, tint=None, angle=None):
    """One hand image, its pivot at the dial center (see render_hand). With
    an angle, a still image at that angle instead of a moving hand."""
    w, top, bottom = HAND_IMAGES[resource]
    box = dict(x=round(C - w / 2), y=round(C - top), width=w, height=top + bottom,
               pivotX=0.5, pivotY=top / (top + bottom))
    if angle is None:
        hand = el(clock, kind, resource=resource, **box)
    else:
        hand = el(clock, "PartImage", angle=float(angle), **box)
        el(hand, "Image", resource=resource)
    if tint:
        hand.set("tintColor", tint)
    return hand


def analog_clock(parent, frozen=None):
    """Steel skeleton hands and a red seconds hand while awake; flat gray
    hands in always-on mode. frozen is an (hour, minute, second) to draw
    the awake hands at instead of the time, for the test build."""
    if frozen is None:
        clock = el(parent, "AnalogClock", x=0, y=0, width=SIZE, height=SIZE, name="hands")
        angles = (None, None, None)
    else:
        clock = el(parent, "Group", x=0, y=0, width=SIZE, height=SIZE, name="hands")
        h, m, s = frozen
        angles = ((h % 12) * 30 + m / 2 + s / 120, m * 6 + s / 10, s * 6)
    ambient_hide(clock)
    for kind, resource, angle in zip(("HourHand", "MinuteHand", "SecondHand"),
                                     ("hand_hour", "hand_minute", "hand_second"), angles):
        hand_parts(clock, kind, resource, angle=angle)

    sleep = el(parent, "AnalogClock", x=0, y=0, width=SIZE, height=SIZE,
               name="sleepHands", alpha=0)
    ambient_only(sleep)
    hand_parts(sleep, "HourHand", "hand_hour_flat", tint=SLEEP)
    hand_parts(sleep, "MinuteHand", "hand_minute_flat", tint=SLEEP)

    # Open center hub: steel while awake, gray in always-on mode.
    r = R * 0.028
    side = 2 * math.ceil(r + 2)  # even, so the box centers on the dial
    for color, ambient in ((STEEL, False), (SLEEP, True)):
        b = box(C, C, side, side)
        draw = el(parent, "PartDraw", name="hubSleep" if ambient else "hub", **b)
        if ambient:
            draw.set("alpha", "0")
            ambient_only(draw)
        else:
            ambient_hide(draw)
        hx, hy = C - b["x"], C - b["y"]
        for radius, fill in ((r + 1, "#000000"), (r, color), (r / 3, "#000000")):
            e = el(draw, "Ellipse", x=hx - radius, y=hy - radius,
                   width=2 * radius, height=2 * radius)
            el(e, "Fill", color=fill)


def digital_clock(parent):
    """The time in the middle, following the 12/24-hour setting (24-hour
    with a leading zero, 12-hour without), with the seconds below it while
    awake."""
    group = el(parent, "Group", name="time", x=0, y=0, width=SIZE, height=SIZE)
    el(group, "Variant", mode="AMBIENT", target="alpha", value=AMBIENT_ALPHA)
    y = C + R * TIME_Y
    size = TIME_SIZE * R
    h24, h12 = condition(group, [("is24Hour", "[IS_24_HOUR_MODE]")])
    text(h24, C, y, 1.2 * R, size, "%s:%s", ["[HOUR_0_23_Z]", "[MINUTE_Z]"])
    text(h12, C, y, 1.2 * R, size, "%d:%s", ["[HOUR_1_12]", "[MINUTE_Z]"])
    seconds = text(parent, C, C + R * SECONDS_Y, 0.5 * R, SECONDS_SIZE * R, "%s",
                   ["[SECOND_Z]"], color=MINUTE_DOT, name="seconds")
    ambient_hide(seconds)


def clock(scene):
    """Divider lines and the time, by the clock style setting."""
    config = el(scene, "ListConfiguration", id="clockStyle")
    analog = el(config, "ListOption", id="0")
    divider_lines(analog, LINE_START)
    digital = el(config, "ListOption", id="1")
    divider_lines(digital, DIGITAL_LINE_START)
    digital_clock(digital)
    return analog


def user_configurations(face):
    configs = el(face, "UserConfigurations")
    style = el(configs, "ListConfiguration", id="clockStyle", displayName="clock_style",
               defaultValue=str(DEFAULT_CLOCK_STYLE))
    el(style, "ListOption", id="0", displayName="clock_analog")
    el(style, "ListOption", id="1", displayName="clock_digital")
    for cid, name, default in (("accent", "accent_color", DEFAULT_ACCENT),
                               ("heart", "heart_color", DEFAULT_HEART)):
        cc = el(configs, "ColorConfiguration", id=cid, displayName=name,
                defaultValue=str(default))
        for i, (label, color) in enumerate(PALETTE):
            el(cc, "ColorOption", id=str(i), displayName=label, colors=color)


def watch_face(test=False):
    face = ET.Element("WatchFace", width=str(SIZE), height=str(SIZE))
    el(face, "Metadata", key="CLOCK_TYPE", value="ANALOG")
    # The picker preview's time: the hands lie along divider lines, clear of
    # the stats (the same time as the test build).
    h, m, s = TEST_TIME
    el(face, "Metadata", key="PREVIEW_TIME", value=f"{h:02d}:{m:02d}:{s:02d}")
    user_configurations(face)
    scene = el(face, "Scene", backgroundColor="#000000")
    dial(scene)
    stats(scene)
    for slot, *rest in COMPLICATIONS:
        complication_slot(scene, slot, *rest)
    # The clock is drawn last, over the stats. Analog hands go in the
    # analog option of the clock style setting.
    analog = clock(scene)
    analog_clock(analog, frozen=TEST_TIME if test else None)
    return face


# ------------------------------------------------------------------- images

#: Pixels per watch face unit in the images, so they stay sharp on screens
#: larger than the 450-unit coordinate space.
SCALE = 4
#: Supersampling for smooth edges.
SS = 4


def downsample(img, size):
    return img.resize(size, Image.LANCZOS)


def svg_paths(svg_file):
    """Shapes in one of DataDial's icon SVGs as (fill_rule, [polygons]) in
    the 24x24 viewBox. Handles path (M L H V C S Z, absolute or relative),
    ellipse, circle and rect: what the icons use."""
    root = ET.parse(svg_file).getroot()
    shapes = []
    for e in root.iter():
        tag = e.tag.split("}")[-1]
        rule = e.get("fill-rule", "nonzero")
        if tag == "path":
            shapes.append((rule, parse_path(e.get("d"))))
        elif tag in ("ellipse", "circle"):
            cx, cy = float(e.get("cx")), float(e.get("cy"))
            rx = float(e.get("rx", e.get("r", 0)))
            ry = float(e.get("ry", e.get("r", 0)))
            pts = [(cx + rx * math.cos(t * math.pi / 32), cy + ry * math.sin(t * math.pi / 32))
                   for t in range(64)]
            shapes.append((rule, [pts]))
        elif tag == "rect":
            x, y = float(e.get("x")), float(e.get("y"))
            w, h = float(e.get("width")), float(e.get("height"))
            shapes.append((rule, [[(x, y), (x + w, y), (x + w, y + h), (x, y + h)]]))
    return shapes


def parse_path(d):
    """Flatten an SVG path into polygons (one per subpath)."""
    tokens = re.findall(r"[MLHVCSZmlhvcsz]|-?\d*\.?\d+(?:e-?\d+)?", d)
    polys, poly = [], []
    x = y = 0.0
    start = (0.0, 0.0)
    last_ctrl = None
    cmd = None
    i = 0

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    def cubic(p0, p1, p2, p3):
        for k in range(1, 17):
            t = k / 16
            mt = 1 - t
            poly.append((mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
                         mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t ** 3 * p3[1]))

    while i < len(tokens):
        if re.match(r"[A-Za-z]", tokens[i]):
            cmd = tokens[i]
            i += 1
        rel = cmd.islower()
        c = cmd.upper()
        if c == "Z":
            if poly:
                polys.append(poly)
            poly = []
            x, y = start
            last_ctrl = None
            continue
        if c == "M":
            if poly:
                polys.append(poly)
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            start = (x, y)
            poly = [(x, y)]
            cmd = "l" if rel else "L"  # implicit lineto after moveto
            last_ctrl = None
        elif c == "L":
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            poly.append((x, y))
            last_ctrl = None
        elif c == "H":
            nx = num()
            x = x + nx if rel else nx
            poly.append((x, y))
            last_ctrl = None
        elif c == "V":
            ny = num()
            y = y + ny if rel else ny
            poly.append((x, y))
            last_ctrl = None
        elif c in ("C", "S"):
            if c == "C":
                pts = [num() for _ in range(6)]
                if rel:
                    pts = [pts[k] + (x if k % 2 == 0 else y) for k in range(6)]
                p1 = (pts[0], pts[1])
                p2, p3 = (pts[2], pts[3]), (pts[4], pts[5])
            else:
                pts = [num() for _ in range(4)]
                if rel:
                    pts = [pts[k] + (x if k % 2 == 0 else y) for k in range(4)]
                p1 = (2 * x - last_ctrl[0], 2 * y - last_ctrl[1]) if last_ctrl else (x, y)
                p2, p3 = (pts[0], pts[1]), (pts[2], pts[3])
            cubic((x, y), p1, p2, p3)
            last_ctrl = p2
            x, y = p3
    if poly:
        polys.append(poly)
    return polys


def render_icon(svg_file, out, px=96):
    """Render a white icon mask from an SVG (tinted by the watch face)."""
    big = px * SS
    mask = Image.new("L", (big, big), 0)
    for rule, polys in svg_paths(svg_file):
        shape = Image.new("L", (big, big), 0)
        for poly in polys:
            layer = Image.new("L", (big, big), 0)
            ImageDraw.Draw(layer).polygon([(p[0] * big / 24, p[1] * big / 24) for p in poly], fill=255)
            if rule == "evenodd":
                # The layers are 0 or 255, so the difference is their XOR.
                shape = ImageChops.difference(shape, layer)
            else:
                shape.paste(255, mask=layer)
        mask.paste(255, mask=shape)
    save_mask(mask, out, px)


def save_mask(mask, out, px):
    img = Image.new("RGBA", mask.size, (255, 255, 255, 0))
    img.putalpha(mask)
    downsample(img, (px, px)).save(out)


def render_symbol(out, draw_fn, px=96):
    """Render a white symbol drawn by draw_fn(draw, size) in a square."""
    big = px * SS
    mask = Image.new("L", (big, big), 0)
    draw_fn(ImageDraw.Draw(mask), big)
    save_mask(mask, out, px)


# ------------------------------------------------------------ weather icons
# Drawn white on a mask (the face tints them), in a unit box: (0, 0) is the
# top left of the icon and (1, 1) its bottom right.


def _circle(d, s, cx, cy, r, fill=255):
    d.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=fill)


def _stroke(d, s, points, width, fill=255):
    """A line with round ends."""
    d.line([(x * s, y * s) for x, y in points], fill=fill, width=round(width * s), joint="curve")
    for x, y in (points[0], points[-1]):
        _circle(d, s, x, y, width / 2, fill)


def _cloud(d, s, dx=0.0, dy=0.0, k=1.0, grow=0.0, fill=255):
    """A cloud: three bumps on a flat base, centered on (0.5 + dx, 0.5 + dy)
    and scaled by k. grow makes it larger all round (for a dark gap)."""
    def p(x, y):
        return 0.5 + dx + (x - 0.5) * k, 0.5 + dy + (y - 0.5) * k

    for x, y, r in ((0.32, 0.58, 0.16), (0.52, 0.45, 0.22), (0.72, 0.59, 0.15)):
        cx, cy = p(x, y)
        _circle(d, s, cx, cy, r * k + grow, fill)
    (x0, y0), (x1, y1) = p(0.32, 0.55), p(0.72, 0.74)
    d.rectangle([x0 * s, y0 * s, x1 * s, (y1 + grow) * s], fill=fill)


def _sun(d, s, cx=0.5, cy=0.5, r=0.17, rays=(0.27, 0.42), pen=0.07):
    _circle(d, s, cx, cy, r)
    for k in range(8):
        a = k * math.pi / 4
        _stroke(d, s, [(cx + rays[0] * math.sin(a), cy - rays[0] * math.cos(a)),
                       (cx + rays[1] * math.sin(a), cy - rays[1] * math.cos(a))], pen)


def _moon(d, s, cx=0.5, cy=0.5, r=0.32):
    """A crescent opening to the upper right."""
    _circle(d, s, cx, cy, r)
    _circle(d, s, cx + 0.45 * r, cy - 0.35 * r, 0.82 * r, fill=0)


def _partly(d, s, behind):
    """The sun or moon (drawn by behind) with a cloud in front, a dark gap
    between them."""
    behind(d, s)
    _cloud(d, s, dx=0.08, dy=0.12, k=0.85, grow=0.06, fill=0)
    _cloud(d, s, dx=0.08, dy=0.12, k=0.85)


def _precipitation(d, s, drops=(), flakes=(), length=0.16, pen=0.065):
    """A raised cloud with rain drops (slanted strokes starting at the given
    x) and snow flakes (dots at the given points) under it."""
    _cloud(d, s, dy=-0.14, k=0.95)
    for x in drops:
        _stroke(d, s, [(x, 0.70), (x - length * 0.35, 0.70 + length)], pen)
    for x, y in flakes:
        _circle(d, s, x, y, 0.05)


def _bolt(d, s):
    _cloud(d, s, dy=-0.14, k=0.95)
    shape = [(0.55, 0.60), (0.36, 0.80), (0.48, 0.80), (0.41, 0.97), (0.64, 0.73),
             (0.52, 0.73), (0.60, 0.60)]
    d.polygon([(x * s, y * s) for x, y in shape], fill=255)


def _fog(d, s):
    for y, x0, x1 in ((0.30, 0.22, 0.82), (0.44, 0.14, 0.86), (0.58, 0.18, 0.78),
                      (0.72, 0.26, 0.86)):
        _stroke(d, s, [(x0, y), (x1, y)], 0.075)


def _mist(d, s):
    """Like fog, but broken lines."""
    for y, parts in ((0.36, ((0.16, 0.42), (0.54, 0.84))),
                     (0.52, ((0.16, 0.30), (0.42, 0.84))),
                     (0.68, ((0.16, 0.58), (0.70, 0.84)))):
        for x0, x1 in parts:
            _stroke(d, s, [(x0, y), (x1, y)], 0.07)


def _wind(d, s):
    """Three gusts with curled ends."""
    pen = 0.07
    w = round(pen * s)
    for y, x0, x1, r, up in ((0.32, 0.12, 0.60, 0.09, True), (0.52, 0.08, 0.76, 0.11, True),
                             (0.72, 0.16, 0.54, 0.09, False)):
        _stroke(d, s, [(x0, y), (x1, y)], pen)
        cy = y - r if up else y + r
        box = [(x1 - r) * s, (cy - r) * s, (x1 + r) * s, (cy + r) * s]
        # Angles clockwise from 3 o'clock: around the right side and on.
        if up:
            d.arc(box, 200, 90, fill=255, width=w)
        else:
            d.arc(box, 270, 160, fill=255, width=w)


#: Weather icon images: drawable name and drawing.
WEATHER_DRAWINGS = {
    "ic_wx_sunny": lambda d, s: _sun(d, s),
    "ic_wx_clear_night": lambda d, s: _moon(d, s),
    "ic_wx_partly_day": lambda d, s: _partly(
        d, s, lambda d, s: _sun(d, s, 0.34, 0.34, 0.12, (0.19, 0.29), 0.055)),
    "ic_wx_partly_night": lambda d, s: _partly(d, s, lambda d, s: _moon(d, s, 0.36, 0.34, 0.21)),
    "ic_wx_cloudy": lambda d, s: _cloud(d, s, dy=0.02, k=1.12),
    "ic_wx_fog": _fog,
    "ic_wx_mist": _mist,
    "ic_wx_light_rain": lambda d, s: _precipitation(d, s, drops=(0.42, 0.64)),
    "ic_wx_rain": lambda d, s: _precipitation(d, s, drops=(0.34, 0.53, 0.72)),
    "ic_wx_heavy_rain": lambda d, s: _precipitation(
        d, s, drops=(0.30, 0.45, 0.60, 0.75), length=0.22, pen=0.07),
    "ic_wx_light_snow": lambda d, s: _precipitation(d, s, flakes=((0.40, 0.80), (0.62, 0.80))),
    "ic_wx_snow": lambda d, s: _precipitation(
        d, s, flakes=((0.32, 0.78), (0.52, 0.88), (0.70, 0.78))),
    "ic_wx_heavy_snow": lambda d, s: _precipitation(
        d, s, flakes=((0.28, 0.76), (0.48, 0.76), (0.68, 0.76), (0.38, 0.90), (0.58, 0.90))),
    "ic_wx_sleet": lambda d, s: _precipitation(d, s, drops=(0.36, 0.70), flakes=((0.50, 0.84),)),
    "ic_wx_thunderstorm": _bolt,
    "ic_wx_windy": _wind,
}

#: Which icon the weather slot shows: expression name, expression and image,
#: first match wins (see WEATHER.CONDITION in the Watch Face Format data
#: sources). Unknown conditions show the thermometer.
_WX = "[WEATHER.CONDITION]"
WEATHER_ICONS = [
    ("wxSunnyDay", f"({_WX} == 1 || {_WX} == 8) && [WEATHER.IS_DAY]", "ic_wx_sunny"),
    ("wxClearNight", f"{_WX} == 1 || {_WX} == 8", "ic_wx_clear_night"),
    ("wxPartlyDay", f"{_WX} == 14 && [WEATHER.IS_DAY]", "ic_wx_partly_day"),
    ("wxPartlyNight", f"{_WX} == 14", "ic_wx_partly_night"),
    ("wxCloudy", f"{_WX} == 2", "ic_wx_cloudy"),
    ("wxFog", f"{_WX} == 3", "ic_wx_fog"),
    ("wxMist", f"{_WX} == 13", "ic_wx_mist"),
    ("wxLightRain", f"{_WX} == 12", "ic_wx_light_rain"),
    ("wxRain", f"{_WX} == 6", "ic_wx_rain"),
    ("wxHeavyRain", f"{_WX} == 4", "ic_wx_heavy_rain"),
    ("wxLightSnow", f"{_WX} == 11", "ic_wx_light_snow"),
    ("wxSnow", f"{_WX} == 7", "ic_wx_snow"),
    ("wxHeavySnow", f"{_WX} == 5", "ic_wx_heavy_snow"),
    ("wxSleet", f"{_WX} == 10", "ic_wx_sleet"),
    ("wxThunderstorm", f"{_WX} == 9", "ic_wx_thunderstorm"),
    ("wxWindy", f"{_WX} == 15", "ic_wx_windy"),
]


def render_bolt(out, px=96):
    """The charging bolt: green with a dark outline (DataDial's drawBolt).
    Drawn in color, so it is not tinted."""
    big = px * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    shape = [(0.16, -0.50), (-0.30, 0.08), (-0.03, 0.08), (-0.16, 0.50), (0.30, -0.08), (0.03, -0.08)]
    # The image is 1.15 icon sizes across: the outline pass fills it.
    for scale, color in ((1.0, (0, 0, 0, 255)), (0.85 / 1.15, (0x3D, 0xDC, 0x57, 255))):
        d.polygon([(big / 2 + x * big * scale, big / 2 + y * big * scale) for x, y in shape], fill=color)
    downsample(img, (px, px)).save(out)


#: Hand geometry from DataDial, in units of the radius: start of the frame,
#: length, and frame width in watch face units.
HOUR_HAND = (0.24, 0.52, 13)
MINUTE_HAND = (0.30, 0.86, 11)
SECOND_HAND = (0.30, 0.92)  # tail behind the center, tip
#: Image box per hand resource: width, distance above the center, below.
HAND_IMAGES = {}

CHROME_STOPS = [0.0, 0.15, 0.32, 0.42, 0.52, 0.70, 0.85, 1.0]
CHROME_LEVELS = [255, 240, 214, 196, 220, 252, 232, 255]
CHROME_SHADE = 0.84


def chrome(t, scale):
    """Cool steel gray at a position t (0..1) along the hand
    (DataDial's chromeColor)."""
    level = CHROME_LEVELS[0]
    for i in range(1, len(CHROME_STOPS)):
        if t <= CHROME_STOPS[i]:
            f = (t - CHROME_STOPS[i - 1]) / (CHROME_STOPS[i] - CHROME_STOPS[i - 1])
            f = f * f * (3 - 2 * f)
            level = CHROME_LEVELS[i - 1] + (CHROME_LEVELS[i] - CHROME_LEVELS[i - 1]) * f
            break
    level *= scale
    return round(level * 0.95), round(level * 0.975), round(level)


def render_hand(name, start, length, width, flat):
    """A skeleton hand pointing up: a thin stem from the center, then a
    rounded bar with an open middle so the stats show through, with a 1-unit
    dark edge. Steel (lighter on the left half) unless flat, which is white
    for tinting in always-on mode. The center of the dial is `top` units
    below the top of the image."""
    frm, ln = start * R, length * R
    to = ln - width / 2
    half = width / 2
    hole_half = (width - 6) / 2
    hole_from = frm + width * 0.6
    hole_to = ln - width * 1.4
    w = 2 * math.ceil((width + 4) / 2)  # even, so the image centers on the dial
    top = math.ceil(ln + 2)
    bottom = 3
    HAND_IMAGES[name] = (w, top, bottom)

    k = SCALE * SS
    W, H = w * k, (top + bottom) * k
    cx = W / 2

    def ty(t):  # distance from center to image y
        return (top - t) * k

    def stadium(img, t0, t1, hw, fill):
        ImageDraw.Draw(img).rounded_rectangle(
            [cx - hw * k, ty(t1 + hw), cx + hw * k, ty(t0 - hw)], radius=hw * k, fill=fill)

    def ring(grow):
        m = Image.new("L", (W, H), 0)
        stadium(m, frm, to, half + grow, 255)
        stadium(m, hole_from, hole_to, hole_half - grow, 0)
        return m

    def stem(px_width):
        m = Image.new("L", (W, H), 0)
        ImageDraw.Draw(m).line([(cx, ty(0)), (cx, ty(frm))], fill=255, width=round(px_width * k))
        return m

    edge = ring(1)
    edge.paste(255, mask=stem(4))
    frame = ring(0)
    frame.paste(255, mask=stem(2))

    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    img.paste((0, 0, 0, 255), mask=edge)
    if flat:
        img.paste((255, 255, 255, 255), mask=frame)
    else:
        steel = Image.new("RGBA", (W, H))
        sd = ImageDraw.Draw(steel)
        for row in range(H):
            t = 1 - row / H
            sd.line([(0, row), (cx, row)], fill=chrome(t, 1.0))
            sd.line([(cx, row), (W, row)], fill=chrome(t, CHROME_SHADE))
        img.paste(steel, mask=frame)
    downsample(img, (w * SCALE, (top + bottom) * SCALE)).save(DRAWABLE / f"{name}.png")


def render_second_hand():
    """Red line through the center, from a short tail to near the edge."""
    tail, tip = SECOND_HAND[0] * R, SECOND_HAND[1] * R
    w, top, bottom = 4, math.ceil(tip), math.ceil(tail)
    HAND_IMAGES["hand_second"] = (w, top, bottom)
    k = SCALE * SS
    img = Image.new("RGBA", (w * k, (top + bottom) * k), (0, 0, 0, 0))
    ImageDraw.Draw(img).line([(w * k / 2, (top - tip) * k), (w * k / 2, (top + tail) * k)],
                             fill=(0xFF, 0x3B, 0x30, 255), width=2 * k)
    downsample(img, (w * SCALE, (top + bottom) * SCALE)).save(DRAWABLE / "hand_second.png")


def render_images():
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    for svg, name in (("heart", "ic_heart"), ("steps", "ic_steps"), ("flame", "ic_calories"),
                      ("floors", "ic_floors"), ("thermometer", "ic_thermometer"), ("uv", "ic_uv")):
        render_icon(ART / f"{svg}.svg", DRAWABLE / f"{name}.png")
    for name, draw_fn in WEATHER_DRAWINGS.items():
        render_symbol(DRAWABLE / f"{name}.png", draw_fn)
    render_bolt(DRAWABLE / "ic_bolt.png")
    for name, (start, length, width) in (("hand_hour", HOUR_HAND), ("hand_minute", MINUTE_HAND)):
        render_hand(name, start, length, width, flat=False)
        render_hand(f"{name}_flat", start, length, width, flat=True)
    render_second_hand()


#: DataDial Stats' icons: drawable name and the art/ SVG it comes from. The
#: data source icons are white (the watch face tints them).
STATS_ICONS = [("ic_elevation", "mountain")]


def vector(paths, color="#FFFFFF", background=None, scale=1.0):
    """An Android vector drawable of 24 x 24 units from (path data, fill
    rule) pairs, scaled about the center, optionally on a filled circle."""
    root = ET.Element("vector", {
        "xmlns:android": "http://schemas.android.com/apk/res/android",
        "android:width": "24dp", "android:height": "24dp",
        "android:viewportWidth": "24", "android:viewportHeight": "24"})
    if background:
        el(root, "path", **{"android:fillColor": background,
                            "android:pathData": "M12 0A12 12 0 1 1 12 24A12 12 0 1 1 12 0Z"})
    group = el(root, "group", **{"android:pivotX": "12", "android:pivotY": "12",
                                 "android:scaleX": str(scale), "android:scaleY": str(scale)})
    for d, rule in paths:
        el(group, "path", **{"android:fillColor": color, "android:pathData": d,
                             "android:fillType": "evenOdd" if rule == "evenodd" else "nonZero"})
    ET.indent(root, space="    ")
    return '<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding="unicode") + "\n"


def svg_path_data(svg_file):
    """The (path data, fill rule) pairs of one of DataDial's icon SVGs: its
    paths, and its ellipses, circles and rects as path data."""
    shapes = []
    for e in ET.parse(svg_file).getroot().iter():
        tag = e.tag.split("}")[-1]
        rule = e.get("fill-rule", "nonzero")
        if tag == "path":
            shapes.append((e.get("d"), rule))
        elif tag in ("ellipse", "circle"):
            cx, cy = float(e.get("cx")), float(e.get("cy"))
            rx = float(e.get("rx", e.get("r", 0)))
            ry = float(e.get("ry", e.get("r", 0)))
            # Two half arcs from the left edge to the right and back.
            shapes.append((f"M{fmt(cx - rx)} {fmt(cy)}A{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(cx + rx)} "
                           f"{fmt(cy)}A{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(cx - rx)} {fmt(cy)}Z", rule))
        elif tag == "rect":
            x, y = float(e.get("x")), float(e.get("y"))
            w, h = float(e.get("width")), float(e.get("height"))
            shapes.append((f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}Z", rule))
    return shapes


def write_stats_icons():
    out = STATS_RES / "drawable"
    out.mkdir(parents=True, exist_ok=True)
    icons = {name: vector(svg_path_data(ART / f"{svg}.svg")) for name, svg in STATS_ICONS}
    # App icon: the elevation mountain in DataDial's blue on black.
    icons["ic_launcher"] = vector(svg_path_data(ART / "mountain.svg"), color=PALETTE[0][1],
                                  background="#000000", scale=0.6)
    for name, xml in icons.items():
        (out / f"{name}.xml").write_text(xml, encoding="utf-8", newline="\n")


#: The test build's time (hour, minute, second), also the picker preview's:
#: all three hands lie along divider lines, clear of the stats and of the
#: battery above the center.
TEST_TIME = (8, 19, 11)

#: Fixed worst-case values for the test build (--test), to check the layout:
#: the widest values, a goal gauge part way (yellow) and charging. DataDial
#: Stats has its own test values for its stats (build.sh test turns them on).
#: The digital time shows TEST_TIME (the analog hands are frozen at it in
#: watch_face).
TEST_VALUES = {
    "[HOUR_1_12]": str(TEST_TIME[0]),
    "[HOUR_0_23_Z]": f"{TEST_TIME[0]:02d}",
    "[MINUTE_Z]": f"{TEST_TIME[1]:02d}",
    "[SECOND_Z]": f"{TEST_TIME[2]:02d}",
    "[HEART_RATE]": "160",
    "[BATTERY_PERCENT]": "100",
    "[BATTERY_CHARGING_STATUS]": "(1 == 1)",
    "[WEATHER.IS_AVAILABLE]": "(1 == 1)",
    "[WEATHER.UV_INDEX]": "11",
    "[WEATHER.CONDITION]": "8",
    "[WEATHER.IS_DAY]": "(1 == 1)",
    "[WEATHER.TEMPERATURE]": "110",
    "[WEATHER.TEMPERATURE_UNIT]": "2",
    "[WEATHER.DAY_TEMPERATURE_HIGH]": "112",
    "[WEATHER.DAY_TEMPERATURE_LOW]": "95",
}


def write_xml(test=False):
    face = watch_face(test)
    ET.indent(face, space="    ")
    out = RES / "raw" / "watchface.xml"
    out.parent.mkdir(parents=True, exist_ok=True)
    body = ET.tostring(face, encoding="unicode")
    note = "Generated by tools/generate.py. Edit that script, not this file."
    if test:
        for source, value in TEST_VALUES.items():
            body = body.replace(source, value)
        # No double hyphens: they aren't allowed in an XML comment.
        note = "TEST BUILD with fixed values (generate.py test option). Don't ship this."
    header = f"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- {note} -->\n"
    out.write_text(header + body + "\n", encoding="utf-8", newline="\n")


def main():
    test = "--test" in sys.argv[1:]
    render_images()  # first: the XML needs the hand image sizes
    write_stats_icons()
    write_xml(test)
    print(f"Wrote {RES / 'raw' / 'watchface.xml'}{' (test values)' if test else ''}"
          f" and the images in {DRAWABLE}")


if __name__ == "__main__":
    main()
