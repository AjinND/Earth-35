import {test,expect,type Page} from "@playwright/test";
import {COASTLINE,ISLAND_AREA_KM2,METERS_PER_MAP_UNIT,ISLAND_BOUNDS,onIsland,polygonArea,islandElevation} from "../lib/island";
import type {World} from "../lib/types";
import {islandCityLayout} from "../lib/city-layout";

test.setTimeout(90000);
test("island area and protected settlement stay coherent",()=>{
  expect(polygonArea(COASTLINE)*METERS_PER_MAP_UNIT**2/1e6).toBeCloseTo(ISLAND_AREA_KM2,8);
  const width=(ISLAND_BOUNDS.maxX-ISLAND_BOUNDS.minX)*METERS_PER_MAP_UNIT;
  const depth=(ISLAND_BOUNDS.maxZ-ISLAND_BOUNDS.minZ)*METERS_PER_MAP_UNIT;
  expect(width).toBeGreaterThan(10000);expect(depth).toBeGreaterThan(10000);
  for(const x of [-76,0,76])for(const z of [-76,0,76]){expect(onIsland({x,z})).toBe(true);expect(islandElevation(x,z)).toBe(0);}
  for(const dx of [-268,0,268])for(const z of [-268,0,268]){
    const x=islandCityLayout.centerX+dx;expect(onIsland({x,z})).toBe(true);expect(islandElevation(x,z)).toBe(0);
  }
  expect(onIsland({x:3000,z:3000})).toBe(false);
});

async function observe(page:Page,world:World) {
  let send:(world:World)=>void=()=>{};
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/island-test"}}));
  await page.routeWebSocket("**/ws/island-test",socket=>{send=next=>socket.send(JSON.stringify(next));socket.send(JSON.stringify(world));});
  await page.goto("/");await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:30000});
  await expect(page.getByLabel("Map zoom")).toHaveText("100%",{timeout:15000});
  return (next:World)=>{world=next;send(next);};
}
async function touch(page:Page,mode:"pinch"|"pan") {
  const canvas=await page.locator(".world-stage canvas").boundingBox();if(!canvas)throw Error("No map");
  const session=await page.context().newCDPSession(page),x=canvas.x+canvas.width*.5,y=canvas.y+canvas.height*.55;
  const fingers=(distance:number)=>[{id:1,x:x-distance,y},{id:2,x:x+distance,y}];
  if(mode==="pinch") {
    await session.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:fingers(25)});
    for(const distance of [35,45,55,65])await session.send("Input.dispatchTouchEvent",{type:"touchMove",touchPoints:fingers(distance)});
  }else {
    await session.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:[{id:1,x,y}]});
    for(const dx of [10,20,30,40])await session.send("Input.dispatchTouchEvent",{type:"touchMove",touchPoints:[{id:1,x:x+dx,y}]});
  }
  await session.send("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});await session.detach();
}

