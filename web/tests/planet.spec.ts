import {test,expect,type Locator,type Page,type TestInfo,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import type {World} from "../lib/types";
import {ISLAND_CENTER} from "../lib/island";
import {ATLAS_HEIGHT,ATLAS_WIDTH,ISLAND_ANCHOR,LANDMASSES,OCEAN_COVERAGE_PERCENT,PLANET_RADIUS_KM,atlasPoint,sphericalAtlasArea} from "../lib/planet";

test.setTimeout(120000);

const fixture:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/organic-world.json"),"utf8").replace(/^\uFEFF/,""));
const mapCanvas=(page:Page)=>page.locator(".world-stage > .world-canvas canvas");
const zoomPercent=async(page:Page)=>Number((await page.getByLabel("Map zoom").textContent())?.replace("%",""));
const cameraZoom=async(canvas:Locator)=>Number(await canvas.getAttribute("data-camera-zoom"));
const target=async(canvas:Locator)=>({x:Number(await canvas.getAttribute("data-target-x")),z:Number(await canvas.getAttribute("data-target-z"))});
const cameraDirection=async(canvas:Locator)=>canvas.evaluate(element=>{
  const data=(element as HTMLCanvasElement).dataset;
  return [Number(data.cameraDirectionX),Number(data.cameraDirectionY),Number(data.cameraDirectionZ)];
});
const directionDistance=(a:number[],b:number[])=>Math.hypot(...a.map((value,index)=>value-b[index]));

test("spherical geography keeps New Eden anchored and more than 70 percent ocean",()=>{
  expect(OCEAN_COVERAGE_PERCENT).toBeGreaterThan(70);
  expect(OCEAN_COVERAGE_PERCENT).toBeLessThan(100);
  expect(atlasPoint(ISLAND_ANCHOR.longitude,ISLAND_ANCHOR.latitude)).toEqual(ISLAND_CENTER);
  // A latitude/longitude rectangle has an independent closed-form sphere area.
  const rectangle=[[-30,20],[70,20],[70,60],[-30,60]] as const;
  const analyticalArea=PLANET_RADIUS_KM**2*(100*Math.PI/180)*(Math.sin(Math.PI/3)-Math.sin(Math.PI/9));
  expect(sphericalAtlasArea(rectangle)).toBeCloseTo(analyticalArea,6);
  expect(sphericalAtlasArea([...rectangle].reverse())).toBeCloseTo(analyticalArea,6);
});

async function previewWorld(page:Page,empty=false) {
  let world:World=structuredClone(fixture),socket:WebSocketRoute|undefined;
  if(empty)world={...world,agents:[],buildings:[],events:[],transport:undefined};
  const errors:string[]=[],unexpectedRequests:string[]=[];
  page.on("pageerror",error=>errors.push(error.message));
  // Keep this suite self contained: all public snapshots and sockets are local
  // fixtures, and any accidental account/runtime request fails the test.
  await page.route("**/api/**",route=>{
    unexpectedRequests.push(`${route.request().method()} ${new URL(route.request().url()).pathname}`);
    return route.fulfill({status:404,json:{message:"No account or runtime in planet preview"}});
  });
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Preview"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/planet-preview"}}));
  await page.routeWebSocket("**/ws/planet-preview",route=>{socket=route;route.send(JSON.stringify(world));});
  await page.goto("/");
  const canvas=mapCanvas(page);
  await expect(canvas).toBeVisible({timeout:45000});
  await expect(canvas).toHaveAttribute("data-map-scale","island",{timeout:30000});
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await expect.poll(()=>!!socket).toBe(true);
  return {
    canvas,errors,unexpectedRequests,
    snapshot:()=>world,
    send:(next:World)=>{world=next;socket!.send(JSON.stringify(world));},
  };
}

