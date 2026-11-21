import {test,expect,type Page,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {LIVING_LIMITS,livingPlan,livingSpatialKey,plantingClear,wildlifeActive,wildlifePose,type PlantKind} from "../lib/living-environment";
import {createVegetationTiles,plantGeometry} from "../lib/vegetation-geometry";
import {onIsland,islandElevation} from "../lib/island";
import {LANDSCAPES,trailDistance} from "../lib/landscapes";
import {nearestRiver} from "../lib/river";
import {streetDistance} from "../lib/organic-layout";
import {walkable,walkSegmentClear,arrival} from "../components/ImmersiveWorld";
import {organicCityLayout} from "../lib/city-layout";
import type {Building,World} from "../lib/types";

const layout={...organicCityLayout,version:4};
function fixture():World {
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  world.geography=layout;world.minute=720;world.weather="Clear";world.agents=[];
  for(const p of LANDSCAPES.viewpoints)if(!world.buildings.some(b=>b.id===p.id))world.buildings.push({...p,type:"wilderness",capacity:100,social:true,restAllowed:true,objects:{},outdoorRadius:0} as Building);
  return world;
}

test("habitats have distinct, deterministic vegetation with complete access clearance",()=>{
  test.setTimeout(60000);const world=fixture(),before=JSON.stringify(world),plan=livingPlan(world.buildings,world.objects);
  expect(plan.plants.length).toBeGreaterThan(2500);expect(plan.plants.length).toBeLessThanOrEqual(LIVING_LIMITS.plants);
  expect(plan.plants.filter(p=>!p.detail).length).toBeLessThanOrEqual(LIVING_LIMITS.trees);
  const habitats=new Map<string,Set<PlantKind>>(),invalid:{id:number;rule:string}[]=[];
  for(const p of plan.plants) {
    const kinds=habitats.get(p.habitat)??new Set();kinds.add(p.kind);habitats.set(p.habitat,kinds);
    if(!onIsland(p))invalid.push({id:p.id,rule:"island"});
    if(Math.abs(p.y-islandElevation(p.x,p.z)+.08)>1e-8)invalid.push({id:p.id,rule:"ground"});
    if(!plantingClear(p.x,p.z,p.radius,world.buildings,world.objects))invalid.push({id:p.id,rule:"clearance"});
    if(trailDistance(p.x,p.z)<p.radius+2.6)invalid.push({id:p.id,rule:"trail"});
    if(streetDistance(p.x,p.z)<p.radius+4.2)invalid.push({id:p.id,rule:"street"});
    const river=nearestRiver(p.x,p.z);if(river.distance<river.width/2+p.radius+1)invalid.push({id:p.id,rule:"river"});
    if(!p.detail&&p.y>=68)invalid.push({id:p.id,rule:"tree line"});
  }
  expect(invalid).toEqual([]);
  expect(habitats.size).toBe(6);
  expect(habitats.get("forest")).toContain("fern");expect(habitats.get("mountain")).toContain("alpine-pine");
  expect(habitats.get("coast")).toContain("shrub");expect(habitats.get("meadow")).toContain("flowers");
  expect(habitats.get("riverbank")).toContain("willow");expect(habitats.get("city")).toContain("birch");
  expect(livingSpatialKey([...world.buildings].reverse(),world.objects)).toBe(livingSpatialKey(world.buildings,world.objects));
  expect(livingPlan([...world.buildings].reverse(),world.objects)).toBe(plan);
  expect(JSON.stringify(world)).toBe(before);
  console.log("Habitat population",Object.fromEntries([...habitats].map(([h,k])=>[h,{plants:plan.plants.filter(p=>p.habitat===h).length,kinds:[...k]}])));
});

test("new construction, entrances and resident signs displace only decorative planting",()=>{
  test.setTimeout(60000);const world=fixture(),original=livingPlan(world.buildings,world.objects);
  const site=original.plants.find(p=>p.habitat==="meadow"&&!p.detail)!,sign=original.plants.find(p=>p.habitat==="forest"&&!p.detail)!;
  const building={...world.buildings[0],id:"resident-new-site",type:"construction",x:site.x,z:site.z,outdoorRadius:0,entrance:{side:"front",x:site.x,z:site.z+3.6}};
  const objects=[...(world.objects??[]),{id:"new-sign",owner:"resident",business:null,kind:"signboard",text:"Local business",status:"installed",x:sign.x,z:sign.z}];
  const updated=livingPlan([...world.buildings,building],objects);
  expect(updated).not.toBe(original);
  expect(updated.plants.some(p=>p.id===site.id)).toBe(false);expect(updated.plants.some(p=>p.id===sign.id)).toBe(false);
  expect(updated.plants.filter(p=>!plantingClear(p.x,p.z,p.radius,[...world.buildings,building],objects))).toEqual([]);
  for(const b of world.buildings)expect(walkable(arrival(b,world.buildings,layout,world.objects),world.buildings,layout,world.objects)).toBe(true);
  for(const bridge of LANDSCAPES.bridges)expect(walkSegmentClear({x:bridge.x-bridge.length/2,z:bridge.z},{x:bridge.x+bridge.length/2,z:bridge.z},world.buildings,layout,world.objects)).toBe(true);
});

test("merged vegetation stays within the crown reservations and a fixed geometry budget",()=>{
  test.setTimeout(60000);const world=fixture(),plan=livingPlan(world.buildings,world.objects),geometry=createVegetationTiles(plan.plants);
  let vertices=0,calls=0;
  for(const tile of geometry.tiles)for(const layer of [tile.canopy,tile.understory])if(layer) {
    calls++;vertices+=layer.getAttribute("position").count;
    expect(layer.boundingSphere?.radius).toBeLessThan(190);
    expect(layer.getAttribute("position").array.every(Number.isFinite)).toBe(true);
  }
  expect(calls).toBeLessThanOrEqual(100);expect(vertices).toBeLessThan(1800000);
  for(const kind of new Set(plan.plants.map(p=>p.kind))) {
    const template=plantGeometry(kind),positions=template.getAttribute("position"),plant=plan.plants.find(p=>p.kind===kind)!;
    let radius=0;for(let i=0;i<positions.count;i++)radius=Math.max(radius,Math.hypot(positions.getX(i),positions.getZ(i)));
    expect(radius*plant.size,`${kind} exceeds placement clearance`).toBeLessThanOrEqual(plant.radius+.001);template.dispose();
  }
  console.log("Vegetation budget",{plants:plan.plants.length,trees:plan.plants.filter(p=>!p.detail).length,tiles:geometry.tiles.length,calls,vertices,wildlife:plan.wildlife.length});geometry.dispose();
});

test("ambient animals use safe habitat paths, shared time and static reduced-motion poses",()=>{
  test.setTimeout(60000);const world=fixture(),animals=livingPlan(world.buildings,world.objects).wildlife;
  expect(animals.length).toBeGreaterThan(8);expect(animals.length).toBeLessThanOrEqual(LIVING_LIMITS.wildlife);
  expect(new Set(animals.map(a=>a.kind)).size).toBe(4);
  for(const animal of animals) {
    for(const p of animal.path)expect(plantingClear(p.x,p.z,1.1,world.buildings,world.objects)).toBe(true);
    expect(wildlifePose(animal,720,true)).toEqual(wildlifePose(animal,721,true));
    expect(wildlifePose(animal,720)).not.toEqual(wildlifePose(animal,721));
    expect(wildlifePose(animal,720)).toEqual(wildlifePose(animal,720));
    expect(wildlifeActive(animal.kind,720,"Clear")).toBe(true);
    expect(wildlifeActive(animal.kind,720,"Rain")).toBe(false);
    expect(wildlifeActive(animal.kind,0,"Clear")).toBe(false);
    if(animal.kind==="gull")expect(animal.habitat).toBe("coast");
    for(let minute=720;minute<750;minute+=.37) {
      const pose=wildlifePose(animal,minute);
      expect(onIsland(pose)).toBe(true);
      expect(pose.y).toBeGreaterThanOrEqual(islandElevation(pose.x,pose.z)-.1);
    }
  }
});

async function controlled(page:Page) {
  let world=fixture(),socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Habitat review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3003/ws/habitats"}}));
  await page.routeWebSocket("**/ws/habitats",route=>{socket=route;route.send(JSON.stringify(world));});
  return (minute:number,weather="Clear")=>{world={...world,minute,weather,version:world.version+1};socket!.send(JSON.stringify(world));};
}

for(const mobile of [false,true])test(`living environment works at island and habitat scales on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(240000);await page.setViewportSize(mobile?{width:390,height:844}:{width:1440,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  await controlled(page);await page.goto("/");const canvas=page.locator(".world-stage canvas");
  await expect.poll(async()=>Number(await canvas.getAttribute("data-habitat-plants")),{timeout:60000}).toBeGreaterThan(2500);
  await expect(canvas).toHaveAttribute("data-habitat-detail-tiles","0");await expect(canvas).toHaveAttribute("data-wildlife-visible","0");
  await page.locator(".world-stage").screenshot({path:info.outputPath("island.png")});
  for(const id of ["fern-valley","moonwash-cove","cloudmere-view","silverstream-estuary","park"]) {
    const place=fixture().buildings.find(b=>b.id===id)!;
    await page.getByRole("button",{name:"Reset camera"}).click();
    await page.getByRole("button",{name:"Browse places"}).click();
    await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
    await expect(canvas).toHaveAttribute("data-target-x",place.x.toFixed(3));
    for(let i=0;i<(mobile?4:1);i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
    await page.getByRole("button",{name:"Close inspector"}).click();
    await expect.poll(async()=>Number(await canvas.getAttribute("data-habitat-detail-tiles"))).toBeGreaterThan(0);
    await page.locator(".world-stage").screenshot({path:info.outputPath(`${id}.png`)});
  }
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});
  await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:60000});
  for(const name of ["Fern Valley Rest","Moonwash Cove"]) {
    await page.getByRole("button",{name:"World map",exact:true}).click();
    await page.getByRole("button",{name:new RegExp(`${name}.*Visit`)}).click();
    const world=fixture(),place=world.buildings.find(b=>b.name===name)!,position=arrival(place,world.buildings,layout,world.objects);
    await expect(page.getByLabel("Your world position")).toHaveText(`${position.x.toFixed(1)}, ${position.z.toFixed(1)}`);
    await page.screenshot({path:info.outputPath(`walk-${name.replaceAll(" ","-")}.png`)});
  }
  expect(errors).toEqual([]);
});

test("live wildlife freezes across clock updates and motion-preference changes",async({page},info)=>{
  test.setTimeout(120000);await page.setViewportSize({width:1440,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});const update=await controlled(page);
  await page.goto("/?resident=fern-valley");const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-wildlife-motion","reduced",{timeout:60000});
  await page.getByRole("button",{name:"Close inspector"}).click();
  await expect.poll(()=>canvas.getAttribute("data-wildlife-pose")).not.toBe("");
  const frozen=await canvas.getAttribute("data-wildlife-pose");update(721);
  await page.waitForTimeout(900);expect(await canvas.getAttribute("data-wildlife-pose")).toBe(frozen);
  await page.emulateMedia({reducedMotion:"no-preference"});update(722);
  await expect(canvas).toHaveAttribute("data-wildlife-motion","animated");
  const moving=await canvas.getAttribute("data-wildlife-pose");
  await expect.poll(()=>canvas.getAttribute("data-wildlife-pose")).not.toBe(moving);
  await page.emulateMedia({reducedMotion:"reduce"});
  await expect(canvas).toHaveAttribute("data-wildlife-pose",frozen!);
  await page.locator(".world-stage").screenshot({path:info.outputPath("wildlife-static.png")});
  update(723,"Rain");await expect(canvas).toHaveAttribute("data-wildlife-pose","");
  update(0);await expect(canvas).toHaveAttribute("data-wildlife-pose","");
  update(724);await expect(canvas).toHaveAttribute("data-wildlife-pose",frozen!);
});

test("planet return restores the same habitat plan and the live preview is read-only",async({page,request},info)=>{
  test.setTimeout(120000);await page.emulateMedia({reducedMotion:"reduce"});
  const response=await request.get("/api/v1/world");expect(response.ok()).toBe(true);
  const world:World=await response.json();expect(world.geography?.version).toBe(4);
  const errors:string[]=[],writes:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  page.on("request",request=>{if(!["GET","HEAD","OPTIONS"].includes(request.method()))writes.push(request.method()+" "+request.url());});
  // Real preview snapshot and WebSocket: no route mocks or account mutations.
  await page.goto("/");const canvas=page.locator(".world-stage canvas");
  await expect.poll(async()=>Number(await canvas.getAttribute("data-habitat-plants")),{timeout:60000}).toBeGreaterThan(2500);
  const count=await canvas.getAttribute("data-habitat-plants");
  await page.getByRole("button",{name:"View planet",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-map-scale","planet");
  await page.locator(".world-stage").screenshot({path:info.outputPath("live-planet.png")});
  await page.getByRole("button",{name:"Reset camera"}).click();
  await expect(canvas).toHaveAttribute("data-map-scale","island",{timeout:30000});
  await expect(canvas).toHaveAttribute("data-habitat-plants",count!);
  await page.getByRole("button",{name:"Browse places"}).click();
  await page.locator(".places-popover").getByRole("button",{name:"Fern Valley Rest",exact:true}).click();
  const place=world.buildings.find(b=>b.id==="fern-valley")!;
  await expect(canvas).toHaveAttribute("data-target-x",place.x.toFixed(3));
  await page.getByRole("button",{name:"Close inspector"}).click();
  await page.locator(".world-stage").screenshot({path:info.outputPath("live-forest.png")});
  expect(errors).toEqual([]);expect(writes).toEqual([]);
});
