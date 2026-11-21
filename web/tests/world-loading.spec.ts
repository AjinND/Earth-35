import {test,expect} from "@playwright/test";
import {segmentIndex} from "../lib/segment-index";
import {sharedResourceCache} from "../lib/shared-resource";
import {COASTLINE,onIsland} from "../lib/island";
import {RIVER} from "../lib/river";
import {ALL_TRAILS} from "../lib/landscapes";
import {organicStreets} from "../lib/organic-layout";
import fs from "node:fs";
import path from "node:path";
import type {World} from "../lib/types";

test("coastline bands preserve island boundaries, vertices and offshore points",()=>{
  const probes=[...COASTLINE,...COASTLINE.flatMap((a,i)=>{
    const b=COASTLINE[(i+1)%COASTLINE.length];return [-1e-8,0,1e-8].map(offset=>({x:(a.x+b.x)/2+offset,z:(a.z+b.z)/2+offset}));
  }),...Array.from({length:5000},(_,i)=>({x:Math.sin(i*31.7)*1500,z:Math.cos(i*17.3)*1500}))];
  const mismatches=[];
  for(const point of probes) {
    const {x,z}=point;let inside=false;
    for(let i=0,j=COASTLINE.length-1;i<COASTLINE.length;j=i++) {
      const a=COASTLINE[i],b=COASTLINE[j];
      if((a.z>z)!==(b.z>z)&&x<(b.x-a.x)*(z-a.z)/(b.z-a.z)+a.x)inside=!inside;
    }
    if(onIsland(point)!==inside)mismatches.push(point);
  }
  expect(mismatches).toEqual([]);
});

test("indexed distances preserve coast, river, trail and street survey results",()=>{
  const paths=[[...COASTLINE,COASTLINE[0]],RIVER,...ALL_TRAILS,...organicStreets];
  for(const points of paths) {
    const nearest=segmentIndex([points]);
    const probes=[...points,...Array.from({length:120},(_,i)=>({x:Math.sin(i*31.7)*1200,z:Math.cos(i*17.3)*1000}))];
    for(const {x,z} of probes) {
      let squared=Infinity,index=-1,fraction=0;
      for(let i=1;i<points.length;i++) {
        const a=points[i-1],b=points[i],dx=b.x-a.x,dz=b.z-a.z;
        const t=Math.max(0,Math.min(1,((x-a.x)*dx+(z-a.z)*dz)/(dx*dx+dz*dz)));
        const d=(x-a.x-t*dx)**2+(z-a.z-t*dz)**2;
        if(d<squared){squared=d;index=i-1;fraction=t;}
      }
      const actual=nearest(x,z);
      expect(actual.distance).toBeCloseTo(Math.sqrt(squared),9);
      expect(actual.index).toBe(index);expect(actual.fraction).toBeCloseTo(fraction,9);
    }
  }
});

test("segment index handles empty paths and zero-length segments",()=>{
  expect(segmentIndex([])(0,0)).toEqual({distance:Infinity,index:-1,fraction:0});
  expect(segmentIndex([[{x:1,z:2},{x:1,z:2}]])(4,6)).toEqual({distance:5,index:0,fraction:0});
});

test("two scenes share resources until the last scene releases them",async()=>{
  const disposed:string[]=[],cache=sharedResourceCache<string,{id:string}>(value=>disposed.push(value.id),20);
  const value=cache.get("terrain",()=>({id:"terrain"})),releaseMap=cache.retain("terrain");
  expect(cache.get("terrain",()=>{throw new Error("Rebuilt terrain");})).toBe(value);
  const releaseWalk=cache.retain("terrain");releaseWalk();releaseWalk();
  await new Promise(resolve=>setTimeout(resolve,30));expect(disposed).toEqual([]);
  releaseMap();
  const releaseAgain=cache.retain("terrain");
  await new Promise(resolve=>setTimeout(resolve,30));expect(disposed).toEqual([]);
  releaseAgain();await expect.poll(()=>disposed).toEqual(["terrain"]);
});

test("spatial changes bound the idle cache without disposing a live scene",()=>{
  const disposed:number[]=[],cache=sharedResourceCache<number,number>(value=>disposed.push(value),30000,1);
  cache.get(1,()=>1);const release=cache.retain(1);
  cache.get(2,()=>2);cache.get(3,()=>3);
  expect(disposed).toEqual([2]);expect(cache.get(1,()=>99)).toBe(1);
  release();expect(disposed).toEqual([2,1]);
});

test("first-person entry pauses the retained map and returning resumes it",async({page},info)=>{
  test.setTimeout(120000);
  let world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  world={...world,geography:{...world.geography!,version:4},minute:720,weather:"Clear",agents:[]};
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{error:"Not signed in"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3011/ws/loading-test"}}));
  let update:(value:World)=>void=()=>{};
  await page.routeWebSocket("**/ws/loading-test",socket=>{update=value=>socket.send(JSON.stringify(value));socket.send(JSON.stringify(world));});
  await page.goto("/");
  const map=page.locator(".world-stage canvas");
  await expect(map).toHaveAttribute("data-map-scale","island",{timeout:60000});
  await expect(map).toHaveAttribute("data-river-flow",/\d/);
  await map.evaluate(element=>{element.dataset.retainedCanvas="true";});
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});
  for(let entry=0;entry<2;entry++) {
    await page.getByRole("button",{name:"Enter the world",exact:true}).click();
    await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:45000});
    const flow=await map.getAttribute("data-river-flow");
    await page.waitForTimeout(400);expect(await map.getAttribute("data-river-flow")).toBe(flow);
    const position=page.getByLabel("Your world position"),start=await position.textContent();
    await page.keyboard.down("w");await expect(position).not.toHaveText(start!);await page.keyboard.up("w");
    world={...world,minute:world.minute+1,version:world.version+1};update(world);
    await expect(dialog.locator(".world-hud strong")).toContainText(`12:0${entry+1}`);
    await page.getByRole("button",{name:"Back to dashboard",exact:true}).click();
    await expect(dialog).toHaveCount(0);await expect(map).toHaveAttribute("data-retained-canvas","true");
    await expect(map).not.toHaveAttribute("data-river-flow",flow!);
  }
  await page.getByRole("button",{name:"Zoom in",exact:true}).click();await expect(page.getByLabel("Map zoom")).toHaveText("125%");
  await page.screenshot({path:info.outputPath("map-after-return.png")});
  expect(errors).toEqual([]);
});
