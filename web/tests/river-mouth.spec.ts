import {test,expect,type Page} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import * as THREE from "three";
import {LANDSCAPES} from "../lib/landscapes";
import {RIVER} from "../lib/river";
import {RIVER_MOUTH,createRiverSurfaces,createRiverWalkGeometry,riverSample} from "../lib/river-geometry";
import {RIVER_OUTLET_END,OCEAN_LEVEL,RIVER_COURSE} from "../lib/river-outlet";
import {createIslandSurfaces} from "../lib/island-surface-geometry";
import {createCoastalGeometry} from "../lib/landscape-geometry";
import {terrainGeometry} from "../components/IslandLandscape";
import {onIsland,riverLevel} from "../lib/island";
import {organicCityLayout} from "../lib/city-layout";
import type {World,Building} from "../lib/types";

const focus={...RIVER[RIVER_MOUTH],id:"river-mouth-review",name:"River mouth review"};

function surfaceIndex(geometry:THREE.BufferGeometry,material:THREE.Material) {
  const position=geometry.getAttribute("position"),indices=geometry.getIndex();
  const ray=new THREE.Raycaster(),meshes=new Map<string,THREE.Mesh>(),size=32;
  const height=(mesh:THREE.Mesh|undefined,x:number,z:number)=>{
    if(!mesh)return undefined;
    ray.set(new THREE.Vector3(x,160,z),new THREE.Vector3(0,-1,0));return ray.intersectObject(mesh)[0]?.point.y;
  };
  // Small polygon/water meshes are cheaper to query directly. In particular,
  // the open-ocean plane must not allocate one tile for every offshore square.
  if(!indices||indices.count<24000) {
    const mesh=new THREE.Mesh(geometry,material);
    return {height:(x:number,z:number)=>height(mesh,x,z),dispose:()=>{}};
  }
  const tiles=new Map<string,number[]>();
  for(let triangle=0;triangle<indices.count;triangle+=3) {
    const vertices=[indices.getX(triangle),indices.getX(triangle+1),indices.getX(triangle+2)],xs=vertices.map(vertex=>position.getX(vertex)),zs=vertices.map(vertex=>position.getZ(vertex));
    for(let x=Math.floor(Math.min(...xs)/size);x<=Math.floor(Math.max(...xs)/size);x++)for(let z=Math.floor(Math.min(...zs)/size);z<=Math.floor(Math.max(...zs)/size);z++) {
      const key=`${x},${z}`,group=tiles.get(key)??[];group.push(...vertices);tiles.set(key,group);
    }
  }
  for(const [key,indices] of tiles) {
    const tile=new THREE.BufferGeometry(),box=new THREE.Box3(),point=new THREE.Vector3();tile.setAttribute("position",position);tile.setIndex(indices);
    for(const index of indices)box.expandByPoint(point.set(position.getX(index),position.getY(index),position.getZ(index)));
    tile.boundingBox=box;tile.boundingSphere=box.getBoundingSphere(new THREE.Sphere());meshes.set(key,new THREE.Mesh(tile,material));
  }
  return {height:(x:number,z:number)=>height(meshes.get(`${Math.floor(x/size)},${Math.floor(z/size)}`),x,z),dispose:()=>{for(const mesh of meshes.values())mesh.geometry.dispose();}};
}

function mouthScene() {
  const material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide}),island=createIslandSurfaces(),river=createRiverSurfaces();
  const solidGeometries={land:island.land,shore:island.shore,foam:island.foam,terrain:terrainGeometry(),coast:createCoastalGeometry(),walk:createRiverWalkGeometry(),bed:river.bed};
  const waterGeometries={river:river.water,ocean:island.ocean,sea:island.sea,shelf:island.shelf};
  const index=(geometries:Record<string,THREE.BufferGeometry>)=>Object.fromEntries(Object.entries(geometries).map(([name,geometry])=>[name,surfaceIndex(geometry,material)]));
  const solids=index(solidGeometries),waters=index(waterGeometries);
  return {solids,waters,dispose:()=>{
    [...Object.values(solids),...Object.values(waters)].forEach(surface=>surface.dispose());
    [...Object.values(solidGeometries),...Object.values(waterGeometries)].forEach(geometry=>geometry.dispose());material.dispose();
  }};
}

