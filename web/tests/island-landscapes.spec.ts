import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {LAKE,LAKE_SHORE,LANDSCAPES,HIGHLAND_TRAILS} from "../lib/landscapes";
import {RIVER,nearestRiver} from "../lib/river";
import {dryLand} from "../lib/organic-layout";
import {islandElevation,walkElevation,riverLevel} from "../lib/island";
import {walkSegmentClear} from "../components/ImmersiveWorld";
import {organicCityLayout} from "../lib/city-layout";
import type {World,Building} from "../lib/types";
import * as THREE from "three";
import {createRiverSurfaces,createPlungePool,riverSample} from "../lib/river-geometry";
import {FALLS,poolPoint} from "../lib/landscapes";
import {atInlet,INLET_IMPACT,mistParticle,sprayParticle,waterfallCliffGeometry,SPRAY_COUNT,MIST_COUNT} from "../lib/waterfall";
import {terrainGeometry} from "../components/IslandLandscape";

test("rendered water rests on a continuous rock bed and joins a submerged plunge basin",()=>{
  const river=createRiverSurfaces(),pool=createPlungePool(),material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide});
  const mesh=(geometry:THREE.BufferGeometry)=>new THREE.Mesh(geometry,material);
  const water=mesh(river.water),bed=mesh(river.bed),poolWater=mesh(pool.water),basin=mesh(pool.basin);
  const ray=new THREE.Raycaster();
  const height=(object:THREE.Mesh,x:number,z:number)=>{
    ray.set(new THREE.Vector3(x,150,z),new THREE.Vector3(0,-1,0));
    const hit=ray.intersectObject(object)[0];expect(hit,`Missing ${object===water?"water":"rock"} at ${x}, ${z}`).toBeDefined();return hit.point.y;
  };
  try {
    // Check actual triangles, not only the height function: the old coarse
    // terrain left the inlet hanging more than ten units above its ground.
    for(const index of [16,17,18,19,28,29,30,31])for(let step=1;step<10;step++)for(const across of [-.7,0,.7]) {
      const fraction=index===28?.481+step*.0037:step/10,p=riverSample(index,fraction),x=p.x+p.sx*p.width/2*across,z=p.z+p.sz*p.width/2*across;
      const gap=height(water,x,z)-height(bed,x,z);
      expect(gap,`Unsupported river at ${index}+${fraction}`).toBeGreaterThan(.02);
      expect(gap,`Unsupported river at ${index}+${fraction}`).toBeLessThan(1.2);
    }
    const base=riverLevel(LANDSCAPES.lakeRiverEnd+1);
    for(const along of [.05,.8,2.4])for(const across of [-.7,0,.7]) {
      const x=FALLS.x+FALLS.dx*along+FALLS.dz*across,z=FALLS.z+FALLS.dz*along-FALLS.dx*across;
      const surface=height(poolWater,x,z),ground=Math.max(height(basin,x,z),height(bed,x,z));
      expect(surface-ground).toBeGreaterThan(.01);expect(surface-ground).toBeLessThan(1.6);
      expect(surface).toBeCloseTo(base+.045,3);
    }
    // New water fits inside the existing server-authoritative cliff exclusion.
    for(let i=0;i<64;i++)expect(dryLand(poolPoint(i/64*Math.PI*2))).toBe(false);
  } finally {Object.values(river).forEach(g=>g.dispose());Object.values(pool).forEach(g=>g.dispose());material.dispose();}
});

