import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {outdoorTrees,OUTDOOR_PLANTING,outdoorClearingRadius} from "../lib/outdoor-planting";
import {outdoorBenches,outdoorPatch,outdoorPath} from "../lib/outdoor-layout";
import {livingPlan} from "../lib/living-environment";
import {organicCityLayout} from "../lib/city-layout";
import type {World} from "../lib/types";

const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
world.geography={...organicCityLayout,version:4};world.minute=720;world.weather="Clear";world.agents=[];
const woodland=world.buildings.find(b=>b.id==="western-woods")!;

test("woodland has mixed, spaced trees and no second habitat layer through its leisure ground",()=>{
  const trees=outdoorTrees(woodland,world.buildings),patch=outdoorPatch(woodland,world.buildings);
  expect(trees.length).toBeGreaterThanOrEqual(24);expect(trees.length).toBeLessThanOrEqual(36);
  expect(new Set(trees.map(t=>t.kind))).toEqual(new Set(["birch","oak","pine"]));
  expect(new Set(trees.map(t=>t.size.toFixed(2))).size).toBeGreaterThan(12);
  for(const [i,tree] of trees.entries()) {
    expect(Math.hypot(tree.x-woodland.x,tree.z-woodland.z)-tree.radius).toBeGreaterThanOrEqual(16);
    for(const other of trees.slice(i+1))expect(Math.hypot(tree.x-other.x,tree.z-other.z)-tree.radius-other.radius).toBeGreaterThanOrEqual(4);
  }
  const habitat=livingPlan(world.buildings,world.objects);
  // Sample actual ground cells, not the reservation function itself: neither
  // tree crowns nor understory from the island layer may occupy this ground.
  const overlaps=habitat.plants.filter(plant=>patch.cells.some(cell=>Math.hypot(cell.x-plant.x,cell.z-plant.z)<plant.radius+1.5));
  expect(overlaps.map(p=>p.id)).toEqual([]);
  expect(outdoorTrees(woodland,[...world.buildings].reverse())).toEqual(trees);
  console.log("Western Woodland trees:",trees.length,Object.fromEntries(["birch","oak","pine"].map(kind=>[kind,trees.filter(t=>t.kind===kind).length])));
});

test("all leisure planting preserves walking lanes, seating and central clearings",()=>{
  for(const b of world.buildings.filter(b=>(b.outdoorRadius??0)>0)) {
    const trees=outdoorTrees(b,world.buildings),patch=outdoorPatch(b,world.buildings),benches=outdoorBenches(b,patch);
    expect(trees.length,b.id).toBeLessThanOrEqual(OUTDOOR_PLANTING.maxTrees);
    for(const tree of trees) {
      expect(Math.hypot(tree.x-b.x,tree.z-b.z)-tree.radius,b.id+" clearing").toBeGreaterThanOrEqual(outdoorClearingRadius(patch.radiusX,patch.radiusZ));
      for(const seat of benches)expect(Math.hypot(tree.x-b.x-seat.x,tree.z-b.z-seat.z)-tree.radius,b.id+" bench access").toBeGreaterThanOrEqual(3.5);
      // Independently sample the full canopy and its walking margin along bends.
      for(let i=0;i<48;i++) {
        const angle=i*Math.PI/24,r=tree.radius+1.8;
        expect(outdoorPath(b,tree.x-b.x+Math.cos(angle)*r,tree.z-b.z+Math.sin(angle)*r),b.id+" clear path").toBe(false);
      }
    }
  }
});

for(const mobile of [false,true])test(`woodland leisure view on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(150000);await page.setViewportSize(mobile?{width:390,height:844}:{width:1440,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Woodland review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/woodland-review"}}));
  await page.routeWebSocket("**/ws/woodland-review",socket=>socket.send(JSON.stringify(world)));
  await page.goto("/?resident=western-woods");const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-target-x","-595.000",{timeout:60000});
  await expect.poll(async()=>Number(await canvas.getAttribute("data-habitat-plants")),{timeout:60000}).toBeGreaterThan(2500);
  await page.getByRole("button",{name:"Close inspector"}).click();
  for(let i=0;i<(mobile?3:1);i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await page.locator(".world-stage").screenshot({path:info.outputPath("western-woodland.png")});
  if(!mobile) {
    await page.getByRole("button",{name:"Enter the world"}).click();
    const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});
    await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:60000});
    await page.getByRole("button",{name:"World map",exact:true}).click();
    await page.getByRole("button",{name:/Western Woodland.*Visit/}).click();
    await expect(page.getByLabel("Your world position")).toHaveText("-595.0, -100.0");
    await page.screenshot({path:info.outputPath("woodland-resting-clearing.png")});
    await page.keyboard.down("w");await expect(page.getByLabel("Your world position")).not.toHaveText("-595.0, -100.0");await page.keyboard.up("w");
    await page.getByRole("button",{name:"Back to dashboard"}).click();
    await page.getByRole("button",{name:"Reset camera"}).click();
    await page.getByRole("button",{name:"Browse places"}).click();
    await page.locator(".places-popover").getByRole("button",{name:"Central Park",exact:true}).click();
    await page.getByRole("button",{name:"Close inspector"}).click();
    await page.locator(".world-stage").screenshot({path:info.outputPath("central-park.png")});
  }
  expect(errors).toEqual([]);
});
