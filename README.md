# JEI Dump

A client-side Minecraft 1.12.2 mod that exports every JEI recipe (vanilla + every JEI plugin) into a self-contained static site (HTML + CSS + JS + PNG assets) that you can open in any browser.

## Usage

1. Drop the built jar into your `mods/` folder along with JEI 4.16+.
2. Launch the game and load any world (single-player is fine).
3. Open the JEI overlay once so the runtime is fully populated, then press `T` and run:
   ```
   /dumpjei              # writes to <gameDir>/jeidump/<UTC-timestamp>/
   /dumpjei myfolder     # writes to <gameDir>/jeidump/myfolder/
   ```
4. The game may lag while it renders (should be unplayable for most people at default speed).

When done, open `<gameDir>/jeidump/<folder>/index.html`.

## Output layout

```
index.html
assets/style.css
assets/app.js
data/index.json
data/categories/<sanitized_uid>/recipe_N.png
data/items/<dedup_id>.png
data/fluids/<dedup_id>.png
```

The frontend is dependency-free: no lunr, no React, no build step. The "fuzzy" search is a lowercase substring + subsequence scorer that handles tens of thousands of entries in the browser without trouble.

## TODO
- [x] Internationalize the command feedback messages (currently hardcoded in English).
- [x] Add a "last updated" timestamp to the footer.
- [x] Add dispatch to translated pages, so the frontend can load images in the appropriate language, if said dump is available. Add language selector to the UI to switch between them and add I18n support for the rest of the UI text.
- [ ] Add dispatch to version, so the frontend can show the recipe in different versions if multiple dumps are available.
- [x] Separate the background texture (shared by category) from the content (items, fluids, text, etc), to reduce dump size and caching overhead.
- [ ] Add support for other types than items and fluids, such Gases/Essentia/Mana/etc. This will require a more flexible data model and frontend.


## Build

To build, run:
```
./gradlew build
```

The jar will be available in `build/libs/`.