function courseProbes(first:number,last:number,spacing=.5) {
  const points:ReturnType<typeof riverSample>[]=[];
  for(let index=first;index<last;index++) {
    const a=RIVER[index],b=RIVER[index+1],steps=Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/spacing);
    for(let step=0;step<steps;step++)points.push(riverSample(index,(step+.37)/steps));
  }
  return points;
}

test("the actual river mouth stays open through every rendered land and shore layer",async({},info)=>{
  test.setTimeout(90000);
  const scene=mouthScene(),survey:unknown[]=[];
  try {
    const probes=courseProbes(RIVER_MOUTH-4,RIVER_OUTLET_END);
    expect(probes.length).toBeGreaterThan(50);
    for(const point of probes)for(const across of [-.65,0,.65]) {
      const x=point.x+point.sx*point.width/2*across,z=point.z+point.sz*point.width/2*across;
      const waterHeights=Object.fromEntries(Object.entries(scene.waters).map(([name,mesh])=>[name,mesh.height(x,z)])),visible=Math.max(...Object.values(waterHeights).filter((value):value is number=>value!==undefined));
      expect(Number.isFinite(visible),`No receiving water at ${point.index}+${point.fraction}, across ${across}`).toBe(true);
      const solidHeights=Object.fromEntries(Object.entries(scene.solids).map(([name,mesh])=>[name,mesh.height(x,z)]));
      survey.push({index:point.index,fraction:point.fraction,across,x,z,waterHeights,solidHeights});
      for(const [name,height] of Object.entries(solidHeights))if(height!==undefined)expect(height,`${name} dams or hides the actual mouth at ${point.index}+${point.fraction}, across ${across}`).toBeLessThan(visible-.01);
    }
  } finally {
    await info.attach("mouth-layer-survey",{body:JSON.stringify(survey,null,2),contentType:"application/json"});scene.dispose();
  }
});

test("the extended river overlaps open ocean at one continuous receiving height",()=>{
  test.setTimeout(90000);
  const scene=mouthScene();
  try {
    const probes=courseProbes(RIVER_MOUTH-2,RIVER_OUTLET_END,.25);let previous:number|undefined;
    for(const point of probes) {
      const height=scene.waters.river.height(point.x,point.z);
      expect(height,`A gap interrupts the river extension at ${point.index}+${point.fraction}`).toBeDefined();
      if(onIsland(point))expect(height!).toBeCloseTo(riverLevel(point.index,point.fraction),4);
      if(previous!==undefined){expect(height!).toBeLessThanOrEqual(previous+.002);expect(Math.abs(height!-previous)).toBeLessThan(.2);}
      previous=height;
    }
    const end=riverSample(RIVER_OUTLET_END-1,.97);
    expect(scene.waters.shelf.height(end.x,end.z),"The river stops within the shelf instead of reaching open ocean").toBeUndefined();
    const river=scene.waters.river.height(end.x,end.z),sea=scene.waters.sea.height(end.x,end.z),ocean=scene.waters.ocean.height(end.x,end.z);
    expect(river).toBeDefined();expect(ocean).toBeDefined();expect(river!).toBeCloseTo(ocean!,3);
    for(const height of [river,sea,ocean])if(height!==undefined)expect(height).toBeCloseTo(OCEAN_LEVEL,3);
    // Continue beyond the rendered river so a closed end or a mismatched
    // ocean patch cannot pass merely by covering the last inland sample.
    for(const point of courseProbes(RIVER_OUTLET_END,Math.min(RIVER.length-1,RIVER_OUTLET_END+5))) {
      for(const name of ["ocean","sea","shelf"] as const) {
        const height=scene.waters[name].height(point.x,point.z);
        if(height!==undefined)expect(height).toBeCloseTo(OCEAN_LEVEL,4);
      }
      expect(scene.waters.ocean.height(point.x,point.z)).toBeDefined();
    }
    expect(RIVER_COURSE.length).toBeGreaterThan(RIVER_MOUTH+2);
  } finally {scene.dispose();}
});

