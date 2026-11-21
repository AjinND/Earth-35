import {test,expect,type Page,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import sharp from "sharp";
import {atmosphereSeed,ATMOSPHERE_LIMITS} from "../lib/atmosphere";
import type {World} from "../lib/types";

async function weather(page:Page) {
  let world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  world={...world,geography:{...world.geography!,version:4},minute:720,weather:"Rain",agents:[]};
  let socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{error:"Not signed in"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3011/ws/map-rain"}}));
  await page.routeWebSocket("**/ws/map-rain",route=>{socket=route;route.send(JSON.stringify(world));});
  return (next:string)=>{world={...world,weather:next,version:world.version+1};socket!.send(JSON.stringify(world));};
}

async function visibleStreaks(png:Buffer,count:number) {
  const {data,info}=await sharp(png).removeAlpha().raw().toBuffer({resolveWithObject:true});
  const brightness=(x:number,y:number)=>{
    const offset=(y*info.width+x)*info.channels;return data[offset]*.2126+data[offset+1]*.7152+data[offset+2]*.0722;
  };
  let hits=0;
  const length=info.width<640?8:10;
  for(let i=0;i<count;i++) {
    const x=Math.round(atmosphereSeed(i,5)*info.width-1.5),y=Math.round((1-atmosphereSeed(i,7))*info.height-length/2);
    if(x<6||x>=info.width-6||y<2||y>=info.height-2)continue;
    let center=0;
    for(let dx=-1;dx<=1;dx++)for(let dy=-1;dy<=1;dy++)center=Math.max(center,brightness(x+dx,y+dy));
    const sides=(brightness(x-5,y)+brightness(x+5,y))/2;
    if(center-sides>12)hits++;
  }
  return hits;
}

for(const mobile of [false,true])test(`map rain is visible, follows weather and respects reduced motion on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(120000);await page.setViewportSize(mobile?{width:390,height:844}:{width:1440,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  page.on("console",message=>{if(message.type()==="error"&&/THREE|WebGL|shader/i.test(message.text()))errors.push(message.text());});
  const update=await weather(page);await page.goto("/");
  const canvas=page.locator(".world-stage canvas"),count=mobile?ATMOSPHERE_LIMITS.rainMobile:ATMOSPHERE_LIMITS.rainDesktop;
  await expect(canvas).toHaveAttribute("data-map-scale","island",{timeout:60000});
  await expect(canvas).toHaveAttribute("data-rain-count",String(count));await expect(canvas).toHaveAttribute("data-rain-view","map");
  const rainy=await canvas.screenshot({path:info.outputPath("rainy-map.png"),scale:"css"});
  const rainHits=await visibleStreaks(rainy,count);
  expect(rainHits).toBeGreaterThan(count*.15);
  const pose=await canvas.getAttribute("data-rain-pose");await page.waitForTimeout(350);await expect(canvas).toHaveAttribute("data-rain-pose",pose!);
  await page.getByRole("button",{name:"Zoom in",exact:true}).click();await expect(page.getByLabel("Map zoom")).toHaveText("125%");
  await expect(canvas).toHaveAttribute("data-rain-count",String(count));
  await page.emulateMedia({reducedMotion:"no-preference"});await expect.poll(()=>canvas.getAttribute("data-rain-pose")).not.toBe(pose);
  await page.emulateMedia({reducedMotion:"reduce"});await expect(canvas).toHaveAttribute("data-rain-pose",pose!);
  await page.getByRole("button",{name:"Enter the world",exact:true}).click();
  const dialog=page.getByRole("dialog"),firstPerson=dialog.locator("canvas");
  await expect(firstPerson).toHaveAttribute("data-controls-ready","true",{timeout:45000});await expect(firstPerson).toHaveAttribute("data-rain-view","first-person");
  await expect(firstPerson).toHaveAttribute("data-rain-count",String(count));
  await page.getByRole("button",{name:"Back to dashboard",exact:true}).click();await expect(dialog).toHaveCount(0);
  await page.getByRole("button",{name:"Reset camera"}).click();await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  update("Clear");await expect(canvas).toHaveAttribute("data-rain-count","0");await expect(canvas).toHaveAttribute("data-rain-view","none");
  await expect.poll(async()=>Number(await canvas.getAttribute("data-road-roughness"))).toBeGreaterThan(.99);
  const clear=await canvas.screenshot({path:info.outputPath("clear-map.png"),scale:"css"});
  const clearHits=await visibleStreaks(clear,count);expect(rainHits-clearHits).toBeGreaterThan(count*.1);
  update("Overcast");await expect(canvas).toHaveAttribute("data-weather","Overcast");await expect(canvas).toHaveAttribute("data-rain-count","0");
  update("Rain");await expect(canvas).toHaveAttribute("data-rain-view","map");
  await page.getByRole("button",{name:"View planet",exact:true}).click();await expect(canvas).toHaveAttribute("data-map-scale","planet");
  await page.getByRole("button",{name:"Reset camera"}).click();await expect(canvas).toHaveAttribute("data-rain-count",String(count));
  console.log({mobile,rainHits,clearHits});expect(errors).toEqual([]);
});
