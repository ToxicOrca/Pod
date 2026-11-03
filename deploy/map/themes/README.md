# Map themes

Dark map styles the app can switch between (Settings → Map & display). The server
serves these files as-is at `/map/themes/<id>.json`; the app fills in the
`__POD_PUBLIC_URL__` placeholder itself.

| id | Name |
|---|---|
| midnight | Midnight (default): balanced, readable |
| contrast | High contrast: bright roads, white labels |
| navy | Navy: blue-tinted |
| oled | Pure black |
| classic | Classic: original Protomaps dark |

## Regenerating

`gen-themes.ts` builds them from the Protomaps basemap layers
(github.com/protomaps/basemaps, `styles/src`). Copy it into that folder (after making
the imports end in `.ts`), then:

    node --experimental-strip-types gen-themes.ts ./out

and copy `out/*.json` here. To add a theme, add an entry in `gen-themes.ts` and in
`android/.../ui/map/MapThemes.kt`.
