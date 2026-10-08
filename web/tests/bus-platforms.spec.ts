import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {busPlatformPlacements} from "../lib/street-furniture";
import {organicCityLayout} from "../lib/city-layout";
import {streetDistance} from "../lib/organic-layout";
import type {World} from "../lib/types";

const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));

test("both curb platforms follow the curved road and keep the entire slab off asphalt",()=>{
  for(const stop of world.transport!.stops) {
    const building=world.buildings.find(b=>b.id===stop.id)!;
    const platforms=busPlatformPlacements(building,stop,organicCityLayout);
    expect(platforms.map(p=>p.route)).toEqual(["R1","R2"]);
    const [a,b]=platforms;
    expect(Math.hypot(a.x-b.x,a.z-b.z)).toBeCloseTo(4.8,5);
    expect(Math.cos(a.heading-b.heading)).toBeCloseTo(-1,5);
    for(const platform of platforms)for(let x=-.4;x<=.4;x+=.2)for(let z=-2.25;z<=2.25;z+=.45) {
      const px=platform.x+Math.cos(platform.heading)*x+Math.sin(platform.heading)*z;
      const pz=platform.z-Math.sin(platform.heading)*x+Math.cos(platform.heading)*z;
      expect(streetDistance(px,pz),`${stop.id} ${platform.route}`).toBeGreaterThan(1.6);
    }
  }
});

test("authoritative route platforms override legacy building coordinates",()=>{
  const stop=world.transport!.stops[0],building=world.buildings.find(b=>b.id===stop.id)!;
  const platforms=[{route:"R1",x:12,z:34,heading:1},{route:"R2",x:56,z:78,heading:2}];
  expect(busPlatformPlacements({...building,x:999,z:999},{...stop,platforms},organicCityLayout)).toEqual(platforms);
});
