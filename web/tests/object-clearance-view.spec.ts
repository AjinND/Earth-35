import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {gatheringSeats} from "../lib/gathering-furniture";
import type {World,Agent} from "../lib/types";

for(const type of ["garden","plaza"])test(`${type} furniture and standing visitors remain clear in the rendered world`,async({page},info)=>{
  test.setTimeout(150000);await page.setViewportSize({width:1280,height:900});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  const b=world.buildings.find(b=>b.type===type)!;b.outdoorRadius=0;
  const seats=gatheringSeats(b);
  const resident=(id:string,index:number):Agent=>({id,name:id,description:"Clearance regression",home:"apt-101",occupation:"Visitor",location:b.id,x:seats[index].x,z:seats[index].z,activity:"Idle",status:"paused",fixture:true});
  world.agents=[resident("Standing visitor",0),{...resident("Seated reader",3),activity:"READ_BOOK",seatIndex:3},
    {...resident("Talking visitor",2),activity:"CHAT",performance:{actionId:"talk",action:"TALK",phase:"perform",posture:"standing",gesture:"talk",heldItem:null,task:"Talking",heading:0,anchored:true,activeMinutes:0}}];
  world.minute=720;world.weather="Clear";
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Clearance review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3005/ws/clearance-review"}}));
  await page.routeWebSocket("**/ws/clearance-review",socket=>socket.send(JSON.stringify(world)));
  await page.goto("/?resident=Standing%20visitor");
  const canvas=page.getByLabel("Interactive 3D map of New Eden island",{exact:true}).locator("canvas");await expect(canvas).toBeVisible({timeout:60000});
  await expect(page.locator(".world-stage [data-avatar-motion]")).toHaveAttribute("data-seated","false",{timeout:60000});
  for(let i=0;i<3;i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await page.getByRole("button",{name:"Close inspector"}).click();
  await page.locator(".world-stage").screenshot({path:info.outputPath(`${type}-clearance.png`)});
  expect(errors).toEqual([]);
});
