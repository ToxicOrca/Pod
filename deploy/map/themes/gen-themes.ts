import { writeFileSync, mkdirSync } from "fs";
import { layers } from "./index.ts";
import { DARK } from "./flavors.ts";

const P = "__POD_PUBLIC_URL__";
const out = process.argv[2];
mkdirSync(out, { recursive: true });

function road(o: any, minor: string, major: string, highway: string, casing: string) {
  for (const k of ["other", "minor_service", "minor_a", "minor_b", "tunnel_other", "tunnel_minor", "bridges_other", "bridges_minor"]) o[k] = minor;
  for (const k of ["link", "major", "tunnel_link", "tunnel_major", "bridges_link", "bridges_major"]) o[k] = major;
  for (const k of ["highway", "tunnel_highway", "bridges_highway"]) o[k] = highway;
  for (const k of Object.keys(o)) if (k.endsWith("_casing") || k.endsWith("casing_early") || k.endsWith("casing_late")) o[k] = casing;
}
function labels(o: any, minor: string, major: string, city: string, halo: string) {
  o.roads_label_minor = minor; o.roads_label_major = major; o.subplace_label = minor;
  o.address_label = minor; o.city_label = city; o.state_label = major; o.country_label = major; o.ocean_label = major;
  for (const k of Object.keys(o)) if (k.endsWith("_halo")) o[k] = halo;
}
function land(o: any, earth: string, water: string, park: string, buildings: string, bg: string) {
  o.background = bg; o.earth = earth; o.water = water; o.park_a = park; o.park_b = park; o.wood_a = park; o.wood_b = park;
  o.buildings = buildings;
  for (const k of ["hospital", "industrial", "school", "pedestrian", "scrub_a", "scrub_b", "sand", "beach", "aerodrome", "zoo", "military", "glacier"]) o[k] = earth;
  o.landcover = { grassland: park, barren: earth, urban_area: earth, farmland: park, glacier: earth, scrub: park, forest: park };
}

const themes: Record<string, any> = {};
{ const t: any = structuredClone(DARK); land(t, "#16181c", "#1b2b3d", "#17261f", "#24272d", "#121417");
  road(t, "#3a3f47", "#575d68", "#737b88", "#101215"); labels(t, "#8f96a1", "#aab1bc", "#d0d5dc", "#0e1013"); themes.midnight = t; }
{ const t: any = structuredClone(DARK); land(t, "#0f1726", "#0a2846", "#0f2a26", "#1a2436", "#0b1220");
  road(t, "#273650", "#3a5074", "#55719e", "#0a101c"); labels(t, "#8ea6c8", "#a9bfdc", "#d4e2f5", "#0a101c"); themes.navy = t; }
{ const t: any = structuredClone(DARK); land(t, "#000000", "#06111d", "#07140f", "#111214", "#000000");
  road(t, "#26282c", "#3c3f45", "#55595f", "#000000"); labels(t, "#7c828b", "#99a0a9", "#c4c9d0", "#000000"); themes.oled = t; }
{ const t: any = structuredClone(DARK); land(t, "#121212", "#0f3557", "#14301f", "#2a2a2a", "#0a0a0a");
  road(t, "#5c5c5c", "#8c8c8c", "#d1a541", "#050505"); labels(t, "#d0d0d0", "#ececec", "#ffffff", "#000000"); themes.contrast = t; }
themes.classic = structuredClone(DARK);

for (const [id, flavor] of Object.entries(themes)) {
  const style = {
    version: 8,
    sources: { protomaps: { type: "vector", attribution: '<a href="https://github.com/protomaps/basemaps">Protomaps</a> © <a href="https://osm.org/copyright">OpenStreetMap</a>', url: `pmtiles://${P}/map/tiles.pmtiles` } },
    layers: layers("protomaps", flavor, { lang: "en" }),
    glyphs: `${P}/map/fonts/{fontstack}/{range}.pbf`,
    sprite: `${P}/map/sprites/dark`,
  };
  writeFileSync(`${out}/${id}.json`, JSON.stringify(style));
}
console.log(Object.keys(themes));
