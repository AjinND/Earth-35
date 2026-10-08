import {test,expect,type Page,type WebSocketRoute} from "@playwright/test";
import {dayPhase,environmentAt} from "../lib/environment";
import type {World} from "../lib/types";

test("day phases wrap across days and light stays continuous at boundaries",()=>{
  for(const [minute,phase] of [[0,"Night"],[300,"Dawn"],[420,"Daytime"],[1080,"Dusk"],[1200,"Night"],[1440,"Night"],[1740,"Dawn"]] as const)
    expect(dayPhase(minute)).toBe(phase);
  expect(environmentAt(720,"Clear").daylight).toBe(1);
  expect(environmentAt(0,"Clear").daylight).toBe(0);
  for(const boundary of [300,360,420,1080,1140,1200,1440])
    expect(Math.abs(environmentAt(boundary-1,"Clear").daylight-environmentAt(boundary,"Clear").daylight)).toBeLessThan(.02);
});

async function controlledWorld(page:Page,initial:World) {
  let world=initial,socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/environment-test"}}));
  await page.routeWebSocket("**/ws/environment-test",route=>{socket=route;route.send(JSON.stringify(world));});
  await page.goto("/");
  await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
  await expect.poll(()=>!!socket).toBe(true);
  return (minute:number,weather:string,temperature:number)=>{
    world={...world,minute,weather,temperature,version:world.version+1};
    socket!.send(JSON.stringify(world));
  };
}

const value=(page:Page,name:string)=>page.locator(".world-stage canvas").getAttribute(`data-${name}`).then(value=>Number(value));

test("shared clock and weather change the map without resetting its camera",async({page,request},testInfo)=>{
  test.setTimeout(60000);
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const response=await request.get("/api/v1/world");expect(response.ok()).toBe(true);
  const initial:World={...await response.json(),minute:720,weather:"Clear",temperature:25};
  const update=await controlledWorld(page,initial);
  const environment=page.getByRole("status",{name:"World environment"});
  await expect(environment).toHaveText("Daytime · Clear");
  await expect.poll(()=>value(page,"daylight"),{timeout:30000}).toBeGreaterThan(.99);
  await expect.poll(()=>value(page,"rain")).toBe(0);
  await page.screenshot({path:testInfo.outputPath("day-clear.png")});
  await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("125%");

  update(0,"Clear",19);
  await expect(environment).toHaveText("Night · Clear");
  await expect.poll(()=>value(page,"daylight"),{timeout:10000}).toBeLessThan(.01);
  await expect.poll(()=>value(page,"window-glow")).toBeGreaterThan(1.4);
  await expect(page.getByLabel("Map zoom")).toHaveText("125%");
  await page.screenshot({path:testInfo.outputPath("night-clear.png")});

  update(330,"Overcast",17);
  await expect(environment).toHaveText("Dawn · Overcast");
  await expect.poll(()=>value(page,"cloud"),{timeout:10000}).toBeGreaterThan(.7);
  await expect.poll(()=>value(page,"rain")).toBe(0);
  await page.screenshot({path:testInfo.outputPath("dawn-overcast.png")});

  update(1110,"Rain",20);
  await expect(environment).toHaveText("Dusk · Rain");
  await expect.poll(()=>value(page,"rain"),{timeout:10000}).toBeGreaterThan(.99);
  await expect(page.locator(".world-stage")).toHaveAttribute("data-tone","dark");
  await expect(page.locator(".environment-status")).toHaveCSS("color","rgb(234, 241, 246)");
  await page.screenshot({path:testInfo.outputPath("dusk-rain.png")});
  // Rain and cloud geometry must not intercept building labels or camera input.
  await page.getByRole("button",{name:"NEW EDEN",exact:true}).click();
  await expect(page.locator(".inspect-panel").getByRole("heading",{name:"Central Park",exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Close inspector"}).click();
  await page.getByRole("button",{name:"Reset camera"}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");

  update(720,"Clear",25);
  await expect.poll(()=>value(page,"rain"),{timeout:10000}).toBeLessThan(.01);
  await expect.poll(()=>value(page,"window-glow"),{timeout:10000}).toBeLessThan(.1);
  await expect(page.locator(".world-stage")).toHaveAttribute("data-tone","light");
  expect(errors).toEqual([]);
});

test("mobile and reduced motion still show the shared rainy night",async({page,request},testInfo)=>{
  await page.setViewportSize({width:390,height:844});
  await page.emulateMedia({reducedMotion:"reduce"});
  const response=await request.get("/api/v1/world");expect(response.ok()).toBe(true);
  await controlledWorld(page,{...await response.json(),minute:1439,weather:"Rain",temperature:15});
  await expect(page.getByRole("status",{name:"World environment"})).toHaveText("Night · Rain");
  await expect.poll(()=>value(page,"rain")).toBe(1);
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("125%");
  await page.screenshot({path:testInfo.outputPath("night-rain-mobile.png")});
});

test("weather facts can be filtered without linking to an empty resident",async({page,request})=>{
  const response=await request.get("/api/v1/world");expect(response.ok()).toBe(true);
  await controlledWorld(page,{...await response.json(),events:[
    {id:900001,worldMinute:360,kind:"weather",actorId:"",targetId:"",message:"The skies have cleared over New Eden."},
    {id:900002,worldMinute:361,kind:"arrival",actorId:"resident-test",targetId:"park",message:"A resident arrived."},
  ]});
  await page.getByRole("link",{name:"World events",exact:true}).click();
  await page.getByRole("button",{name:"Weather",exact:true}).click();
  await expect(page.locator(".timeline article")).toHaveCount(1);
  await expect(page.locator(".event-message")).toHaveText("The skies have cleared over New Eden.");
  await expect(page.locator(".event-body button")).toHaveCount(0);
});