test("the rendered cliff supports the falling sheet without terrain obscuring it",()=>{
  const river=createRiverSurfaces(),cliff=waterfallCliffGeometry(),terrain=terrainGeometry();
  const material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide}),water=new THREE.Mesh(river.water,material),ground=new THREE.Mesh(terrain,material),rock=new THREE.Mesh(cliff,material);
  const lookout=LANDSCAPES.viewpoints.find(place=>place.id==="silverstream-falls")!;
  const eye=new THREE.Vector3(lookout.x,walkElevation(lookout.x,lookout.z)+1.65,lookout.z),ray=new THREE.Raycaster();
  try {
    for(const index of [17,18,19])for(const fraction of [.1,.4,.7,.9]) {
      const point=riverSample(index,fraction);ray.set(new THREE.Vector3(point.x,150,point.z),new THREE.Vector3(0,-1,0));
      const sheet=ray.intersectObject(water)[0],groundHit=ray.intersectObject(ground)[0];
      expect(sheet).toBeDefined();if(groundHit)expect(groundHit.point.y,`Terrain covers inlet ${index}+${fraction}`).toBeLessThan(sheet.point.y-.01);
    }
    for(const fraction of [.487,.5,.513]) {
      const target=riverSample(LANDSCAPES.lakeRiverEnd,fraction),point=new THREE.Vector3(target.x,target.y,target.z);
      ray.set(eye,point.sub(eye).normalize());
      const sheet=ray.intersectObject(water)[0];expect(sheet).toBeDefined();
      for(const surface of [ground,rock]) {
        const hit=ray.intersectObject(surface)[0];
        if(hit)expect(hit.distance,`Waterfall hidden by ${surface===rock?"rock":"terrain"}`).toBeGreaterThan(sheet.distance-.04);
      }
      const backing=ray.intersectObject(rock)[0];expect(backing).toBeDefined();
      expect(backing.distance-sheet.distance).toBeLessThan(2);
    }
  } finally {Object.values(river).forEach(g=>g.dispose());cliff.dispose();terrain.dispose();material.dispose();}
});

test("impact droplets rise and fall while mist disperses above the pool",()=>{
  const base=riverLevel(LANDSCAPES.lakeRiverEnd+1),sprayHeights:number[]=[],mistHeights:number[]=[];
  for(let frame=0;frame<20;frame++) {
    for(let i=0;i<SPRAY_COUNT;i++) {
      const point=sprayParticle(i,frame*.1);sprayHeights.push(point.y-base);
      expect(point.y).toBeGreaterThan(base);expect(Math.hypot(point.x-FALLS.x,point.z-FALLS.z)).toBeLessThan(5.6);
    }
    for(let i=0;i<MIST_COUNT;i++) {
      const point=mistParticle(i,frame*.1),inlet=atInlet(point);mistHeights.push(point.y-base);
      expect(inlet.y).toBeGreaterThan(LAKE.level);expect(inlet.y-INLET_IMPACT.y).toBeLessThan(2.7);
    }
  }
  expect(Math.max(...sprayHeights)).toBeGreaterThan(1.2);
  expect(Math.min(...sprayHeights)).toBeLessThan(.2);
  expect(Math.max(...mistHeights)).toBeGreaterThan(3);
  expect(Math.max(...mistHeights)).toBeLessThan(4.5);
});

