import {test,expect,type Locator,type Page,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {ATMOSPHERE_LIMITS} from "../lib/atmosphere";
import type {World} from "../lib/types";

type Frame=Record<string,string>;
const vector=(value:string)=>value.split(",").map(Number);
const numeric=(frame:Frame,key:string)=>Number(frame[key]);
const phaseKeys=["cloudOffset","rainPose","vegetationWind","waveTime"] as const;

async function frame(canvas:Locator):Promise<Frame> {
  return canvas.evaluate(element=>({...((element as HTMLCanvasElement).dataset)}) as Record<string,string>);
}

async function controlledWorld(page:Page) {
  const fixture:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  let world:World={...fixture,geography:{...fixture.geography!,version:4},minute:720,tickMs:1000,weather:"Clear",agents:[],events:[]};
  let socket:WebSocketRoute|undefined;
  const errors:string[]=[],unexpectedRequests:string[]=[];
  page.on("pageerror",error=>errors.push(error.message));
  page.on("console",message=>{if(message.type()==="error"&&/THREE|WebGL|shader/i.test(message.text()))errors.push(message.text());});
  await page.route("**/api/**",route=>{
    unexpectedRequests.push(new URL(route.request().url()).pathname);
    return route.fulfill({status:404,json:{message:"No live backend in environmental physics review"}});
  });
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Offline visitor"}}));
  await page.route("**/api/config",route=>{
    const url=new URL("/ws/environment-physics",route.request().url());url.protocol=url.protocol==="https:"?"wss:":"ws:";
    return route.fulfill({json:{webSocketUrl:url.href}});
  });
  await page.routeWebSocket("**/ws/environment-physics",route=>{socket=route;route.send(JSON.stringify(world));});
  await page.goto("/");
  const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-map-scale","island",{timeout:60000});
  await expect(canvas).toHaveAttribute("data-wind-motion","animated");
  await expect(canvas).toHaveAttribute("data-cloud-offset",/\d/);
  await expect(canvas).toHaveAttribute("data-vegetation-wind",/\d/);
  await expect(canvas).toHaveAttribute("data-wave-time",/\d/);
  await expect.poll(()=>!!socket).toBe(true);
  return {
    canvas,world,errors,unexpectedRequests,
    update:(changes:Partial<World>)=>{world={...world,...changes,version:world.version+1};socket!.send(JSON.stringify(world));},
  };
}

function expectWeatherVectors(sample:Frame) {
  const wind=[numeric(sample,"windX"),numeric(sample,"windZ")],rain=vector(sample.rainVelocity);
  const consumers=[sample.waveWind,sample.cloudWind,sample.vegetationWind].map(vector);
  expect([...wind,...rain,...consumers.flat()].every(Number.isFinite)).toBe(true);
  expect(rain).toHaveLength(3);for(const consumer of consumers)expect(consumer).toHaveLength(2);
  expect(rain[1]).toBeLessThan(0);
  for(let axis=0;axis<2;axis++) {
    expect(Math.sign(rain[axis===0?0:2])).toBe(Math.sign(wind[axis]));
    expect(rain[axis===0?0:2]).toBeCloseTo(wind[axis]/10,3);
    for(const consumer of consumers)expect(consumer[axis]).toBeCloseTo(wind[axis],3);
  }
}

for(const mobile of [false,true])test(`weather, water and woodland share bounded motion on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(150000);
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1440,height:960});
  await page.emulateMedia({reducedMotion:"no-preference"});
  const review=await controlledWorld(page),{canvas}=review;
  await expect.poll(async()=>numeric(await frame(canvas),"windSpeed")).toBeGreaterThan(0);

  // A stopped server may finish its received interval, but cannot run forever.
  await page.waitForTimeout(1400);
  const held=await frame(canvas);await page.waitForTimeout(350);
  expect(numeric(await frame(canvas),"environmentSeconds")).toBe(numeric(held,"environmentSeconds"));
  const clearSpeed=numeric(held,"windSpeed"),clearDirection=[numeric(held,"windX"),numeric(held,"windZ")];
  review.update({weather:"Rain"});
  await expect(canvas).toHaveAttribute("data-weather","Rain");
  await expect(canvas).toHaveAttribute("data-rain-count",String(mobile?ATMOSPHERE_LIMITS.rainMobile:ATMOSPHERE_LIMITS.rainDesktop));
  await expect(canvas).toHaveAttribute("data-rain-velocity",/\d/);
  await expect(canvas).toHaveAttribute("data-wave-wind",/\d/);
  await expect(canvas).toHaveAttribute("data-cloud-wind",/\d/);
  await expect.poll(async()=>numeric(await frame(canvas),"windSpeed")).toBeGreaterThan(clearSpeed);
  await expect.poll(async()=>{
    const sample=await frame(canvas),wind=[numeric(sample,"windX"),numeric(sample,"windZ")];
    return Math.max(...[sample.waveWind,sample.cloudWind,sample.vegetationWind].map(vector).flatMap(values=>values.map((value,index)=>Math.abs(value-wind[index]))));
  }).toBeLessThan(.001);
  const rainy=await frame(canvas),rainDirection=[numeric(rainy,"windX"),numeric(rainy,"windZ")];
  const alignment=clearDirection.reduce((sum,value,index)=>sum+value*rainDirection[index],0)/(Math.hypot(...clearDirection)*Math.hypot(...rainDirection));
  expect(alignment).toBeGreaterThan(.999);expectWeatherVectors(rainy);
  expect(numeric(rainy,"environmentSeconds")).toBe(numeric(held,"environmentSeconds"));

  const resume=await frame(canvas);
  review.update({minute:721});await expect(canvas).toHaveAttribute("data-sky-minute","721");
  await expect.poll(async()=>numeric(await frame(canvas),"environmentSeconds")).toBeGreaterThan(numeric(held,"environmentSeconds"));
  await expect.poll(()=>canvas.getAttribute("data-cloud-offset")).not.toBe(resume.cloudOffset);
  await expect.poll(()=>canvas.getAttribute("data-wave-time")).not.toBe(resume.waveTime);

  // Changing pace must not rewind any component's shared seconds.
  review.update({minute:722});
  await expect(canvas).toHaveAttribute("data-sky-minute","722");
  const beforePace=numeric(await frame(canvas),"environmentSeconds");review.update({tickMs:2000});
  const seconds=await canvas.evaluate(async element=>{
    const samples:number[]=[];
    for(let i=0;i<8;i++) {samples.push(Number((element as HTMLCanvasElement).dataset.environmentSeconds));await new Promise(resolve=>setTimeout(resolve,100));}
    return samples;
  });
  expect(seconds.every(Number.isFinite)).toBe(true);expect(seconds[0]).toBeGreaterThanOrEqual(beforePace);
  for(let i=1;i<seconds.length;i++)expect(seconds[i]).toBeGreaterThanOrEqual(seconds[i-1]);

  await page.emulateMedia({reducedMotion:"reduce"});
  await expect(canvas).toHaveAttribute("data-wind-motion","reduced");await page.waitForTimeout(350);
  const still=await frame(canvas);await page.waitForTimeout(400);const later=await frame(canvas);
  for(const key of phaseKeys) {expect(still[key],`${key} telemetry is missing`).toBeDefined();expect(later[key],`${key} moved with reduced motion`).toBe(still[key]);}
  await canvas.screenshot({path:info.outputPath("rain-cloud-and-water-overview.png")});

  const sky=[later.skyMinute,later.sunDirection,later.moonSelene,later.moonEmber];
  await page.getByRole("button",{name:"Enter the world",exact:true}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"}),firstPerson=dialog.locator("canvas");
  await expect(firstPerson).toHaveAttribute("data-controls-ready","true",{timeout:45000});
  await expect(firstPerson).toHaveAttribute("data-wind-motion","reduced");
  await expect(firstPerson).toHaveAttribute("data-sky-minute","722");
  await expect(firstPerson).toHaveAttribute("data-cloud-wind",/\d/);
  await expect(firstPerson).toHaveAttribute("data-vegetation-wind",/\d/);
  await expect(firstPerson).toHaveAttribute("data-wave-wind",/\d/);
  await expect(firstPerson).toHaveAttribute("data-rain-velocity",/\d/);
  const local=await frame(firstPerson);
  expect([local.skyMinute,local.sunDirection,local.moonSelene,local.moonEmber]).toEqual(sky);
  expectWeatherVectors(local);
  for(const key of ["cloudOffset","vegetationWind","waveTime"])expect(local[key]).toBe(later[key]);
  await page.getByRole("button",{name:"World map",exact:true}).click();
  const woodland=review.world.buildings.find(building=>building.id==="western-woods")!;
  await page.locator(".map-destination").filter({hasText:woodland.name}).click();
  await expect(page.getByLabel("World map and residents")).toHaveCount(0);
  await page.emulateMedia({reducedMotion:"no-preference"});review.update({minute:723});
  await expect(firstPerson).toHaveAttribute("data-wind-motion","animated");
  await expect(firstPerson).toHaveAttribute("data-sky-minute","723");
  await expect(firstPerson).toHaveAttribute("data-vegetation-wind",/\d/);
  await page.screenshot({path:info.outputPath("woodland-rain-and-wind.png")});
  expect(review.errors).toEqual([]);expect(review.unexpectedRequests).toEqual([]);
});
