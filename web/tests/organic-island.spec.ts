import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {architecture} from "../lib/architecture";
import {organicCityLayout} from "../lib/city-layout";
import {organicPoint,surveyPoint,organicStreets,dryLand} from "../lib/organic-layout";
import {RIVER,nearestRiver} from "../lib/river";
import {onIsland,islandElevation,walkElevation,riverLevel} from "../lib/island";
import {walkable} from "../components/ImmersiveWorld";
import type {World} from "../lib/types";

test("curved streets, terrain river and wilderness share one geometry",()=>{
  for(const street of organicStreets)for(const p of street){expect(onIsland(p)).toBe(true);const q=surveyPoint(p.x,p.z),r=organicPoint(q.x,q.z);expect(Math.hypot(r.x-p.x,r.z-p.z)).toBeLessThan(.0001);}
  expect(islandElevation(RIVER[0].x,RIVER[0].z)).toBeGreaterThan(50);
  expect(onIsland(RIVER.at(-1)!)).toBe(false);
  for(let i=1;i<RIVER.length;i++)expect(riverLevel(i)).toBeLessThanOrEqual(riverLevel(i-1));
  for(const p of [{x:-225,z:-450},{x:-595,z:-100},{x:-150,z:350}]){expect(dryLand(p)).toBe(true);expect(walkable(p,[],organicCityLayout)).toBe(true);}
  expect(walkable({x:3000,z:3000},[],organicCityLayout)).toBe(false);
  expect(dryLand({x:185,z:-5})).toBe(false);
  const bridge=RIVER.reduce((a,b)=>Math.abs(b.z-70)<Math.abs(a.z-70)?b:a,RIVER[0]);expect(dryLand({x:bridge.x,z:70})).toBe(true);
  expect(walkElevation(bridge.x,70)).toBeGreaterThan(islandElevation(bridge.x,70)+.5);
  expect(nearestRiver(185,-5).distance).toBeLessThan(.001);
});

test("residential architecture has multiple stories, roofs and footprints",()=>{
  for(const type of ["house","apartment"]){
    const variants=Array.from({length:40},(_,i)=>architecture(`${type}-${101+i}`,type));
    expect(new Set(variants.map(a=>a.style)).size).toBe(5);
    expect(new Set(variants.map(a=>a.height)).size).toBeGreaterThan(3);
    for(const a of variants)expect(a.width/2*Math.cos(a.angle)+a.depth/2*Math.abs(Math.sin(a.angle))).toBeLessThan(3.5);
  }
});

for(const mobile of [false,true])test(`organic island renders and wilderness travel works on ${mobile?"mobile":"desktop"}`,async({page},testInfo)=>{
  test.setTimeout(120000);
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/organic-world.json"),"utf8").replace(/^\uFEFF/,""));
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",r=>r.fulfill({status:401,json:{message:"Preview"}}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/organic-test"}}));
  await page.routeWebSocket("**/ws/organic-test",socket=>socket.send(JSON.stringify(world)));
  await page.goto("/");
  const canvas=page.locator(".world-stage canvas");await expect(canvas).toBeVisible({timeout:45000});
  await expect(canvas).toHaveAttribute("data-river-course","mountain-to-sea",{timeout:30000});
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("organic-island.png")});
  await page.getByRole("button",{name:"View settlement",exact:true}).click();await expect(page.getByLabel("Map zoom")).toHaveText("200%");
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("organic-settlement.png")});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:30000});
  await page.getByRole("button",{name:"World map",exact:true}).click();
  await page.getByRole("button",{name:/Cloudpeak Mountain Lookout.*Visit/}).click();
  await expect(page.getByLabel("Your world position")).toHaveText("-225.0, -450.0");
  const before=await page.getByLabel("Your world position").textContent();
  await page.keyboard.down("w");await expect(page.getByLabel("Your world position")).not.toHaveText(before!);await page.keyboard.up("w");
  await page.screenshot({path:testInfo.outputPath("mountain-exploration.png"),fullPage:true});
  await page.getByRole("button",{name:"World map",exact:true}).click();
  await page.getByRole("button",{name:/South Coast Beach.*Visit/}).click();
  await expect(page.getByLabel("Your world position")).toHaveText("-150.0, 350.0");
  await page.getByRole("button",{name:"Back to dashboard"}).click();
  expect(errors).toEqual([]);
});