test("shared specification and authoritative survey agree on water, cliffs and trail",()=>{
  const server=JSON.parse(fs.readFileSync(path.resolve("../backend/src/main/resources/city/island-landscapes-v1.json"),"utf8"));
  expect(LANDSCAPES).toEqual(server);
  const surveyPath=path.resolve("tests/fixtures/landscape-survey.json");
  const survey:{x:number;z:number;dry:boolean}[]=JSON.parse(fs.readFileSync(surveyPath,"utf8"));
  for(const point of survey)expect(dryLand(point),`${point.x},${point.z}`).toBe(point.dry);
  for(const trail of HIGHLAND_TRAILS)for(let segment=1;segment<trail.length;segment++)expect(walkSegmentClear(trail[segment-1],trail[segment],[],organicCityLayout)).toBe(true);
  expect(dryLand(LAKE)).toBe(false);
  expect(walkSegmentClear({x:-166,z:-400},{x:-145,z:-377},[],organicCityLayout)).toBe(false);
  expect(islandElevation(LAKE.x,LAKE.z)).toBeLessThan(LAKE.level);
  for(const point of LAKE_SHORE) {
    const river=nearestRiver(point.x,point.z);
    // Inlet and outlet intentionally cut through the otherwise continuous rim.
    if(river.distance>river.width/2+4)expect(islandElevation(point.x,point.z)).toBeGreaterThanOrEqual(LAKE.level-.01);
  }
  for(let i=1;i<RIVER.length;i++)expect(riverLevel(i)).toBeLessThanOrEqual(riverLevel(i-1));
  const index=LANDSCAPES.lakeRiverEnd;
  expect(riverLevel(index,.48)-riverLevel(index,.52)).toBeGreaterThan(20);
  // The lookout must reveal the falls rather than face an intervening hillside.
  const lookout=LANDSCAPES.viewpoints.find(place=>place.id==="silverstream-falls")!;
  const a=RIVER[index],b=RIVER[index+1],fall={x:(a.x+b.x)/2,z:(a.z+b.z)/2,y:riverLevel(index,.5)};
  const eye=walkElevation(lookout.x,lookout.z)+1.65;
  for(let i=1;i<40;i++) {
    const t=i/40,x=lookout.x+(fall.x-lookout.x)*t,z=lookout.z+(fall.z-lookout.z)*t;
    expect(islandElevation(x,z),`Lookout sightline at ${i}`).toBeLessThan(eye+(fall.y-eye)*t);
  }
});

