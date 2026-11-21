import {test,expect,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {avatarMotion} from "../lib/avatar";
import {ResidentPlayback} from "../lib/resident-playback";
import type {ActivityProgress,World} from "../lib/types";

const progress:ActivityProgress={mode:"search",phase:"inspect",label:"Search for native plants",activeMinutes:3,requestedMinutes:15,inspectedStops:2,distance:8};

test("activity poses use authoritative execution modes and leave unsupported labels idle",()=>{
  expect(avatarMotion("DO_ACTIVITY")).toBe("Idle");
  expect(avatarMotion("DO_ACTIVITY",progress)).toBe("Search");
  expect(avatarMotion("DO_ACTIVITY",{...progress,phase:"travel"})).toBe("Walk");
  expect(avatarMotion("DO_ACTIVITY",{...progress,phase:"blocked"})).toBe("Idle");
  expect(avatarMotion("DO_ACTIVITY",{...progress,mode:"observe",phase:"perform"})).toBe("Observe");
  expect(avatarMotion("DO_ACTIVITY",{...progress,mode:"stationary",phase:"perform"})).toBe("Idle");
  expect(avatarMotion("WORK",progress)).toBe("Work");
  expect(avatarMotion("READ_BOOK")).toBe("Read");
});

test("execution metadata follows the buffered activity and never adds physical movement",()=>{
  const playback=new ResidentPlayback();
  playback.ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
  playback.ingest(601,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:{...progress,activeMinutes:4}},1000,0);
  playback.ingest(602,{x:4,z:0,activity:"DO_ACTIVITY",activityProgress:{...progress,phase:"travel",distance:12},motion:[{x:0,z:0,heading:Math.PI/2},{x:4,z:0,heading:Math.PI/2}]},1000,500);
  const inspection=playback.sample(500);
  expect(inspection.activityProgress?.phase).toBe("inspect");
  expect(inspection.moving).toBe(false);expect(inspection.x).toBe(0);
  const walking=playback.sample(1500);
  expect(walking.activityProgress?.phase).toBe("travel");
  expect(walking.moving).toBe(true);expect(walking.x).toBe(2);
  playback.ingest(603,{x:4,z:0,activity:"Idle"},1000,2000);
  const finished=playback.sample(3000);
  expect(finished.activityProgress).toBeUndefined();
  expect(finished.moving).toBe(false);expect(finished.x).toBe(4);
});

test("live searching shows measured progress and footsteps require received movement",async({page},info)=>{
  test.setTimeout(90000);
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const live:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/organic-world.json"),"utf8").replace(/^\uFEFF/,""));
  const park=live.buildings.find(building=>building.id==="park")??live.buildings.find(building=>building.type==="park")!;
  let world:World={...live,minute:720,weather:"Clear",tickMs:1500,agents:[{id:"activity-test",name:"Mei Lin",description:"A resident exploring native plants",home:"apt-101",occupation:"Explorer",location:park.id,x:park.x,z:park.z,activity:"DO_ACTIVITY",activityProgress:progress,status:"online",fixture:true}]};
  let socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Preview"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/activity-test"}}));
  await page.routeWebSocket("**/ws/activity-test",route=>{socket=route;route.send(JSON.stringify(world));});
  await page.goto("/?resident=activity-test");
  const label=page.locator(".map-label.selected"),preview=page.locator(".avatar-preview-stage"),activity=page.locator(".activity-progress");
  await expect(preview).toHaveAttribute("data-motion","Search",{timeout:30000});
  await expect(page.locator(".world-stage canvas[data-map-scale]")).toHaveAttribute("data-map-scale","residents",{timeout:45000});
  await expect(label).toHaveAttribute("data-avatar-motion","Search",{timeout:30000});
  await expect(activity).toContainText("Search for native plants");
  await expect(activity).toContainText("3 / 15 active minutes");
  await expect(activity).toContainText("2 inspection stops");
  await expect(activity).toContainText("8.00 world units travelled");
  await expect(page.getByRole("progressbar",{name:"Activity progress"})).toHaveAttribute("value","3");
  await page.locator(".world-stage").screenshot({path:info.outputPath("resident-inspecting-plants.png")});

  const start=world.agents[0];
  world={...world,version:world.version+1,minute:721,agents:[{...start,x:start.x+4,activityProgress:{...progress,phase:"travel",activeMinutes:4,distance:12},motion:[{x:start.x,z:start.z,heading:Math.PI/2},{x:start.x+4,z:start.z,heading:Math.PI/2}]}]};
  socket!.send(JSON.stringify(world));
  await expect(label).toHaveAttribute("data-avatar-motion","Walk");
  // The backend route has ended. A travel label alone cannot continue its gait.
  await expect(label).toHaveAttribute("data-avatar-motion","Idle");
  await expect(label).toContainText("waiting to move");
  world={...world,version:world.version+1,minute:722,agents:[{...world.agents[0],motion:null,activityProgress:{...progress,activeMinutes:5,inspectedStops:3,distance:12}}]};
  socket!.send(JSON.stringify(world));
  await expect(label).toHaveAttribute("data-avatar-motion","Search");
  await expect(activity).toContainText("5 / 15 active minutes");
  world={...world,version:world.version+1,agents:[{...world.agents[0],activityProgress:{...progress,phase:"blocked"}}]};
  socket!.send(JSON.stringify(world));
  await expect(preview).toHaveAttribute("data-motion","Idle");
  await expect(activity).toContainText("Waiting to resume; no progress recorded");
  await expect(label).toHaveAttribute("data-avatar-motion","Idle");
  world={...world,version:world.version+1,agents:[{...world.agents[0],activityProgress:{...progress,mode:"stationary",phase:"perform",distance:0,inspectedStops:0}}]};
  socket!.send(JSON.stringify(world));
  await expect(preview).toHaveAttribute("data-motion","Idle");
  await expect(label).toHaveAttribute("data-avatar-motion","Idle");
  await expect(label).toContainText("spending time");
  await expect(activity).toContainText("Time at this location only; no physical task is recorded");
  await expect(activity).toContainText("0.00 world units travelled · 0 inspection stops");
  expect(errors).toEqual([]);
});
