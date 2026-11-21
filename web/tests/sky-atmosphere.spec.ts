import {test,expect,type Page,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import sharp from "sharp";
import {MOONS,skyAt} from "../lib/sky";
import {ATMOSPHERE_LIMITS,mistPatches,mistStrength,precipitationFloor} from "../lib/atmosphere";
import {LANDSCAPES,LAKE,LAKE_SHORE,poolPoint} from "../lib/landscapes";
import {bridgeElevation,islandElevation,riverLevel} from "../lib/island";
import {organicCityLayout} from "../lib/city-layout";
import type {Building,World} from "../lib/types";

function fixture():World {
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  world.geography={...organicCityLayout,version:4};world.minute=0;world.weather="Clear";world.agents=[];
  for(const p of LANDSCAPES.viewpoints)if(!world.buildings.some(b=>b.id===p.id))world.buildings.push({...p,type:"wilderness",capacity:100,social:true,restAllowed:true,objects:{},outdoorRadius:0} as Building);
  return world;
}

test("two moons have deterministic continuous orbits, distinct cycles and solar phases",()=>{
  const night=skyAt(0,"Clear"),day=skyAt(720,"Clear");
  expect(night.moons.filter(m=>m.aboveHorizon)).toHaveLength(2);
  expect(night.stars).toBeGreaterThan(.8);expect(day.stars).toBe(0);
  expect(skyAt(0,"Rain").stars).toBe(0);
  expect(skyAt(360,"Clear").sunDirection[0]).toBeGreaterThan(.99); // +x is east in the atlas
  expect(skyAt(1080,"Clear").sunDirection[0]).toBeLessThan(-.99);
  for(const minute of [-1441,-1,0,359,360,720,1080,1439,1440,1441,33120,298080,10000000]) {
    const sky=skyAt(minute,"Clear");expect(sky).toEqual(skyAt(minute,"Clear"));
    expect(sky.moons.map(m=>m.direction)).toEqual(skyAt(minute,"Rain").moons.map(m=>m.direction));
    for(const moon of sky.moons) {
      expect(Math.hypot(...moon.direction)).toBeCloseTo(1,10);
      expect(moon.illuminated).toBeGreaterThanOrEqual(0);expect(moon.illuminated).toBeLessThanOrEqual(1);
      expect(moon.direction.every(Number.isFinite)).toBe(true);
    }
    for(const [i,moon] of sky.moons.entries())expect(Math.hypot(...moon.direction.map((v,j)=>v-skyAt(minute+.01,"Clear").moons[i].direction[j]))).toBeLessThan(.0001);
  }
  for(const [i,moon] of MOONS.entries()) {
    const fullCycle=skyAt(moon.periodDays*1440,"Clear").moons[i];
    fullCycle.direction.forEach((v,j)=>expect(v).toBeCloseTo(night.moons[i].direction[j],9));
    const phases=Array.from({length:moon.periodDays*4},(_,n)=>skyAt(n*360,"Clear").moons[i].illuminated);
    expect(Math.min(...phases)).toBeLessThan(.15);expect(Math.max(...phases)).toBeGreaterThan(.85);
  }
  expect(skyAt(1440,"Clear").moons[0].direction).not.toEqual(night.moons[0].direction);
  expect(night.moons[0].direction).not.toEqual(night.moons[1].direction);
});

test("moon contrast fades through dawn, stays absent after 08:00 and returns at dusk",()=>{
  const dawn=[300,330,360,390,420,450,480].map(minute=>skyAt(minute,"Clear").moonVisibility);
  expect(dawn[0]).toBe(1);expect(dawn.at(-1)).toBe(0);
  for(let i=1;i<dawn.length;i++)expect(dawn[i]).toBeLessThanOrEqual(dawn[i-1]);
  expect(skyAt(360,"Clear").moonVisibility).toBeGreaterThan(0);
  expect(skyAt(360,"Clear").moonVisibility).toBeLessThan(1);
  for(let minute=300;minute<=480;minute++) {
    expect(Math.abs(skyAt(minute,"Clear").moonVisibility-skyAt(minute-1,"Clear").moonVisibility)).toBeLessThan(.02);
    expect(skyAt(1440-minute,"Clear").moonVisibility).toBeCloseTo(skyAt(minute,"Clear").moonVisibility,10);
  }
  // Cover both phase cycles, including mornings when either moon is still above
  // the horizon. Overcast/rain must not bring an opaque daytime disc back.
  const aboveHorizon=[false,false];
  for(let day=0;day<MOONS[0].periodDays*MOONS[1].periodDays;day++)for(const weather of ["Clear","Overcast","Rain"]) {
    for(const time of [480,720,960]) {
      const sky=skyAt(day*1440+time,weather);
      expect(sky.moonVisibility).toBe(0);
      sky.moons.forEach((moon,i)=>{aboveHorizon[i] ||= moon.aboveHorizon;});
    }
    expect(skyAt(day*1440,weather).moonVisibility).toBe(1);
  }
  expect(aboveHorizon).toEqual([true,true]);
});

test("rain stops at roofs, elevated terrain, water and bridges; mist stays bounded and terrain anchored",()=>{
  const world=fixture(),patches=mistPatches();
  expect(patches).toEqual(mistPatches());expect(patches.length).toBeLessThanOrEqual(ATMOSPHERE_LIMITS.mist);
  expect(new Set(patches.map(p=>p.kind)).size).toBe(4);
  for(const p of patches)expect([p.x,p.y,p.z,p.width,p.height].every(Number.isFinite)).toBe(true);
  for(const p of patches.filter(p=>p.kind==="mountain"))expect(p.y).toBeGreaterThan(islandElevation(p.x,p.z));
  expect(precipitationFloor(LAKE.x,LAKE.z,world)).toBeGreaterThan(LAKE.level);
  for(const p of LAKE_SHORE)expect(precipitationFloor(LAKE.x+(p.x-LAKE.x)*.98,LAKE.z+(p.z-LAKE.z)*.98,world)).toBeGreaterThan(LAKE.level);
  for(let i=0;i<12;i++){const p=poolPoint(i*Math.PI/6,.8);expect(precipitationFloor(p.x,p.z,world)).toBeGreaterThan(riverLevel(LANDSCAPES.lakeRiverEnd+1));}
  for(const bridge of LANDSCAPES.bridges)expect(precipitationFloor(bridge.x,bridge.z,world)).toBeGreaterThan(bridgeElevation(bridge));
  for(const building of world.buildings.filter(b=>["house","apartment","office"].includes(b.type)))
    expect(precipitationFloor(building.x,building.z,world)).toBeGreaterThan(islandElevation(building.x,building.z)+3);
  expect(mistStrength("lake",360,"Clear")).toBeGreaterThan(mistStrength("lake",720,"Clear"));
  expect(mistStrength("mountain",720,"Rain")).toBeGreaterThan(mistStrength("mountain",720,"Clear"));
});

async function controlled(page:Page) {
  let world=fixture(),socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Sky review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/sky-review"}}));
  await page.routeWebSocket("**/ws/sky-review",route=>{socket=route;route.send(JSON.stringify(world));});
  return (minute:number,weather="Clear")=>{world={...world,minute,weather,version:world.version+1};socket?.send(JSON.stringify(world));};
}
const signatures=async(page:Page,selector=".world-stage canvas")=>page.locator(selector).evaluate(canvas=>{
  const d=(canvas as HTMLElement).dataset;return [d.skyMinute,d.sunDirection,d.moonSelene,d.moonEmber,d.moonPhases,d.moonVisibility];
});

test("08:00 sky pixels do not retain either moon disc across orbital phases",async({page},info)=>{
  test.setTimeout(120000);await page.setViewportSize({width:1440,height:960});await page.emulateMedia({reducedMotion:"reduce"});
  const update=await controlled(page);update(480);await page.goto("/");
  await expect(page.locator(".world-stage canvas")).toHaveAttribute("data-sky-minute","480",{timeout:60000});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const canvas=page.getByRole("dialog").locator("canvas");await expect(canvas).toHaveAttribute("data-controls-ready","true",{timeout:60000});
  await page.getByRole("button",{name:"World map",exact:true}).click();await page.getByRole("button",{name:/Moonwash Cove.*Visit/}).click();
  const sun=skyAt(480,"Clear").sunDirection;
  const angles=(direction:number[])=>({yaw:Math.atan2(-direction[0],-direction[2]),pitch:Math.asin(direction[1])});
  const origin=angles(sun),bounds=(await canvas.boundingBox())!;
  const center={x:bounds.x+bounds.width/2,y:bounds.y+bounds.height/2};
  const crop={left:Math.round(center.x)-45,top:Math.round(center.y)-45,width:90,height:90};
  // Each chosen phase puts a different moon high in the morning sky. Compare
  // its pixels with the same view at the same time on day zero, when both are
  // below the horizon. Fixed clouds, daylight and camera make a disc the only
  // possible sky difference, catching a missing shader uniform or opacity fade.
  for(const [index,day] of [[0,11],[1,5]]) {
    const minute=day*1440+480,moon=skyAt(minute,"Clear").moons[index],target=angles(moon.direction);
    expect(moon.aboveHorizon).toBe(true);expect(target.pitch).toBeLessThan(1.2);
    await page.getByRole("button",{name:"Look at sky",exact:true}).click();
    const yawDelta=Math.atan2(Math.sin(origin.yaw-target.yaw),Math.cos(origin.yaw-target.yaw));
    await page.mouse.move(center.x,center.y);await page.mouse.down();
    await page.mouse.move(center.x+yawDelta/.004,center.y+(origin.pitch-target.pitch)/.004,{steps:6});await page.mouse.up();
    update(minute);await expect(canvas).toHaveAttribute("data-sky-minute",String(minute));await expect(canvas).toHaveAttribute("data-moon-visibility","0.000");
    const withMoon=await sharp(await page.screenshot({path:info.outputPath(`${moon.id}-daylight.png`)})).extract(crop).removeAlpha().raw().toBuffer();
    update(480);await expect(canvas).toHaveAttribute("data-sky-minute","480");
    const withoutMoon=await sharp(await page.screenshot()).extract(crop).removeAlpha().raw().toBuffer();
    const averageDifference=withMoon.reduce((sum,value,i)=>sum+Math.abs(value-withoutMoon[i]),0)/withMoon.length;
    expect(averageDifference).toBeLessThan(1);
  }
});

for(const mobile of [false,true])test(`sun, two moons and terrain weather remain usable on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(240000);await page.setViewportSize(mobile?{width:390,height:844}:{width:1440,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));page.on("console",m=>{if(m.type()==="error"&&/THREE|shader|WebGL/i.test(m.text()))errors.push(m.text());});
  const update=await controlled(page);await page.goto("/");
  const map=page.locator(".world-stage canvas");await expect(map).toHaveAttribute("data-moons-above-horizon","2",{timeout:60000});
  const initial=await signatures(page);await page.locator(".world-stage").screenshot({path:info.outputPath("night-island.png")});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const canvas=page.getByRole("dialog").locator("canvas");await expect(canvas).toHaveAttribute("data-controls-ready","true",{timeout:60000});
  await expect(canvas).toHaveAttribute("data-sky-minute","0");expect(await signatures(page,"dialog canvas")).toEqual(initial);
  await page.getByRole("button",{name:"World map",exact:true}).click();
  await page.getByRole("button",{name:/Moonwash Cove.*Visit/}).click();
  await page.getByRole("button",{name:"Look at sky",exact:true}).click();
  const nightImage=await page.screenshot({path:info.outputPath("two-moons-and-stars.png")});
  // Inspect actual sky pixels away from both discs and HUD. Uniform telemetry
  // alone cannot catch a material that fails to upload scalar shader values.
  const viewport=page.viewportSize()!;
  const {data,info:pixels}=await sharp(nightImage).extract({left:Math.floor(viewport.width*.04),top:Math.floor(viewport.height*.18),width:Math.floor(viewport.width*.24),height:Math.floor(viewport.height*.40)}).raw().toBuffer({resolveWithObject:true});
  let starPixels=0;for(let i=0;i<data.length;i+=pixels.channels)if(data[i]>60)starPixels++;
  expect(starPixels).toBeGreaterThan(mobile?1:15);
  for(const [minute,weather,name] of [[390,"Clear","sunrise"],[480,"Clear","morning"],[720,"Clear","sun"],[1050,"Clear","sunset"],[360,"Overcast","dawn-overcast"]] as const) {
    update(minute,weather);await expect(canvas).toHaveAttribute("data-sky-minute",String(minute));
    await expect(canvas).toHaveAttribute("data-moon-visibility",skyAt(minute,weather).moonVisibility.toFixed(3));
    await page.getByRole("button",{name:"Look at sky",exact:true}).click();
    await expect.poll(()=>canvas.getAttribute("data-weather")).toBe(weather);
    await page.screenshot({path:info.outputPath(`${name}.png`)});
  }
  update(365,"Rain");await expect(canvas).toHaveAttribute("data-rain-count",String(mobile?200:420));
  const coastCell=await canvas.getAttribute("data-rain-cell");
  await page.getByRole("button",{name:"World map",exact:true}).click();
  await page.getByRole("button",{name:/Cloudmere Lakeside.*Visit/}).click();
  await expect.poll(()=>canvas.getAttribute("data-rain-cell")).not.toBe(coastCell);
  await expect(canvas).toHaveAttribute("data-atmosphere-motion","reduced");
  const cell=await canvas.getAttribute("data-rain-cell");
  await expect.poll(()=>canvas.getAttribute("data-rain-cell")).toBe(cell);
  const frozen=await canvas.getAttribute("data-rain-pose");update(366,"Rain");
  await expect(canvas).toHaveAttribute("data-sky-minute","366");await page.waitForTimeout(500);
  expect(await canvas.getAttribute("data-rain-pose")).toBe(frozen);
  await expect.poll(async()=>Number(await canvas.getAttribute("data-road-roughness"))).toBeLessThan(.46);
  await page.screenshot({path:info.outputPath("rain-at-cloudmere.png")});
  await page.emulateMedia({reducedMotion:"no-preference"});await expect(canvas).toHaveAttribute("data-atmosphere-motion","animated");
  await expect.poll(()=>canvas.getAttribute("data-rain-pose")).not.toBe(frozen);
  await page.emulateMedia({reducedMotion:"reduce"});await expect(canvas).toHaveAttribute("data-rain-pose",frozen!);
  await page.getByRole("button",{name:"World map",exact:true}).click();await page.getByRole("button",{name:/Moonwash Cove.*Visit/}).click();
  await expect.poll(()=>canvas.getAttribute("data-rain-cell")).not.toBe(cell);
  update(720);await expect(canvas).toHaveAttribute("data-rain-count","0");await expect.poll(async()=>Number(await canvas.getAttribute("data-road-roughness"))).toBeGreaterThan(.99);
  await page.getByRole("button",{name:"Back to dashboard"}).click();await expect(map).toHaveAttribute("data-sky-minute","720");
  await page.getByRole("button",{name:"View planet",exact:true}).click();await expect(map).toHaveAttribute("data-map-scale","planet");
  await page.getByRole("button",{name:"Reset camera"}).click();await expect(map).toHaveAttribute("data-map-scale","island",{timeout:30000});
  const beforeReload=await signatures(page);await page.reload();await expect(map).toHaveAttribute("data-sky-minute","720",{timeout:60000});
  expect(await signatures(page)).toEqual(beforeReload);expect(errors).toEqual([]);
});

test("independent observers see the same received-time sky",async({page,context})=>{
  test.setTimeout(120000);const other=await context.newPage();
  const first=await controlled(page),second=await controlled(other);first(98341,"Overcast");second(98341,"Overcast");
  await page.goto("/");await expect(page.locator("canvas")).toHaveAttribute("data-sky-minute","98341",{timeout:60000});
  await other.goto("/");await expect(other.locator("canvas")).toHaveAttribute("data-sky-minute","98341",{timeout:60000});
  expect(await signatures(page)).toEqual(await signatures(other));await other.close();
});