function fixture():World {
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/organic-world.json"),"utf8").replace(/^\uFEFF/,""));
  world.geography={...organicCityLayout,version:4};
  for(const place of LANDSCAPES.viewpoints)world.buildings.push({...place,type:"wilderness",district:"Highlands",capacity:100,social:true,restAllowed:true,objects:{},outdoorRadius:0,entrance:{side:"open",x:place.x,z:place.z}} as Building);
  return world;
}
for(const mobile of [false,true])test(`connected highland landscape is explorable on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(120000);
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const world=fixture(),errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  page.on("console",message=>{if(message.type()==="error"&&/THREE|WebGL|shader/i.test(message.text()))errors.push(message.text());});
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Preview"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/landscape-preview"}}));
  await page.routeWebSocket("**/ws/landscape-preview",socket=>socket.send(JSON.stringify(world)));
  await page.goto("/");const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-landscape-version",String(LANDSCAPES.version),{timeout:45000});
  expect(Number(await canvas.getAttribute("data-waterfall-drop"))).toBeGreaterThan(20);
  await page.locator(".world-stage").screenshot({path:info.outputPath("highland-island.png")});
  const still=await canvas.getAttribute("data-waterfall-flow");
  await page.waitForTimeout(400);expect(await canvas.getAttribute("data-waterfall-flow")).toBe(still);
  await page.emulateMedia({reducedMotion:"no-preference"});
  await expect.poll(()=>canvas.getAttribute("data-waterfall-flow")).not.toBe(still);
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});
  await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:30000});
  const settle=async()=>{
    const canvas=dialog.locator("canvas"),frame=await canvas.getAttribute("data-waterfall-flow");
    await expect.poll(()=>canvas.getAttribute("data-waterfall-flow"),{intervals:[100],timeout:10000}).not.toBe(frame);
  };
  for(const place of LANDSCAPES.viewpoints.filter(place=>["cloudmere-view","silverstream-falls"].includes(place.id))) {
    await page.getByRole("button",{name:"World map",exact:true}).click();
    await expect(dialog.locator("svg path[fill='#347e92']")).toHaveCount(1);
    await page.getByRole("button",{name:new RegExp(`${place.name}.*Visit`)}).click();
    await expect(page.getByLabel("Your world position")).toHaveText(`${place.x.toFixed(1)}, ${place.z.toFixed(1)}`);
    const box=await dialog.locator("canvas").boundingBox();if(!box)throw new Error("Missing first-person canvas");
    const x=box.x+box.width*.2,y=box.y+box.height*.5;
    await page.mouse.move(x,y);await page.mouse.down();
    const turn=place.id==="cloudmere-view"?Math.PI/.008:-(1.08+Math.PI/2)/.004;
    await page.mouse.move(x+turn,y+(place.id==="cloudmere-view"?90:-250),{steps:8});await page.mouse.up();
    await settle();
    await page.screenshot({path:info.outputPath(`${place.id}.png`)});
    if(place.id==="cloudmere-view"&&!mobile) {
      // Inspect the steep inlet from the exact lakeside destination as well as
      // the wider lake: this is the unsupported water in the reported image.
      const target=riverSample(19,.5),yaw=Math.atan2(place.x-target.x,place.z-target.z),pitch=Math.atan2(target.y-walkElevation(place.x,place.z)-1.65,Math.hypot(target.x-place.x,target.z-place.z));
      const dx=(-Math.PI/2-yaw)/.004,dy=(-.36-pitch)/.004;
      await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x+dx,y+dy,{steps:8});await page.mouse.up();await settle();
      await page.screenshot({path:info.outputPath("cloudmere-inlet.png")});
      await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x-dx,y-dy,{steps:8});await page.mouse.up();await settle();
    }
    const before=await page.getByLabel("Your world position").textContent();
    await page.keyboard.down("s");await expect(page.getByLabel("Your world position")).not.toHaveText(before!);await page.keyboard.up("s");
  }
  await page.getByRole("button",{name:"Back to dashboard"}).click();
  if(!mobile)for(const place of LANDSCAPES.viewpoints.filter(place=>["cloudmere-view","silverstream-falls"].includes(place.id))) {
    // The normal orbit view reveals the bed and receiving water from above,
    // matching the perspective in the reported floating-water screenshot.
    await page.getByRole("button",{name:"Browse places",exact:true}).click();
    await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
    const canvas=page.locator(".world-stage canvas");
    await expect(canvas).toHaveAttribute("data-target-x",place.x.toFixed(3));
    await expect(canvas).toHaveAttribute("data-target-z",place.z.toFixed(3));
    const zoom=Number(await canvas.getAttribute("data-camera-zoom"));
    if(place.id==="cloudmere-view")for(let i=0;i<2;i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
    if(place.id==="cloudmere-view")await expect.poll(async()=>Number(await canvas.getAttribute("data-camera-zoom"))).toBeGreaterThan(zoom*1.55);
    await page.getByRole("button",{name:"Close inspector",exact:true}).click();
    const focus=place.id==="cloudmere-view"?riverSample(19,.5):riverSample(LANDSCAPES.lakeRiverEnd,.5);
    const offset=new THREE.Vector3(1800,1550,1950),right=new THREE.Vector3(offset.z,0,-offset.x).normalize(),up=offset.clone().cross(right).normalize();
    const delta=new THREE.Vector3(focus.x-place.x,focus.y-islandElevation(place.x,place.z),focus.z-place.z),currentZoom=Number(await canvas.getAttribute("data-camera-zoom"));
    const box=(await canvas.boundingBox())!,x=box.x+box.width/2,y=box.y+box.height/2;
    // Orbit's ground-plane pan projects vertical dragging by cos(polar angle).
    await page.mouse.move(x,y);await page.mouse.down();
    await page.mouse.move(x-delta.dot(right)*currentZoom,y+delta.dot(up)*currentZoom/(offset.y/offset.length()),{steps:12});await page.mouse.up();
    const frame=await canvas.getAttribute("data-waterfall-flow");await expect.poll(()=>canvas.getAttribute("data-waterfall-flow"),{intervals:[100]}).not.toBe(frame);
    await page.locator(".world-stage").screenshot({path:info.outputPath(place.id==="cloudmere-view"?"cloudmere-inlet-grounding.png":"waterfall-pool-and-spray.png")});
  }
  expect(errors).toEqual([]);
});
