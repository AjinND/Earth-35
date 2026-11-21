import {test,expect,type Page,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import * as THREE from "three";
import {architecture,redesignedBuilding,BUILDING_ROUTE_HALF,BUILDING_VIEWER_HALF} from "../lib/architecture";
import {buildingDesign} from "../lib/building-design";
import {createBuildingGeometry} from "../lib/building-geometry";
import {arrival,walkable,walkSegmentClear} from "../components/ImmersiveWorld";
import {organicCityLayout} from "../lib/city-layout";
import {walkElevation} from "../lib/island";
import type {Building,World} from "../lib/types";

const fixture=():World=>JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
const buildings=fixture().buildings.filter(b=>redesignedBuilding(b.type));
const layout={...organicCityLayout,version:4};

test("every existing address retains its footprint, front entrance and safe viewer arrival",()=>{
  expect(buildings.length).toBeGreaterThanOrEqual(110);
  for(const b of buildings) {
    const original=JSON.stringify(b),design=buildingDesign(b),a=design.architecture;
    expect(JSON.stringify(b)).toBe(original);
    expect(a.angle).toBe(0);
    expect(a.floorHeight).toBeGreaterThan(2.7-1e-9);
    expect(a.floorHeight).toBeLessThan(3.4+1e-9);
    expect(b.x+design.door.x).toBeCloseTo(b.entrance!.x,8);
    expect(b.z+design.door.z).toBeCloseTo(b.entrance!.z,8);
    const door=design.solid.find(part=>part.role==="door")!;
    expect(door.x).toBeCloseTo(design.door.x,8);
    expect(door.z+door.depth/2).toBeLessThan(design.door.z);
    // The doorway remains open to its approach up to the admission point.
    for(const part of design.solid.filter(part=>part.y-part.height/2<1.7&&part.y+part.height/2>.4)) {
      if(Math.abs(part.x-design.door.x)>part.width/2+.22)continue;
      expect(part.z+part.depth/2,`${b.id}: ${part.role} obstructs admission`).toBeLessThan(design.door.z);
    }
    expect(walkable(b,[b],layout)).toBe(false);
    const outside=arrival(b,[b],layout);
    expect(walkable(outside,[b],layout)).toBe(true);
    expect(walkSegmentClear({x:b.x-6,z:b.z},{x:b.x+6,z:b.z},[b],layout)).toBe(false);
    expect(walkSegmentClear({x:b.x-6,z:b.z+4},{x:b.x+6,z:b.z+4},[b],layout)).toBe(true);
  }
});

test("rendered walls and roofs have outward faces, support their windows and stay inside clearance",()=>{
  const variants=new Map<string,Building>();
  for(const b of buildings)variants.set(architecture(b.id,b.type).style,b);
  // Also cover forms not currently used by an office address.
  for(const type of ["house","apartment","office"])for(let i=0;i<40;i++) {
    const b={...buildings[0],id:`${type}-${i}`,type};variants.set(architecture(b.id,b.type).style,b);
  }
  expect(variants.size).toBe(13);
  const material=new THREE.MeshBasicMaterial({side:THREE.FrontSide});
  for(const [style,b] of variants) {
    const geometry=createBuildingGeometry(b),mesh=new THREE.Mesh(geometry.opaque,material);
    mesh.updateMatrixWorld();
    const bounds=geometry.opaque.boundingBox!;
    expect(Math.max(Math.abs(bounds.min.x),Math.abs(bounds.max.x),Math.abs(bounds.min.z),Math.abs(bounds.max.z)),style).toBeLessThan(BUILDING_VIEWER_HALF);
    for(const [dx,dz] of [[1,0],[-1,0],[0,1],[0,-1]]) {
      const hit=new THREE.Raycaster(new THREE.Vector3(dx*8,1.65,dz*8),new THREE.Vector3(-dx,0,-dz)).intersectObject(mesh)[0];
      expect(hit,`${style}: missing exterior wall`).toBeDefined();
      expect(Math.hypot(hit.point.x,hit.point.z)).toBeGreaterThanOrEqual(3.34);
    }
    const roof=geometry.design.roofs[0];
    // Survey the full roof, including all four eaves: a reversed triangle or
    // undersized roof must not expose a wall top (the earlier villa defect).
    for(const x of [-.95,-.8,-.5,0,.5,.8,.95])for(const z of [-.95,-.8,-.5,0,.5,.8,.95]) {
      const hit=new THREE.Raycaster(new THREE.Vector3(roof.x+x*roof.width/2,30,roof.z+z*roof.depth/2),new THREE.Vector3(0,-1,0)).intersectObject(mesh)[0];
      expect(hit?.point.y,`${style}: missing roof at ${x}, ${z}`).toBeGreaterThan(roof.y+.005);
    }
    for(const pane of geometry.design.glass.filter(part=>part.role==="window")) {
      const normal=pane.width<.1?new THREE.Vector3(Math.sign(pane.x),0,0):new THREE.Vector3(0,0,Math.sign(pane.z));
      const hit=new THREE.Raycaster(new THREE.Vector3(pane.x,pane.y,pane.z).addScaledVector(normal,.15),normal.negate()).intersectObject(mesh)[0];
      expect(hit,`${style}: floating window`).toBeDefined();
      expect(hit.distance).toBeLessThan(.25);
    }
    // A resident can pass alongside the unchanged 3.5-unit server envelope.
    for(const side of [-1,1])expect(new THREE.Raycaster(new THREE.Vector3(side*(BUILDING_ROUTE_HALF+.01),1.65,8),new THREE.Vector3(0,0,-1)).intersectObject(mesh)).toHaveLength(0);
    geometry.dispose();
  }
  material.dispose();
});

test("distant buildings retain lit windows with bounded geometry and deterministic district palettes",()=>{
  for(const b of buildings) {
    const near=createBuildingGeometry(b,true),far=createBuildingGeometry(b,false);
    expect(far.design.glass.filter(part=>part.role==="window").length).toBeGreaterThan(0);
    expect(far.opaque.getAttribute("position").count).toBeLessThan(near.opaque.getAttribute("position").count*.6);
    expect(near.opaque.getAttribute("position").count).toBeLessThan(16000);
    expect(architecture(b.id,b.type,b.district)).toEqual(architecture(b.id,b.type,b.district));
    expect(near.design.architecture.color).toBe(architecture("another-address",b.type,b.district).color);
    // Existing settlement addresses remain grounded on their authoritative surface.
    const door=b.entrance!;
    expect(Math.abs(walkElevation(door.x,door.z)-walkElevation(b.x,b.z))).toBeLessThan(.1);
    near.dispose();far.dispose();
  }
});

async function controlled(page:Page) {
  let world={...fixture(),geography:layout,minute:720,weather:"Clear",agents:[]},socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Architecture review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3003/ws/built-environment"}}));
  await page.routeWebSocket("**/ws/built-environment",route=>{socket=route;route.send(JSON.stringify(world));});
  return (minute:number,weather="Clear")=>{world={...world,minute,weather,version:world.version+1};socket!.send(JSON.stringify(world));};
}

for(const mobile of [false,true])test(`built environment review and first-person door clearance on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(180000);
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const update=await controlled(page);
  const examples=[buildings.find(b=>b.type==="house"&&architecture(b.id,b.type).style==="cottage")!,buildings.find(b=>b.type==="apartment"&&architecture(b.id,b.type).style==="stepped-apartment")!,buildings.find(b=>b.type==="office")!];
  for(const b of examples) {
    await page.goto(`/?resident=${encodeURIComponent(b.id)}`);
    const canvas=page.locator(".world-stage canvas");await expect(canvas).toBeVisible({timeout:60000});
    await expect(canvas).toHaveAttribute("data-target-x",b.x.toFixed(3),{timeout:45000});
    await expect(page.locator(".inspect-panel")).toContainText(b.name);
    for(let i=0;i<(b.type==="house"?6:3);i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
    await page.getByRole("button",{name:"Close inspector"}).click();
    await page.locator(".world-stage").screenshot({path:info.outputPath(`${b.type}-day.png`)});
    update(0);await expect.poll(async()=>Number(await canvas.getAttribute("data-window-glow"))).toBeGreaterThan(1.4);
    await page.locator(".world-stage").screenshot({path:info.outputPath(`${b.type}-night.png`)});
    update(720);
  }
  await page.getByRole("button",{name:"Reset camera"}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await page.locator(".world-stage").screenshot({path:info.outputPath("island.png")});
  await page.getByRole("button",{name:"View settlement",exact:true}).click();
  await page.locator(".world-stage").screenshot({path:info.outputPath("settlement.png")});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});
  await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:45000});
  for(const b of examples) {
    await page.getByRole("button",{name:"World map",exact:true}).click();
    await page.getByRole("button",{name:new RegExp(`${b.name.replace(/[.*+?^${}()|[\]\\]/g,"\\$&")}.*Visit`)}).click();
    const position=page.getByLabel("Your world position"),outside=arrival(b,fixture().buildings,layout);
    await expect(position).toHaveText(`${outside.x.toFixed(1)}, ${outside.z.toFixed(1)}`);
    await page.keyboard.down("s");
    await expect.poll(async()=>Number((await position.textContent())!.split(",")[1])).toBeGreaterThan(outside.z+5);
    await page.keyboard.up("s");
    await page.screenshot({path:info.outputPath(`${b.type}-entrance.png`)});
    // Default gaze is north. Walk back towards the door and hold the key;
    // the camera must stop outside the wall rather than enter a private interior.
    await page.keyboard.down("w");
    await expect.poll(async()=>Number((await position.textContent())!.split(",")[1]),{timeout:10000}).toBeLessThan(outside.z+.5);
    await page.waitForTimeout(600);await page.keyboard.up("w");
    const coordinates=(await position.textContent())!.split(",").map(Number);
    expect(coordinates[1]).toBeGreaterThanOrEqual(b.z+BUILDING_VIEWER_HALF-.06);
  }
  expect(errors).toEqual([]);
});

test("all house roof variants have complete silhouettes in close-up",async({page},info)=>{
  test.setTimeout(180000);await page.setViewportSize({width:1280,height:900});
  await page.emulateMedia({reducedMotion:"reduce"});await controlled(page);
  const examples=new Map<string,Building>();
  for(const b of buildings.filter(b=>b.type==="house"))examples.set(architecture(b.id,b.type).style,b);
  expect(examples.size).toBe(5);
  for(const [style,b] of examples) {
    await page.goto(`/?resident=${encodeURIComponent(b.id)}`);
    const canvas=page.locator(".world-stage canvas");
    await expect(canvas).toHaveAttribute("data-target-x",b.x.toFixed(3),{timeout:45000});
    for(let i=0;i<6;i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
    await page.getByRole("button",{name:"Close inspector"}).click();
    await page.locator(".world-stage").screenshot({path:info.outputPath(`roof-${style}.png`)});
  }
});
