import {test,expect,type Page} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import * as THREE from "three";
import {organicCityLayout,clearsRoad} from "../lib/city-layout";
import {dryLand} from "../lib/organic-layout";
import {COASTLINE,ISLAND_CENTER,islandElevation,onIsland} from "../lib/island";
import {nearestRiver} from "../lib/river";
import type {World} from "../lib/types";

type Probe={kind:"land"|"water";x:number;y:number;z:number};
type ProjectedProbe=Probe&{u:number;v:number};

function fixture():World {
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/organic-world.json"),"utf8").replace(/^\uFEFF/,""));
  return {...world,geography:{...organicCityLayout,version:4},minute:720,weather:"Clear",temperature:25};
}

function probes(world:World):Probe[] {
  const points:Probe[]=[];
  // Sample known flat, dry city ground rather than roofs, road markings or
  // public-place furniture. A broad grid tolerates incidental trees/shadows.
  for(let x=-410;x<=110;x+=24)for(let z=-220;z<=220;z+=24) {
    if(!dryLand({x,z})||Math.abs(islandElevation(x,z))>.01||!clearsRoad(x,z,8,world.geography)||nearestRiver(x,z).distance<14)continue;
    if(world.buildings.some(building=>Math.hypot(x-building.x,z-building.z)<Math.max(13,(building.outdoorRadius??0)+8)))continue;
    points.push({kind:"land",x,y:-.08,z});
  }
  // The rendered offshore sea is a positive cyan control in the same frame.
  // Stay beyond the beach and shelf, avoiding their different surface colors.
  for(let index=0;index<COASTLINE.length;index+=8) {
    const coast=COASTLINE[index],x=ISLAND_CENTER.x+(coast.x-ISLAND_CENTER.x)*1.065,z=ISLAND_CENTER.z+(coast.z-ISLAND_CENTER.z)*1.065;
    if(!onIsland({x,z}))points.push({kind:"water",x,y:-.8,z});
  }
  return points;
}

async function mockWorld(page:Page,world:World) {
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Island water rendering review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/island-water-rendering"}}));
  await page.routeWebSocket("**/ws/island-water-rendering",socket=>socket.send(JSON.stringify(world)));
}

async function rasterProbes(page:Page,points:Probe[]) {
  const canvas=page.locator(".world-stage > .world-canvas canvas"),bounds=(await canvas.boundingBox())!;
  const metadata=await canvas.evaluate(element=>{
    const data=(element as HTMLCanvasElement).dataset;
    return {zoom:Number(data.cameraZoom),targetX:Number(data.targetX),targetZ:Number(data.targetZ),direction:[Number(data.cameraDirectionX),Number(data.cameraDirectionY),Number(data.cameraDirectionZ)] as [number,number,number]};
  });
  const target=new THREE.Vector3(metadata.targetX,0,metadata.targetZ),direction=new THREE.Vector3(...metadata.direction);
  const camera=new THREE.OrthographicCamera(-bounds.width/2,bounds.width/2,bounds.height/2,-bounds.height/2,.1,30000);
  camera.position.copy(target).addScaledVector(direction,-3000);camera.lookAt(target);camera.zoom=metadata.zoom;
  camera.updateProjectionMatrix();camera.updateMatrixWorld();
  const projected:ProjectedProbe[]=points.map(point=>{
    const screen=new THREE.Vector3(point.x,point.y,point.z).project(camera);
    return {...point,u:(screen.x+1)/2,v:(1-screen.y)/2};
  }).filter(point=>point.u>.03&&point.u<.97&&point.v>.03&&point.v<.97);
  const png=await canvas.screenshot();
  const samples=await page.evaluate(async({encoded,points})=>{
    // Decode the captured raster, not WebGL geometry or material settings.
    const bytes=Uint8Array.from(atob(encoded),character=>character.charCodeAt(0));
    const image=await createImageBitmap(new Blob([bytes],{type:"image/png"}));
    const canvas=document.createElement("canvas");canvas.width=image.width;canvas.height=image.height;
    const context=canvas.getContext("2d")!;context.drawImage(image,0,0);image.close();
    const pixels=context.getImageData(0,0,canvas.width,canvas.height).data,radius=canvas.width<640?1:2;
    return points.map(point=>{
      const x=Math.round(point.u*canvas.width),y=Math.round(point.v*canvas.height);
      let count=0,land=0,cyan=0,red=0,green=0,blue=0;
      for(let dx=-radius;dx<=radius;dx++)for(let dy=-radius;dy<=radius;dy++) {
        const offset=((y+dy)*canvas.width+x+dx)*4,r=pixels[offset],g=pixels[offset+1],b=pixels[offset+2];
        // Broad hue separation: grass has more green than blue; cyan water
        // has substantially more blue than red. Lighting may vary RGB values.
        if(g-b>=12&&b-r<=8)land++;
        if(b-r>=12&&g-b<=12)cyan++;
        count++;red+=r;green+=g;blue+=b;
      }
      return {...point,land:land/count,cyan:cyan/count,rgb:[red/count,green/count,blue/count]};
    });
  },{encoded:png.toString("base64"),points:projected});
  return {png,metadata:{...metadata,bounds},samples};
}

for(const mobile of [false,true])test(`marine bands do not cover dry city ground in the ${mobile?"mobile":"desktop"} island overview`,async({page},info)=>{
  test.setTimeout(120000);
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const world=fixture(),errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  await mockWorld(page,world);await page.goto("/");
  const canvas=page.locator(".world-stage > .world-canvas canvas");
  await expect(canvas).toHaveAttribute("data-river-course","mountain-to-sea",{timeout:45000});
  await expect(canvas).toHaveAttribute("data-map-scale","island");
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await expect.poll(async()=>Number(await canvas.getAttribute("data-daylight"))).toBeGreaterThan(.99);
  // Environment colors converge over frames even when decorative motion is reduced.
  await page.waitForTimeout(500);
  const result=await rasterProbes(page,probes(world));
  const imagePath=info.outputPath("island-overview.png"),diagnosticsPath=info.outputPath("rendered-ground-probes.json");
  fs.writeFileSync(imagePath,result.png);
  fs.writeFileSync(diagnosticsPath,JSON.stringify({metadata:result.metadata,samples:result.samples},null,2));
  await info.attach("island-overview.png",{path:imagePath,contentType:"image/png"});
  await info.attach("rendered-ground-probes.json",{path:diagnosticsPath,contentType:"application/json"});
  const land=result.samples.filter(point=>point.kind==="land"),water=result.samples.filter(point=>point.kind==="water");
  expect(land.length,"The overview must include enough independent dry city probes").toBeGreaterThan(40);
  expect(water.length,"The same frame must include rendered offshore water controls").toBeGreaterThan(5);
  const visibleLand=land.filter(point=>point.land>=.6).length/land.length;
  const obscuredByWater=land.filter(point=>point.cyan>=.6).length/land.length;
  const visibleWater=water.filter(point=>point.cyan>=.6).length/water.length;
  expect(visibleWater,"Offshore controls should recognize the actual cyan sea").toBeGreaterThan(.6);
  expect(visibleLand,"Dry city ground is hidden by a marine-colored layer").toBeGreaterThan(.65);
  expect(obscuredByWater,"Cyan sea or shelf pixels cover dry city ground").toBeLessThan(.2);
  expect(errors).toEqual([]);
});
