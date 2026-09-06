# MetaStrip

A small Android app that removes metadata from photos before you share them.
No permissions, no network access, no analytics. Free software under the
GNU General Public License v3.0 or later.

## Why

Photos carry hidden data: where they were taken, when, with what phone,
sometimes even the owner's name. Sharing a photo usually means sharing all
of that too, unless something strips it first.

## What it removes

EXIF, XMP, IPTC, ICC colour profiles, embedded thumbnails, comments, and
vendor-specific blocks. The exact method depends on the file type.

**PNG, WebP, GIF, BMP**
Always rebuilt from raw pixels into a fresh lossless file. Nothing but
pixel data goes in, so nothing else can come out.

**JPEG, HEIC, AVIF**
Rebuilt from raw pixels by default and saved as a quality 95 JPEG. This is
the only method that also catches metadata types the app doesn't know
about by name.

You can turn this off in Settings to keep the original pixels byte for
byte and remove only the known metadata blocks instead, so photo quality
is untouched. The trade-off: unusual or hidden data inside the pixel
stream can't be detected, and a sideways JPEG may lose its rotation since
that lives in the EXIF block being removed. HEIC and AVIF store rotation
separately, so they keep it either way.

## Features

- Pick an image, or share one in from any other app
- Strip metadata and save a clean copy, original file untouched
- Random 13-character file names by default, so the name gives nothing away
- Choose where clean files are saved, including a custom folder
- Settings page with plain-language explanations, not just switches

## Privacy

- Zero runtime permissions. The system photo picker supplies the input,
  MediaStore or the system folder picker writes the output.
- No internet access. Nothing leaves your device.
- No analytics, no crash reporting, no accounts, no ads.

## Building

Requirements: Android Studio, JDK 17.

```
git clone https://github.com/yourhandle/metastrip.git
```

Open the folder in Android Studio, let Gradle sync, and run on a device or
emulator with Android 10 (API 29) or newer.

## Project layout

- `org.metastrip.core`: the metadata strippers. Plain Kotlin standard
  library only, no Android or Java imports, so it's testable on a JVM and
  ready to move into a Kotlin Multiplatform common source set later.
- `org.metastrip.app`: everything Android — picking, decoding, re-encoding,
  saving, settings, and UI.

## Contributing

Issues and pull requests are welcome. If you're adding support for a new
format or changing the stripping logic, please include a test file (or a
script that generates one) showing the metadata is actually gone and the
pixels are unchanged.

## License

MetaStrip is free software: you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the
Free Software Foundation, either version 3 of the License, or (at your
option) any later version.

MetaStrip is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
more details.

You should have received a copy of the GNU General Public License along
with MetaStrip. If not, see <https://www.gnu.org/licenses/>.
