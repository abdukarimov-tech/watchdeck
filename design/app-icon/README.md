# WatchDeck W-pulse

Canonical geometry: `watchdeck.svg`, viewport 108×108. Graphite `#12161D`, blue `#6B9EFF`.
The panel outline is 4 units wide, the W is 7 units wide, and the status dot is a circle.

Android uses the same paths scaled by 2/3 about (54,54), compensating for adaptive
icons cropping the 108dp canvas to the central 72dp. The foreground also supplies
the monochrome mask for themed icons. The OS applies the launcher shape.

iOS uses a 1024×1024 RGB PNG rendered from the SVG, with no alpha channel or
pre-rounded outer corners. `preview.png` shows the mark at 352, 128, 64 and 32px.

Export with CairoSVG, then convert to RGB with Pillow. On Homebrew macOS, set
`DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib` so CairoSVG can load libcairo.
