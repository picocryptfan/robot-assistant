#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Writes the Material Symbols path data in app/src/main/java/app/heyrobot/ui/common/Icons.kt (RIcons).

Robot draws its icons from path data in the source instead of a multi-megabyte icon library. This fetches each symbol
below from Google's material-design-icons repository (Apache-2.0) at a pinned commit, Material Symbols Outlined, 24 px
optical size, weight 400, grade 0, fill 0 ("symbols/web/<name>/materialsymbolsoutlined/<name>_24px.svg"), checks it is
one or more plain paths on the 0 -960 960 960 viewBox, and replaces the lines between "// <symbols>" and
"// </symbols>" in Icons.kt. Run on a computer when the list changes; the app never fetches anything.

Usage: python3 tools/symbols.py <robot project dir>
"""
import re
import sys
import urllib.request
from pathlib import Path

REPO = "https://raw.githubusercontent.com/google/material-design-icons"
COMMIT = "49d4db35df873165d6bd6ba09b063c7dafbac2f4"

# RIcons property, Material Symbols name, what Robot uses it for (a KDoc line; empty for none).
SYMBOLS = [
    ("Mic", "mic", ""),
    ("Send", "send", ""),
    ("Stop", "stop", ""),
    ("StopCircle", "stop_circle", "Stop listening."),
    ("Pause", "pause", ""),
    ("Record", "fiber_manual_record", "Recording (tinted with the error colour)."),
    ("Camera", "photo_camera", ""),
    ("Image", "photo_library", ""),
    ("ScreenShare", "screenshot", "Ask about this screen."),
    ("Close", "close", ""),
    ("Add", "add", ""),
    ("VolumeUp", "volume_up", ""),
    ("VolumeOff", "volume_off", ""),
    ("History", "history", ""),
    ("Settings", "settings", ""),
    ("SettingsVoice", "settings_voice", "Assistant setup."),
    ("Home", "home", ""),
    ("Back", "arrow_back", ""),
    ("MoreVert", "more_vert", "The overflow menu."),
    ("Delete", "delete", ""),
    ("Check", "check_circle", "Done, ready, allowed (a status)."),
    ("Tick", "check", "A plain check mark (a switch's thumb when on)."),
    ("Unchecked", "radio_button_unchecked", "Still to do (a status)."),
    ("Error", "error", "A problem (a status)."),
    ("Warning", "warning", "A risk to know about first."),
    ("Info", "info", ""),
    ("Lock", "lock", ""),
    ("Shield", "shield", "Safety & privacy."),
    ("Timer", "timer", ""),
    ("Alarm", "alarm", ""),
    ("Apps", "apps", ""),
    ("Place", "location_on", ""),
    ("Flash", "flashlight_on", ""),
    ("Bookmark", "bookmark_add", "Keep this conversation."),
    ("BookmarkAdded", "bookmark_added", "Kept."),
    ("Phone", "smartphone", ""),
    ("Chevron", "chevron_right", "Only on rows that open a page."),
    ("Download", "download", ""),
    ("Folder", "folder", ""),
    ("Refresh", "refresh", ""),
    ("Copy", "content_copy", ""),
    ("Chip", "memory", "The model."),
    ("Message", "sms", ""),
    ("Email", "mail", ""),
    ("Person", "person", ""),
    ("Contacts", "contacts", "People, notes and calendar."),
    ("Note", "sticky_note_2", ""),
    ("Event", "event", ""),
    ("Search", "search", ""),
    ("Share", "share", ""),
    ("Sunny", "partly_cloudy_day", "The weather."),
    ("Cloud", "cloud", "Claude."),
    ("Palette", "palette", "Display."),
    ("RecordVoiceOver", "record_voice_over", "Robot's voice."),
    ("TouchApp", "touch_app", "Actions."),
    ("Terminal", "terminal", "ADB fixes."),
    ("Transcribe", "transcribe", "Transcribing (the tile and shortcuts)."),
    ("Call", "call", "A phone call (0.8)."),
    ("Headset", "headphones", "Headset mode."),
    ("Car", "directions_car", "Car mode."),
    ("Navigate", "near_me", "A route in Organic Maps (car mode)."),
    ("Key", "key", "Car mode's trusted session."),
    ("Bluetooth", "bluetooth", ""),
]


def fetch(name: str) -> list:
    url = f"{REPO}/{COMMIT}/symbols/web/{name}/materialsymbolsoutlined/{name}_24px.svg"
    svg = urllib.request.urlopen(url, timeout=30).read().decode("utf-8")
    if 'viewBox="0 -960 960 960"' not in svg:
        raise SystemExit(f"{name}: unexpected viewBox")
    body = re.sub(r"^<svg[^>]*>|</svg>\s*$", "", svg.strip())
    paths = re.findall(r'<path d="([^"]+)"\s*/>', body)
    if not paths or re.sub(r'<path d="[^"]+"\s*/>', "", body).strip():
        raise SystemExit(f"{name}: not plain paths")
    return paths


def main() -> None:
    root = Path(sys.argv[1])
    kt = root / "app/src/main/java/app/heyrobot/ui/common/Icons.kt"
    lines = []
    for prop, name, doc in SYMBOLS:
        paths = fetch(name)
        if doc:
            lines.append(f"    /** {doc} */")
        args = ", ".join(f'"{p}"' for p in paths)
        lines.append(f'    val {prop} by lazy {{ symbol("{name}", {args}) }}')
    text = kt.read_text()
    start, end = "    // <symbols>\n", "    // </symbols>\n"
    if start not in text or end not in text:
        raise SystemExit("markers missing in Icons.kt")
    head, rest = text.split(start, 1)
    _, tail = rest.split(end, 1)
    kt.write_text(head + start + "\n".join(lines) + "\n" + end + tail)
    print(f"{len(SYMBOLS)} symbols written to {kt}")


if __name__ == "__main__":
    main()