test("the offshore bed and bank triangles taper below receiving water without an outlet wall",()=>{
  test.setTimeout(90000);
  const scene=mouthScene();let reviewed=0;
  try {
    for(const point of courseProbes(RIVER_MOUTH,RIVER_OUTLET_END))for(const side of [-1,1]) {
      const offset=side*(point.width/2+1.5),x=point.x+point.sx*offset,z=point.z+point.sz*offset;
      if(onIsland({x,z}))continue;
      // Only open-water bank footprints are submerged; an adjacent actual
      // beach still permits exposed dry bank terrain beside the outlet.
      const dry=Object.entries(scene.solids).some(([name,surface])=>name!=="bed"&&(surface.height(x,z)??-Infinity)>OCEAN_LEVEL+.01);
      if(dry)continue;
      reviewed++;
      const bank=scene.solids.bed.height(x,z);
      if(bank!==undefined)expect(bank,`Raised offshore bank at ${point.index}+${point.fraction}, side ${side}`).toBeLessThan(OCEAN_LEVEL-.01);
    }
    expect(reviewed,"No actual offshore bank footprints were reviewed").toBeGreaterThan(10);
  } finally {scene.dispose();}
});

function fixture():World {
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/organic-world.json"),"utf8").replace(/^\uFEFF/,""));
  world.geography={...organicCityLayout,version:4};
  for(const place of LANDSCAPES.viewpoints)if(!world.buildings.some(building=>building.id===place.id))world.buildings.push({
    ...place,type:"wilderness",capacity:100,social:true,restAllowed:true,objects:{},outdoorRadius:0,
    entrance:{side:"open",x:place.x,z:place.z},
  } as Building);
  // This inspection-only marker focuses the normal orbit camera on the actual
  // water outlet. It is never used as a navigable dry destination.
  world.buildings.push({...focus,type:"wilderness",district:"Geometry review",capacity:0,social:false,restAllowed:false,objects:{},outdoorRadius:0} as Building);
  return world;
}

async function mockWorld(page:Page) {
  let world=fixture();
  let sendWorld:(snapshot:World)=>void=()=>{throw new Error("Missing review websocket");};
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"River mouth review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/river-mouth-review"}}));
  await page.routeWebSocket("**/ws/river-mouth-review",socket=>{
    sendWorld=snapshot=>socket.send(JSON.stringify(snapshot));sendWorld(world);
  });
  return ()=>{
    // Polls must keep returning the filtered snapshot instead of restoring
    // the inspection marker while the close-up camera is being positioned.
    world={...world,version:world.version+1,buildings:world.buildings.filter(building=>building.id!==focus.id)};
    sendWorld(world);
  };
}

for(const mobile of [false,true])test(`river mouth rendered layers are reviewable up close on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(120000);
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];
  page.on("pageerror",error=>errors.push(error.message));
  page.on("console",message=>{if(message.type()==="error"&&/THREE|WebGL|shader/i.test(message.text()))errors.push(message.text());});
  const removeInspectionMarker=await mockWorld(page);await page.goto("/");
  const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-river-course","mountain-to-sea",{timeout:45000});
  await page.getByRole("button",{name:"Browse places",exact:true}).click();
  await page.locator(".places-popover").getByRole("button",{name:"All places",exact:true}).click();
  await page.locator(".places-popover").getByRole("button",{name:focus.name,exact:true}).click();
  await expect(canvas).toHaveAttribute("data-target-x",focus.x.toFixed(3));
  await expect(canvas).toHaveAttribute("data-target-z",focus.z.toFixed(3));
  await page.getByRole("button",{name:"Close inspector",exact:true}).click();
  removeInspectionMarker();
  await page.waitForTimeout(300);
  await page.locator(".world-stage").screenshot({path:info.outputPath("river-mouth-context.png")});
  for(let step=0;step<2;step++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  const bounds=(await canvas.boundingBox())!,x=bounds.x+bounds.width*.5,y=bounds.y+bounds.height*.3;
  // Orbit toward the supported top-down limit, keeping the actual outlet at
  // the same target. This exposes any beach layer spanning the water channel.
  await page.mouse.move(x,y);await page.mouse.down({button:"right"});
  await page.mouse.move(x,y+bounds.height*.2,{steps:12});await page.mouse.up({button:"right"});
  await expect.poll(async()=>Number(await canvas.getAttribute("data-camera-direction-y"))).toBeLessThan(-.9);
  await page.waitForTimeout(300);
  await page.locator(".world-stage").screenshot({path:info.outputPath("river-mouth-close-top-down.png")});
  await info.attach("browser-errors",{body:JSON.stringify(errors),contentType:"application/json"});
  expect(errors).toEqual([]);
});
