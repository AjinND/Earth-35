import {test,expect,type Locator,type Page,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {walkable} from "../components/ImmersiveWorld";
import type {World} from "../lib/types";

type Point={x:number;z:number};
const separation=(a:Point,b:Point)=>Math.hypot(a.x-b.x,a.z-b.z);

async function controlledWorld(page:Page) {
  const fixture:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  let world:World={...fixture,geography:{...fixture.geography!,version:4},minute:720,weather:"Clear",agents:[],events:[]};
  let socket:WebSocketRoute|undefined;
  const errors:string[]=[],unexpectedRequests:string[]=[];
  page.on("pageerror",error=>errors.push(error.message));
  // Every API and socket response comes from this saved world, including polls.
  await page.route("**/api/**",route=>{
    unexpectedRequests.push(new URL(route.request().url()).pathname);
    return route.fulfill({status:404,json:{message:"No live backend in walking controls review"}});
  });
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Offline visitor"}}));
  await page.route("**/api/config",route=>{
    const url=new URL("/ws/walking-controls",route.request().url());url.protocol=url.protocol==="https:"?"wss:":"ws:";
    return route.fulfill({json:{webSocketUrl:url.href}});
  });
  await page.routeWebSocket("**/ws/walking-controls",route=>{socket=route;route.send(JSON.stringify(world));});
  await page.goto("/");
  await expect(page.getByRole("button",{name:"Enter the world"})).toBeEnabled({timeout:30000});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"}),canvas=dialog.locator("canvas");
  await expect(canvas).toHaveAttribute("data-controls-ready","true",{timeout:45000});
  await expect(canvas).toHaveAttribute("data-walking-speed","0.0000");
  await expect.poll(()=>!!socket).toBe(true);
  const position=page.getByLabel("Your world position");
  await expect(position).toHaveText(`${world.geography!.centerX.toFixed(1)}, ${(world.geography!.centerZ+8).toFixed(1)}`);
  return {
    world,dialog,canvas,position,errors,unexpectedRequests,
    update:(changes:Partial<World>={})=>{world={...world,...changes,version:world.version+1};socket!.send(JSON.stringify(world));},
  };
}

async function readPosition(position:Locator):Promise<Point> {
  const [x,z]=(await position.textContent())!.split(",").map(Number);return {x,z};
}

async function expectMoving(canvas:Locator,position:Locator,from:Point) {
  await expect.poll(async()=>Number(await canvas.getAttribute("data-walking-speed")),{timeout:4000,intervals:[50]}).toBeGreaterThan(0);
  await expect.poll(async()=>separation(await readPosition(position),from),{timeout:4000,intervals:[50]}).toBeGreaterThan(.3);
}

async function expectStopped(page:Page,canvas:Locator,position:Locator) {
  // The speed is measured every frame; the rounded HUD reports every 150 ms.
  await expect(canvas).toHaveAttribute("data-walking-speed","0.0000",{timeout:250});
  await page.waitForTimeout(180);
  const stopped=await readPosition(position);
  await page.waitForTimeout(250);
  expect(await readPosition(position)).toEqual(stopped);
  await expect(canvas).toHaveAttribute("data-walking-speed","0.0000");
  return stopped;
}

test("keyboard release, map pause and focus loss stop walking in the saved world",async({page},info)=>{
  test.setTimeout(120000);
  const review=await controlledWorld(page),{canvas,position}=review;
  const initial=await readPosition(position);
  await page.keyboard.down("w");await expectMoving(canvas,position,initial);
  await page.keyboard.up("w");const released=await expectStopped(page,canvas,position);
  review.update();await page.waitForTimeout(200);expect(await readPosition(position)).toEqual(released);

  await page.keyboard.down("d");await expectMoving(canvas,position,released);
  await page.getByRole("button",{name:"World map",exact:true}).click();
  await expect(page.getByLabel("World map and residents")).toBeVisible();
  await expect(canvas).toHaveAttribute("data-walking-speed","0.0000",{timeout:250});
  await page.getByRole("button",{name:"Close world map",exact:true}).click();
  // The physical key stays held until after resume, so a stale input would move.
  const resumed=await expectStopped(page,canvas,position);await page.keyboard.up("d");

  await page.keyboard.down("w");await expectMoving(canvas,position,resumed);
  await page.evaluate(()=>window.dispatchEvent(new Event("blur")));
  await expectStopped(page,canvas,position);await page.keyboard.up("w");

  const destination=review.world.buildings.find(building=>building.id==="south-beach")!;
  await page.getByRole("button",{name:"World map",exact:true}).click();
  await page.locator(".map-destination").filter({hasText:destination.name}).click();
  await expect(page.getByLabel("World map and residents")).toHaveCount(0);
  await expect.poll(async()=>separation(await readPosition(position),destination)).toBeLessThan(16);
  const arrived=await expectStopped(page,canvas,position);
  expect(walkable(arrived,review.world.buildings,review.world.geography,review.world.objects)).toBe(true);

  // A newly installed sign must free an occupied viewer instead of trapping it.
  const objects=[...(review.world.objects??[]),{id:"walking-recovery-sign",owner:"offline-owner",kind:"signboard",text:"New arrival",business:null,x:arrived.x,z:arrived.z,status:"installed"}];
  review.update({objects});
  await expect.poll(async()=>separation(await readPosition(position),arrived)).toBeGreaterThanOrEqual(.9);
  const recovered=await expectStopped(page,canvas,position);
  expect(walkable(recovered,review.world.buildings,review.world.geography,objects)).toBe(true);
  // Arrival searches the front approach first; walk away from the new sign.
  await page.keyboard.down("s");await expectMoving(canvas,position,recovered);
  await page.keyboard.up("s");await expectStopped(page,canvas,position);
  await page.screenshot({path:info.outputPath("walking-controls-desktop.png")});
  expect(review.errors).toEqual([]);expect(review.unexpectedRequests).toEqual([]);
});

test("mobile walking pad stops after touch release and cancellation",async({page},info)=>{
  test.setTimeout(120000);await page.setViewportSize({width:390,height:844});
  const review=await controlledWorld(page),{canvas,position}=review;
  const touch=await page.context().newCDPSession(page);
  await touch.send("Emulation.setTouchEmulationEnabled",{enabled:true,maxTouchPoints:1});
  try {
    const forward=page.getByRole("button",{name:"Walk forward",exact:true});await expect(forward).toBeVisible();
    const bounds=(await forward.boundingBox())!,point={x:bounds.x+bounds.width/2,y:bounds.y+bounds.height/2,id:0};
    const initial=await readPosition(position);
    // Real touch input gives setPointerCapture an active pointer, as on a phone.
    await touch.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:[point]});
    await expectMoving(canvas,position,initial);
    await touch.send("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});
    const released=await expectStopped(page,canvas,position);

    await touch.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:[point]});
    await expectMoving(canvas,position,released);
    await touch.send("Input.dispatchTouchEvent",{type:"touchCancel",touchPoints:[]});
    await expectStopped(page,canvas,position);
    await page.screenshot({path:info.outputPath("walking-controls-mobile.png")});
    expect(review.errors).toEqual([]);expect(review.unexpectedRequests).toEqual([]);
  } finally {await touch.detach();}
});