for(const mobile of [false,true])test.describe(mobile?"mobile island":"desktop island",()=>{
  test.use({viewport:mobile?{width:390,height:844}:{width:1440,height:1050},hasTouch:mobile,isMobile:mobile});
  test("island, settlement and residents remain reachable",async({page,request},testInfo)=>{
    const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
    const response=await request.get("/api/v1/world");expect(response.ok()).toBe(true);
    const live:World=await response.json();
    const park=live.buildings.find(b=>b.id==="park")!;
    const world={...live,minute:720,weather:"Clear",temperature:25,agents:[{id:"island-observer",name:"Island Observer",description:"A camera test resident",home:live.buildings.find(b=>b.capacity===1)!.id,occupation:"Unemployed",location:"park",x:park.x,z:park.z,activity:"REST",status:"online",fixture:true}]};
    for(const b of live.buildings){expect(onIsland(b)).toBe(true);expect(islandElevation(b.x,b.z)).toBe(0);}
    await observe(page,world);
    const canvas=page.locator(".world-stage canvas"),zoom=page.getByLabel("Map zoom");
    await expect(canvas).toHaveAttribute("data-map-scale","island");
    await expect(page.getByRole("button",{name:"NEW EDEN",exact:true})).toBeVisible();
    await expect(page.getByLabel("Map distance scale")).toBeVisible();
    await page.screenshot({path:testInfo.outputPath("island-overview.png"),fullPage:true});
    await page.getByRole("button",{name:"View settlement",exact:true}).click();
    await expect(zoom).toHaveText(live.geography?.version?"200%":"800%");await expect(canvas).toHaveAttribute("data-map-scale","settlement");
    await page.screenshot({path:testInfo.outputPath("settlement.png"),fullPage:true});
    await page.getByRole("button",{name:"View residents",exact:true}).click();
    await expect(zoom).toHaveText("5500%");await expect(canvas).toHaveAttribute("data-map-scale","residents");
    await page.screenshot({path:testInfo.outputPath("resident-scale.png"),fullPage:true});
    await page.getByRole("button",{name:"Browse places"}).click();
    await page.locator(".places-popover").getByRole("button",{name:"Common Ground",exact:true}).click();
    await expect(page.locator(".inspect-panel h2")).toHaveText("Common Ground");
    await page.getByRole("button",{name:"Close inspector"}).click();
    await page.getByRole("button",{name:"Reset camera"}).click();await expect(zoom).toHaveText("100%");
    if(mobile) {
      await touch(page,"pinch");await expect.poll(async()=>Number((await zoom.textContent())!.replace("%",""))).toBeGreaterThan(100);
      const x=Number(await canvas.getAttribute("data-camera-x"));await touch(page,"pan");
      await expect.poll(async()=>Math.abs(Number(await canvas.getAttribute("data-camera-x"))-x)).toBeGreaterThan(1);
    }else {
      const box=await canvas.boundingBox();if(!box)throw Error("No canvas");
      await page.mouse.move(box.x+box.width*.6,box.y+box.height*.5);await page.mouse.wheel(0,-300);
      await expect.poll(async()=>Number((await zoom.textContent())!.replace("%",""))).toBeGreaterThan(100);
      const x=Number(await canvas.getAttribute("data-camera-x"));
      await page.mouse.down();await page.mouse.move(box.x+box.width*.7,box.y+box.height*.6,{steps:6});await page.mouse.up();
      await expect.poll(async()=>Math.abs(Number(await canvas.getAttribute("data-camera-x"))-x)).toBeGreaterThan(1);
    }
    await page.getByRole("button",{name:"Reset camera"}).click();await expect(zoom).toHaveText("100%");
    const position=await canvas.getAttribute("data-camera-x");
    await page.setViewportSize(mobile?{width:430,height:900}:{width:1200,height:900});
    await expect(zoom).toHaveText("100%");await expect(canvas).toHaveAttribute("data-camera-x",position!);
    await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
    expect(errors).toEqual([]);
  });
});

test("following uses the displayed resident and survives live updates",async({page,request})=>{
  const live:World=await (await request.get("/api/v1/world")).json(),park=live.buildings.find(b=>b.id==="park")!;
  const resident={id:"island-follow",name:"Island Follow",description:"Camera observer",home:live.buildings.find(b=>b.capacity===1)!.id,occupation:"Unemployed",location:"park",x:park.x,z:park.z,activity:"REST",status:"online",fixture:true};
  let world={...live,minute:720,weather:"Clear",agents:[resident]};const update=await observe(page,world);
  await page.goto("/?resident=island-follow");
  await expect(page.locator(".inspect-panel h2")).toHaveText("Island Follow");
  await page.getByRole("button",{name:"Follow agent",exact:true}).click();await expect(page.getByLabel("Map zoom")).toHaveText("5500%");
  const canvas=page.locator(".world-stage .world-canvas canvas");
  await expect(page.locator(".world-canvas .map-label.selected").getByText("Island Follow",{exact:true})).toBeVisible();
  const before=Number(await canvas.getAttribute("data-target-x"));
  world={...world,version:world.version+1,minute:721,agents:[{...resident,activity:"MOVE",location:"road",x:park.x+10,z:park.z+10}]};update(world);
  await expect.poll(async()=>Math.abs(Number(await canvas.getAttribute("data-target-x"))-before),{timeout:15000}).toBeGreaterThan(5);
  await expect(page.getByLabel("Map zoom")).toHaveText("5500%");
  await page.getByRole("button",{name:"Reset camera"}).click();await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await expect(page.getByRole("button",{name:"Follow agent",exact:true})).toBeVisible();
});

test("reduced motion, empty residents and maximum zoom reset correctly",async({page,request})=>{
  await page.emulateMedia({reducedMotion:"reduce"});
  const live:World=await (await request.get("/api/v1/world")).json();await observe(page,{...live,agents:[],minute:720,weather:"Clear"});
  const zoom=page.getByLabel("Map zoom"),canvas=page.locator(".world-stage canvas");
  await page.getByRole("button",{name:"View residents"}).click();await expect(zoom).toHaveText("5500%");
  const zoomIn=page.getByRole("button",{name:"Zoom in",exact:true});
  for(let i=0;i<8&&await zoomIn.isEnabled();i++)await zoomIn.click();
  await expect(zoom).toHaveText("18000%");await expect(page.getByRole("button",{name:"Zoom in",exact:true})).toBeDisabled();
  await page.getByRole("button",{name:"Zoom out",exact:true}).click();await expect(zoom).toHaveText("14400%");
  await page.getByRole("button",{name:"Reset camera"}).click();await expect(zoom).toHaveText("100%");
  await expect(canvas).toHaveAttribute("data-camera-x","1630.000");
});
