import {test,expect,type Page} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {organicCityLayout} from "../lib/city-layout";
import {LANDSCAPES} from "../lib/landscapes";
import {scaledCoastline,OCEAN_LEVEL} from "../lib/river-outlet";
import {viewerEyeElevation} from "../components/ImmersiveWorld";
import type {World,Building} from "../lib/types";

function fixture():World {
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/organic-world.json"),"utf8").replace(/^\uFEFF/,""));
  world.geography={...organicCityLayout,version:4};
  world.minute=720;world.weather="Clear";world.temperature=25;
  for(const place of LANDSCAPES.viewpoints)if(!world.buildings.some(building=>building.id===place.id))world.buildings.push({
    ...place,type:"wilderness",capacity:100,social:true,restAllowed:true,objects:{},outdoorRadius:0,
    entrance:{side:"open",x:place.x,z:place.z},
  } as Building);
  return world;
}

async function mockWorld(page:Page) {
  let world=fixture();
  let sendWorld:(snapshot:World)=>void=()=>{throw new Error("Missing coastal review websocket");};
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Coastal surf review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/coastal-surf-review"}}));
  await page.routeWebSocket("**/ws/coastal-surf-review",socket=>{
    sendWorld=snapshot=>socket.send(JSON.stringify(snapshot));sendWorld(world);
  });
  return (weather:string)=>{
    // Publish another simulation minute: shared environmental motion correctly
    // holds once a stopped fixture has exhausted its one received interval.
    world={...world,version:world.version+1,minute:world.minute+1,weather};sendWorld(world);
  };
}

async function focusPlace(page:Page,id:string) {
  const place=LANDSCAPES.viewpoints.find(place=>place.id===id)!;
  await page.getByRole("button",{name:"Browse places",exact:true}).click();
  await page.locator(".places-popover").getByRole("button",{name:"All places",exact:true}).click();
  await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
  const canvas=page.locator(".world-stage > .world-canvas canvas");
  await expect(canvas).toHaveAttribute("data-target-x",place.x.toFixed(3));
  await expect(canvas).toHaveAttribute("data-target-z",place.z.toFixed(3));
  await page.getByRole("button",{name:"Close inspector",exact:true}).click();
}

for(const mobile of [false,true])test(`coastal surf renders at beaches and respects motion and weather on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(120000);
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];
  page.on("pageerror",error=>errors.push(error.message));
  page.on("console",message=>{if(message.type()==="error"&&/THREE|WebGL|shader/i.test(message.text()))errors.push(message.text());});
  const weather=await mockWorld(page);await page.goto("/");
  const canvas=page.locator(".world-stage > .world-canvas canvas");
  await expect(canvas).toHaveAttribute("data-coastal-surf","shore-break",{timeout:45000});
  await expect(canvas).toHaveAttribute("data-surf-weather","calm");
  await expect.poll(async()=>Number(await canvas.getAttribute("data-daylight"))).toBeGreaterThan(.99);
  await expect(canvas).toHaveAttribute("data-surf-time",/\d/);
  const still=await canvas.getAttribute("data-surf-time");
  await page.waitForTimeout(400);
  await expect(canvas).toHaveAttribute("data-surf-time",still!);
  await page.locator(".world-stage").screenshot({path:info.outputPath("coastal-island-overview.png")});

  await focusPlace(page,"moonwash-cove");
  const zoom=Number(await canvas.getAttribute("data-camera-zoom"));
  await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await expect.poll(async()=>Number(await canvas.getAttribute("data-camera-zoom"))).toBeGreaterThan(zoom*1.2);
  await page.locator(".world-stage").screenshot({path:info.outputPath("moonwash-cove-surf.png")});
  await page.emulateMedia({reducedMotion:"no-preference"});
  await expect.poll(async()=>Number(await canvas.getAttribute("data-surf-time"))).toBeGreaterThan(Number(still)+.1);

  weather("Rain");
  await expect(canvas).toHaveAttribute("data-surf-weather","rain");
  const rainTime=Number(await canvas.getAttribute("data-surf-time"));
  await expect.poll(async()=>Number(await canvas.getAttribute("data-surf-time"))).toBeGreaterThan(rainTime+.1);
  await page.locator(".world-stage").screenshot({path:info.outputPath("moonwash-cove-rain.png")});
  await page.emulateMedia({reducedMotion:"reduce"});
  // Let the matchMedia listener apply before testing that the waves stay still.
  await page.waitForTimeout(300);
  const paused=await canvas.getAttribute("data-surf-time");
  await page.waitForTimeout(400);
  await expect(canvas).toHaveAttribute("data-surf-time",paused!);

  weather("Clear");
  await expect(canvas).toHaveAttribute("data-surf-weather","calm");
  await focusPlace(page,"silverstream-estuary");
  await page.locator(".world-stage").screenshot({path:info.outputPath("silverstream-estuary-surf.png")});
  if(!mobile) {
    await page.getByRole("button",{name:"Enter the world",exact:true}).click();
    const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"}),firstPerson=dialog.locator("canvas");
    await expect(firstPerson).toHaveAttribute("data-controls-ready","true",{timeout:30000});
    await page.getByRole("button",{name:"World map",exact:true}).click();
    const place=LANDSCAPES.viewpoints.find(place=>place.id==="moonwash-cove")!;
    await page.getByRole("button",{name:new RegExp(`${place.name}.*Visit`)}).click();
    await expect(page.getByLabel("Your world position")).toHaveText(`${place.x.toFixed(1)}, ${place.z.toFixed(1)}`);
    const target=scaledCoastline(1.025).reduce((nearest,point)=>Math.hypot(point.x-place.x,point.z-place.z)<Math.hypot(nearest.x-place.x,nearest.z-place.z)?point:nearest);
    const yaw=Math.atan2(place.x-target.x,place.z-target.z),pitch=Math.atan2(OCEAN_LEVEL-viewerEyeElevation(place.x,place.z),Math.hypot(target.x-place.x,target.z-place.z));
    const box=(await firstPerson.boundingBox())!,x=box.x+box.width*.65,y=box.y+box.height*.45;
    await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x-yaw/.004,y-pitch/.004,{steps:10});await page.mouse.up();
    await expect(firstPerson).toHaveAttribute("data-coastal-surf","shore-break");
    await page.emulateMedia({reducedMotion:"no-preference"});
    const before=Number(await firstPerson.getAttribute("data-surf-time"));
    await expect.poll(async()=>Number(await firstPerson.getAttribute("data-surf-time"))).toBeGreaterThan(before+.4);
    await page.screenshot({path:info.outputPath("moonwash-cove-first-person.png")});
    await page.getByRole("button",{name:"Back to dashboard",exact:true}).click();
    await expect(canvas).toHaveAttribute("data-coastal-surf","shore-break");
  }
  await info.attach("browser-errors",{body:JSON.stringify(errors),contentType:"application/json"});
  expect(errors).toEqual([]);
});
