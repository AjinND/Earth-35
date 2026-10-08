import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {outdoorPatch} from "../lib/outdoor-layout";
import {dryLand,streetDistance} from "../lib/organic-layout";
import {enclosedPlace,organicCityLayout} from "../lib/city-layout";
import {walkable} from "../components/ImmersiveWorld";
import type {World} from "../lib/types";

const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
test("destinations have substantial connected grounds and keep roads and properties clear",()=>{
  const sizes:Record<string,number>={};
  for(const b of world.buildings.filter(b=>(b.outdoorRadius??0)>0)) {
    const patch=outdoorPatch(b,world.buildings);sizes[b.id]=patch.area;
    expect(patch.area,b.name).toBeGreaterThan(100);
    expect(patch.cells.every(dryLand),b.name+" stays on land").toBe(true);
    expect(patch.cells.every(cell=>streetDistance(cell.x,cell.z)>3.4),b.name+" keeps roads clear").toBe(true);
    expect(patch.cells.some(cell=>world.buildings.some(p=>enclosedPlace(p.type)&&Math.abs(p.x-cell.x)<4.8&&Math.abs(p.z-cell.z)<4.8)),b.name+" keeps properties clear").toBe(false);
  }
  console.log("Destination ground areas:",sizes);
  // Previous plaza occupied 8.4 × 8.4, and parks approximately 12 × 12.
  expect(sizes["town-square"]).toBeGreaterThan(4*8.4*8.4);
  for(const id of ["park","willow-park","meadow-commons","south-gardens"])expect(sizes[id],id).toBeGreaterThan(4*12*12);
  expect(sizes["meadow-commons"]).toBeGreaterThan(2000);
});
test("growth adds homes and workplaces while fenced construction remains a navigation obstacle",()=>{
  expect(world.buildings.length).toBe(168);
  expect(world.buildings.filter(b=>b.capacity===1).length).toBe(110);
  const sites=world.buildings.filter(b=>b.type==="construction");expect(sites.length).toBe(10);
  for(const b of sites){expect(b.hours?.openNow).toBe(false);expect(b.capacity).toBe(0);expect(walkable(b,world.buildings,organicCityLayout)).toBe(false);}
  for(const id of ["meadow-commons","south-gardens"])expect(walkable(world.buildings.find(b=>b.id===id)!,world.buildings,organicCityLayout)).toBe(true);
});
for(const mobile of [false,true])test(`larger parks and growing city render on ${mobile?"mobile":"desktop"}`,async({page},testInfo)=>{
  test.setTimeout(120000);await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",r=>r.fulfill({status:401,json:{message:"Preview"}}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/growth-test"}}));
  await page.routeWebSocket("**/ws/growth-test",socket=>socket.send(JSON.stringify(world)));
  await page.goto("/");const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-construction-sites","10",{timeout:45000});
  await expect(canvas).toHaveAttribute("data-expanded-destinations","17");
  await page.getByRole("button",{name:"View settlement",exact:true}).click();
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("growing-settlement.png")});
  await page.getByRole("button",{name:"Browse places"}).click();
  await page.getByRole("button",{name:"Founders Square",exact:true}).click();
  const square=world.buildings.find(b=>b.id==="town-square")!;await expect(canvas).toHaveAttribute("data-target-x",square.x.toFixed(3));
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("expanded-founders-square.png")});
  await page.getByRole("button",{name:"Browse places"}).click();
  await page.getByRole("button",{name:"Meadow Commons",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-target-x","-416.000");
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("meadow-commons.png")});
  await page.getByRole("button",{name:"Browse places"}).click();
  await page.getByRole("button",{name:"All places",exact:true}).click();
  await page.getByRole("button",{name:"Founders Offices · Site 8",exact:true}).click();
  await expect(page.locator(".inspect-panel")).toContainText("Under construction · Closed to visitors");
  const site=world.buildings.find(b=>b.id==="construction-208")!;await expect(canvas).toHaveAttribute("data-target-x",site.x.toFixed(3));
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("construction-site.png")});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:30000});
  for(const destination of [{name:/Meadow Commons.*Visit/,position:"-416.0, 12.0"},{name:/South Gardens.*Visit/,position:"-220.0, 290.0"}]) {
    await page.getByRole("button",{name:"World map",exact:true}).click();await page.getByRole("button",{name:destination.name}).click();
    await expect(page.getByLabel("Your world position")).toHaveText(destination.position);
    await page.keyboard.down("w");await expect(page.getByLabel("Your world position")).not.toHaveText(destination.position);await page.keyboard.up("w");
  }
  await page.getByRole("button",{name:"Back to dashboard"}).click();expect(errors).toEqual([]);
});