async function clickZoom(page:Page,direction:"in"|"out") {
  const canvas=mapCanvas(page),before=await cameraZoom(canvas);
  await page.getByRole("button",{name:`Zoom ${direction}`,exact:true}).click();
  const expected=direction==="in"?before*1.25:before*.8;
  await expect.poll(async()=>Math.abs(await cameraZoom(canvas)-expected)).toBeLessThan(Math.max(1e-6,expected*.0001));
}

async function noHorizontalOverflow(page:Page) {
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth-document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}

async function checkAtlas(page:Page,info:TestInfo) {
  const preview=await previewWorld(page),{canvas}=preview;
  const originalCanvas=await canvas.elementHandle();
  if(!originalCanvas)throw new Error("World canvas did not render");
  await page.emulateMedia({reducedMotion:"reduce"});

  // Ordinary dolly steps cross the old island limit without replacing Canvas.
  for(let i=0;i<3;i++)await clickZoom(page,"out");
  expect(await zoomPercent(page)).toBeLessThan(55);
  await expect(canvas).toHaveAttribute("data-map-scale","planet");
  await expect(page.getByRole("button",{name:"View planet",exact:true})).toHaveAttribute("aria-pressed","true");
  expect(await canvas.evaluate((element,original)=>element===original,originalCanvas)).toBe(true);

  await page.getByRole("button",{name:"View planet",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
  await expect(page.getByLabel("Planet geography",{exact:true})).toBeVisible();
  await expect(page.getByLabel("Map distance scale",{exact:true})).toBeVisible();
  const eden=page.getByRole("button",{name:"New Eden · Inhabited island",exact:true});
  await expect(eden).toBeVisible();
  await expect(page.getByText(/Undeveloped/).first()).toBeVisible();
  await expect(page.getByRole("button",{name:/Undeveloped/})).toHaveCount(0);
  await page.locator(".world-stage").screenshot({path:info.outputPath("planet-atlas.png")});

  // The minimum is computed from this viewport's atlas fit, and is reversible.
  const planetZoom=await cameraZoom(canvas);
  expect(planetZoom).toBeGreaterThan(0);
  expect(await zoomPercent(page)).toBeGreaterThan(0);
  await clickZoom(page,"in");
  await expect(page.getByRole("button",{name:"Zoom out",exact:true})).toBeEnabled();
  const zoomOut=page.getByRole("button",{name:"Zoom out",exact:true});
  for(let i=0;i<40&&await zoomOut.isEnabled();i++) {
    const before=await cameraZoom(canvas);
    await zoomOut.click();
    await expect.poll(()=>cameraZoom(canvas)).toBeLessThan(before);
  }
  await expect(zoomOut).toBeDisabled();
  expect(await cameraZoom(canvas)).toBeGreaterThan(0);
  await clickZoom(page,"in");

  // Planet pan uses the same orbit target as the local map.
  const box=await canvas.boundingBox(),beforePan=await target(canvas);
  if(!box)throw new Error("World canvas has no bounds");
  await page.mouse.move(box.x+box.width*.3,box.y+box.height*.45);
  await page.mouse.down();
  await page.mouse.move(box.x+box.width*.48,box.y+box.height*.57,{steps:8});
  await page.mouse.up();
  await expect.poll(async()=>{const next=await target(canvas);return Math.hypot(next.x-beforePan.x,next.z-beforePan.z);}).toBeGreaterThan(1);
  await page.getByRole("button",{name:"View planet",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
  await eden.click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await expect(canvas).toHaveAttribute("data-map-scale","island");
  expect(await canvas.evaluate((element,original)=>element===original,originalCanvas)).toBe(true);

  // Buildings, residents and live camera following remain usable after return.
  const world=preview.snapshot(),resident=world.agents[0],place=world.buildings.find(building=>building.id===resident.location)!;
  await page.getByRole("button",{name:"Browse places",exact:true}).click();
  await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
  const inspector=page.locator(".inspect-panel");
  await expect(inspector.getByRole("heading",{name:place.name,exact:true})).toBeVisible();
  await inspector.getByRole("button",{name:resident.name,exact:true}).click();
  await expect(inspector.getByRole("heading",{name:resident.name,exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Follow agent",exact:true}).click();
  await expect(page.getByRole("button",{name:"Stop following",exact:true})).toBeVisible();
  await expect(canvas).toHaveAttribute("data-map-scale","residents");
  const next={...world,version:world.version+1,minute:world.minute+1,tickMs:200,agents:world.agents.map(agent=>agent.id===resident.id?{...agent,x:resident.x+12,z:resident.z+8,activity:"MOVE",location:"outdoors"}:agent)};
  preview.send(next);
  await expect(inspector).toContainText("outdoors");
  await expect.poll(async()=>{const position=await target(canvas);return Math.hypot(position.x-next.agents[0].x,position.z-next.agents[0].z);}).toBeLessThan(.2);
  await expect(page.getByRole("button",{name:"Stop following",exact:true})).toBeVisible();
  if((page.viewportSize()?.width??Infinity)<=540) {
    const selectedLabel=page.locator(".world-stage [data-avatar-motion]");
    await expect(selectedLabel).toBeVisible();
    await expect.poll(async()=>{
      const labelBox=await selectedLabel.boundingBox(),inspectorBox=await inspector.boundingBox();
      return labelBox&&inspectorBox?labelBox.x+labelBox.width-inspectorBox.x:Infinity;
    }).toBeLessThanOrEqual(1);
  }
  await page.locator(".world-stage").screenshot({path:info.outputPath("resident-after-planet.png")});
  await page.getByRole("button",{name:"Stop following",exact:true}).click();
  await page.getByRole("button",{name:"Close inspector",exact:true}).click();
  await page.getByRole("button",{name:"View settlement",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("200%");
  await page.getByRole("button",{name:"View residents",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("5500%");
  await page.getByRole("button",{name:"Reset camera",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await noHorizontalOverflow(page);
  expect(preview.errors).toEqual([]);
  expect(preview.unexpectedRequests).toEqual([]);
  return canvas;
}

test("planet atlas fits, pans and returns to live island residents on desktop",async({page},info)=>{
  await page.setViewportSize({width:1536,height:960});
  await checkAtlas(page,info);
});

test.describe("touch planet atlas",()=>{
  test.use({viewport:{width:390,height:844},hasTouch:true,isMobile:true});
  test("mobile atlas, pinch and local follow controls remain reachable",async({page},info)=>{
    const canvas=await checkAtlas(page,info);
    await page.getByRole("button",{name:"View planet",exact:true}).tap();
    await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
    const before=await cameraZoom(canvas),box=await canvas.boundingBox();
    if(!box)throw new Error("World canvas has no touch bounds");
    const session=await page.context().newCDPSession(page),x=box.x+box.width/2,y=box.y+box.height*.48;
    const fingers=(distance:number)=>[{id:1,x:x-distance,y},{id:2,x:x+distance,y}];
    await session.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:fingers(20)});
    for(const distance of [30,40,50,60])await session.send("Input.dispatchTouchEvent",{type:"touchMove",touchPoints:fingers(distance)});
    await session.send("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});
    await expect.poll(()=>cameraZoom(canvas)).toBeGreaterThan(before);
    const enlarged=await cameraZoom(canvas);
    await session.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:fingers(60)});
    for(const distance of [50,40,30,20])await session.send("Input.dispatchTouchEvent",{type:"touchMove",touchPoints:fingers(distance)});
    await session.send("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});
    await expect.poll(()=>cameraZoom(canvas)).toBeLessThan(enlarged);
    await session.detach();
    await page.getByRole("button",{name:"Reset camera",exact:true}).tap();
    await expect(page.getByLabel("Map zoom")).toHaveText("100%");
    await noHorizontalOverflow(page);
  });
});

test("reduced motion and an empty world retain accessible planet navigation",async({page},info)=>{
  await page.setViewportSize({width:390,height:844});
  await page.emulateMedia({reducedMotion:"reduce"});
  const preview=await previewWorld(page,true),{canvas}=preview;
  const planet=page.getByRole("button",{name:"View planet",exact:true});
  await planet.focus();await page.keyboard.press("Enter");
  await expect(planet).toHaveAttribute("aria-pressed","true");
  await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
  await expect(page.getByLabel("Planet geography",{exact:true})).toBeVisible();
  const eden=page.getByRole("button",{name:"New Eden · Inhabited island",exact:true});
  await eden.focus();await expect(eden).toBeFocused();await page.keyboard.press("Enter");
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await page.getByRole("button",{name:"View residents",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("5500%");
  await page.getByRole("button",{name:"Reset camera",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await page.screenshot({path:info.outputPath("empty-world-accessible.png"),fullPage:true});
  await noHorizontalOverflow(page);
  expect(preview.errors).toEqual([]);
  expect(preview.unexpectedRequests).toEqual([]);
});

test("resizing preserves relative zoom at local and planet scales",async({page},info)=>{
  await page.setViewportSize({width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const preview=await previewWorld(page),{canvas}=preview;
  await clickZoom(page,"in");
  await page.setViewportSize({width:390,height:844});
  await expect(page.getByLabel("Map zoom")).toHaveText("125%");
  await noHorizontalOverflow(page);
  await page.setViewportSize({width:1536,height:960});
  await expect(page.getByLabel("Map zoom")).toHaveText("125%");
  await page.getByRole("button",{name:"View planet",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
  // Stay clear of either viewport's computed lower bound when resizing.
  for(let i=0;i<8;i++)await clickZoom(page,"in");
  const percent=await zoomPercent(page);
  await page.setViewportSize({width:390,height:844});
  await expect.poll(async()=>Math.abs(await zoomPercent(page)-percent)).toBeLessThanOrEqual(.002);
  await expect(canvas).toHaveAttribute("data-map-scale","planet");
  await noHorizontalOverflow(page);
  await page.getByRole("button",{name:"View planet",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
  await page.locator(".world-stage").screenshot({path:info.outputPath("planet-resized.png")});
  await page.getByRole("button",{name:"Reset camera",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  expect(preview.errors).toEqual([]);
  expect(preview.unexpectedRequests).toEqual([]);
});

test("animated atlas return keeps the island visible and preserves an orbited camera",async({page},info)=>{
  await page.setViewportSize({width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const preview=await previewWorld(page),{canvas}=preview;
  const originalCanvas=await canvas.elementHandle();
  if(!originalCanvas)throw new Error("World canvas did not render");
  let navigations=0;page.on("framenavigated",frame=>{if(frame===page.mainFrame())navigations++;});
  await page.getByRole("button",{name:"Reset camera",exact:true}).click();
  const homeDirection=await cameraDirection(canvas),box=await canvas.boundingBox();
  if(!box)throw new Error("World canvas has no orbit bounds");
  // Reach the steepest allowed pitch, then yaw so the atlas diagonal occupies
  // the viewport height. This is the most demanding orientation for full fit.
  await page.mouse.move(box.x+box.width*.35,box.y+box.height*.3);
  await page.mouse.down({button:"right"});
  await page.mouse.move(box.x+box.width*.35,box.y+box.height*.7,{steps:8});
  await page.mouse.up({button:"right"});
  await expect.poll(async()=>Math.abs((await cameraDirection(canvas))[1])).toBeGreaterThan(.97);
  const steepDirection=await cameraDirection(canvas),currentYaw=Math.atan2(-steepDirection[0],-steepDirection[2]);
  const desiredYaw=Math.atan2(ATLAS_WIDTH,ATLAS_HEIGHT);
  const yawChange=Math.atan2(Math.sin(desiredYaw-currentYaw),Math.cos(desiredYaw-currentYaw));
  await page.mouse.move(box.x+box.width*.5,box.y+box.height*.5);
  await page.mouse.down({button:"right"});
  await page.mouse.move(box.x+box.width*.5-yawChange*box.height/(2*Math.PI),box.y+box.height*.5,{steps:8});
  await page.mouse.up({button:"right"});
  await expect.poll(async()=>{
    const direction=await cameraDirection(canvas);
    return Math.abs(Math.atan2(-direction[0],-direction[2])-desiredYaw);
  }).toBeLessThan(.02);
  await expect.poll(async()=>directionDistance(await cameraDirection(canvas),homeDirection)).toBeGreaterThan(.05);
  const orbitedDirection=await cameraDirection(canvas),islandZoom=await cameraZoom(canvas);
  await page.emulateMedia({reducedMotion:"no-preference"});
  await page.getByRole("button",{name:"View planet",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
  expect(directionDistance(await cameraDirection(canvas),orbitedDirection)).toBeLessThan(.001);
  await expect(canvas).toHaveAttribute("data-inhabited-island-visible","true");

  // Sample rendered frames, so an endpoint-only fit cannot hide a blank journey.
  const renderedFrames=canvas.evaluate(async(element,stopZoom)=>{
    const samples:{zoom:number;visible:boolean}[]=[],start=performance.now();
    const sample=()=>{
      const data=(element as HTMLCanvasElement).dataset,zoom=Number(data.cameraZoom);
      samples.push({zoom,visible:data.inhabitedIslandVisible==="true"});
      return zoom;
    };
    // Include the start immediately; slow rendering may provide only one
    // intermediate frame before the wall-clock transition reaches its endpoint.
    sample();
    while(performance.now()-start<15000) {
      await new Promise<void>(resolve=>requestAnimationFrame(()=>resolve()));
      const zoom=sample();
      if(samples.length>1&&Math.abs(zoom-stopZoom)<stopZoom*1e-5)break;
    }
    return samples;
  },islandZoom);
  await page.getByRole("button",{name:"New Eden · Inhabited island",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  const samples=await renderedFrames;
  expect(samples.length).toBeGreaterThan(2);
  expect(new Set(samples.map(sample=>sample.zoom.toPrecision(5))).size).toBeGreaterThan(2);
  expect(samples.every(sample=>sample.visible)).toBe(true);
  expect(directionDistance(await cameraDirection(canvas),orbitedDirection)).toBeLessThan(.001);
  expect(await canvas.evaluate((element,original)=>element===original,originalCanvas)).toBe(true);
  expect(navigations).toBe(0);
  await page.locator(".world-stage").screenshot({path:info.outputPath("orbited-island-after-animated-return.png")});
  expect(preview.errors).toEqual([]);
  expect(preview.unexpectedRequests).toEqual([]);
});

test("island marker releases following after a resident wheel zooms out to planet scale",async({page})=>{
  await page.setViewportSize({width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const preview=await previewWorld(page),{canvas}=preview,world=preview.snapshot();
  const resident=world.agents[0],place=world.buildings.find(building=>building.id===resident.location)!;
  await page.getByRole("button",{name:"Browse places",exact:true}).click();
  await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
  await page.locator(".inspect-panel").getByRole("button",{name:resident.name,exact:true}).click();
  await page.getByRole("button",{name:"Follow agent",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-map-scale","residents");
  // Approach the island boundary with buttons while keeping follow active.
  // Wheel dolly in these controls applies one step per event, regardless of delta.
  for(let i=0;i<24&&await zoomPercent(page)>70;i++) {
    const before=await zoomPercent(page);
    await page.getByRole("button",{name:"Zoom out",exact:true}).click();
    await expect.poll(()=>zoomPercent(page)).toBeLessThan(before);
  }
  expect(await zoomPercent(page)).toBeGreaterThanOrEqual(55);
  expect(await zoomPercent(page)).toBeLessThanOrEqual(70);
  await expect(page.getByRole("button",{name:"Stop following",exact:true})).toBeVisible();
  const box=await canvas.boundingBox();
  if(!box)throw new Error("World canvas has no wheel bounds");
  await canvas.hover({position:{x:box.width*.28,y:box.height*.5}});
  for(let i=0;i<10&&(await canvas.getAttribute("data-map-scale"))!=="planet";i++) {
    const before=await cameraZoom(canvas);
    await page.mouse.wheel(0,400);
    await expect.poll(()=>cameraZoom(canvas)).toBeLessThan(before);
  }
  await expect(canvas).toHaveAttribute("data-map-scale","planet");
  await expect(page.getByRole("button",{name:"Stop following",exact:true})).toBeVisible();
  await page.getByRole("button",{name:"New Eden · Inhabited island",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await expect(page.getByRole("button",{name:"Stop following",exact:true})).toHaveCount(0);
  await expect.poll(()=>target(canvas)).toEqual(ISLAND_CENTER);
  preview.send({...world,version:world.version+1,weather:"Rain",agents:world.agents.map(agent=>({...agent,x:agent.x+40,z:agent.z+30,activity:"MOVE",location:"outdoors"}))});
  await expect(page.getByLabel("World environment",{exact:true})).toContainText("Rain");
  const afterUpdate=await canvas.evaluate(async element=>{
    const positions:{x:number;z:number}[]=[];
    for(let i=0;i<8;i++) {
      await new Promise<void>(resolve=>requestAnimationFrame(()=>resolve()));
      const data=(element as HTMLCanvasElement).dataset;
      positions.push({x:Number(data.targetX),z:Number(data.targetZ)});
    }
    return positions;
  });
  for(const position of afterUpdate)expect(position).toEqual(ISLAND_CENTER);
  expect(preview.errors).toEqual([]);
  expect(preview.unexpectedRequests).toEqual([]);
});

test("first-person exploration stays local and returns to the unchanged parent atlas",async({page},info)=>{
  await page.setViewportSize({width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const preview=await previewWorld(page),{canvas}=preview,world=preview.snapshot();
  await page.getByRole("button",{name:"View planet",exact:true}).click();
  await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
  const originalCanvas=await canvas.elementHandle(),beforeZoom=await cameraZoom(canvas),beforeTarget=await target(canvas);
  if(!originalCanvas)throw new Error("World canvas did not render");
  await page.getByRole("button",{name:"Enter the world",exact:true}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person",exact:true});
  await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:30000});
  await dialog.getByRole("button",{name:"World map",exact:true}).click();
  const travelButtons=dialog.locator(".world-map-lists").getByRole("button",{name:/Visit/});
  await expect(travelButtons).toHaveCount(world.buildings.length);
  for(const land of LANDMASSES.filter(land=>land.name))
    await expect(dialog.getByRole("button",{name:new RegExp(land.name)})).toHaveCount(0);
  await dialog.getByRole("button",{name:/South Coast Beach.*Visit/}).click();
  await expect(dialog.getByLabel("Your world position",{exact:true})).toHaveText("-150.0, 350.0");
  await dialog.getByRole("button",{name:"Back to dashboard",exact:true}).click();
  await expect(dialog).toHaveCount(0);
  expect(await canvas.evaluate((element,original)=>element===original,originalCanvas)).toBe(true);
  expect(await cameraZoom(canvas)).toBe(beforeZoom);
  expect(await target(canvas)).toEqual(beforeTarget);
  await expect(canvas).toHaveAttribute("data-map-scale","planet");
  await expect(canvas).toHaveAttribute("data-planet-bounds-visible","true");
  await page.locator(".world-stage").screenshot({path:info.outputPath("planet-after-first-person.png")});
  expect(preview.errors).toEqual([]);
  expect(preview.unexpectedRequests).toEqual([]);
});
