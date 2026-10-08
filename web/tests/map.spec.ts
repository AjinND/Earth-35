import {test,expect,type Page} from "@playwright/test";

test.setTimeout(120000);

async function zoomPercent(page:Page) {
  return Number((await page.getByLabel("Map zoom").textContent())?.replace("%",""));
}

async function checkMapControls(page:Page) {
  await page.goto("/");
  await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
  const zoom=page.getByLabel("Map zoom"),zoomIn=page.getByRole("button",{name:"Zoom in",exact:true});
  const zoomOut=page.getByRole("button",{name:"Zoom out",exact:true}),reset=page.getByRole("button",{name:"Reset camera"});
  await expect(zoom).toHaveText("100%");
  await page.getByRole("button",{name:"Browse places"}).click();
  await page.locator(".places-popover").getByRole("button",{name:"Common Ground",exact:true}).click();
  await expect(page.locator(".inspect-panel").getByRole("heading",{name:"Common Ground",exact:true})).toBeVisible();
  await expect(zoom).toHaveText("1200%");

  // Labels must remain screen-sized after a camera change, not cover the map/UI.
  await zoomIn.click();
  await expect(zoom).toHaveText("1500%");
  const labelSizes=await page.locator(".world-canvas .map-label").evaluateAll(labels=>labels.map(label=>{
    const box=label.getBoundingClientRect();return {width:box.width,height:box.height};
  }));
  expect(labelSizes.length).toBeGreaterThan(0);
  for(const box of labelSizes){expect(box.width).toBeLessThan(220);expect(box.height).toBeLessThan(60);}
  await zoomOut.click();
  await expect(zoom).toHaveText("1200%");
  await page.getByRole("button",{name:"Close inspector"}).click();
  await reset.click();await expect(zoom).toHaveText("100%");

  // Wheel events over an interactive label must reach the orbit controller.
  const park=page.locator(".park-label").first();
  await park.hover();await page.mouse.wheel(0,-400);
  await expect.poll(()=>zoomPercent(page)).toBeGreaterThan(100);
  await park.hover();await page.mouse.wheel(0,400);
  await expect.poll(()=>zoomPercent(page)).toBeLessThanOrEqual(100);
  await reset.click();await expect(zoom).toHaveText("100%");

  // Both limits are reversible, with controls still clickable above labels.
  await page.emulateMedia({reducedMotion:"reduce"});
  for(let i=0;i<32&&await zoomIn.isEnabled();i++)await zoomIn.click();
  await expect(zoom).toHaveText("18000%");await expect(zoomIn).toBeDisabled();
  const largestLabel=await page.locator(".world-canvas .map-label").evaluateAll(labels=>Math.max(...labels.map(label=>label.getBoundingClientRect().height)));
  expect(largestLabel).toBeLessThan(60);
  await expect(zoomOut).toBeEnabled();await zoomOut.click();
  await expect(zoom).toHaveText("14400%");
  for(let i=0;i<100&&await zoomOut.isEnabled();i++)await zoomOut.click();
  const worldCanvas=page.locator(".world-stage canvas");
  await expect(worldCanvas).toHaveAttribute("data-map-scale","planet");
  await expect(zoomOut).toBeDisabled();
  const minimumZoom=Number(await worldCanvas.getAttribute("data-camera-zoom"));
  expect(minimumZoom).toBeGreaterThan(0);
  await expect(zoomIn).toBeEnabled();await zoomIn.click();
  await expect.poll(async()=>Number(await worldCanvas.getAttribute("data-camera-zoom"))).toBeGreaterThan(minimumZoom);
  await reset.click();await expect(zoom).toHaveText("100%");

  const home=await park.boundingBox(),canvas=await page.locator(".world-stage canvas").boundingBox();
  expect(home).not.toBeNull();expect(canvas).not.toBeNull();
  if(!home||!canvas)throw new Error("Map did not render");
  await page.mouse.move(canvas.x+canvas.width*.45,canvas.y+canvas.height*.5);
  await page.mouse.down();
  await page.mouse.move(canvas.x+canvas.width*.7,canvas.y+canvas.height*.65,{steps:8});
  await page.mouse.up();
  await expect.poll(async()=>Math.abs((await park.boundingBox())!.x-home.x)).toBeGreaterThan(15);
  await reset.click();await expect(zoom).toHaveText("100%");
  await expect.poll(async()=>Math.abs((await park.boundingBox())!.x-home.x)).toBeLessThan(2);
  await expect.poll(async()=>Math.abs((await park.boundingBox())!.y-home.y)).toBeLessThan(2);
  await page.mouse.move(canvas.x+canvas.width*.45,canvas.y+canvas.height*.5);
  await page.mouse.down({button:"right"});
  await page.mouse.move(canvas.x+canvas.width*.65,canvas.y+canvas.height*.55,{steps:8});
  await page.mouse.up({button:"right"});
  await expect.poll(async()=>Math.abs((await park.boundingBox())!.x-home.x)).toBeGreaterThan(5);
  await reset.click();
  await expect.poll(async()=>Math.abs((await park.boundingBox())!.x-home.x)).toBeLessThan(2);
  await expect.poll(async()=>Math.abs((await park.boundingBox())!.y-home.y)).toBeLessThan(2);
}

test("world map zoom, wheel, limits and reset remain usable",async({page},testInfo)=>{
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  await checkMapControls(page);
  // Resizing should preserve the current relative zoom; reset fits the new size.
  await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await page.setViewportSize({width:390,height:844});
  await expect(page.getByLabel("Map zoom")).toHaveText("125%");
  await page.getByRole("button",{name:"Reset camera"}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("100%");
  await page.screenshot({path:testInfo.outputPath("map-resized.png"),fullPage:true});
  expect(errors).toEqual([]);
});

test.describe("touch-sized world map",()=>{
  test.use({viewport:{width:390,height:844},hasTouch:true,isMobile:true});
  test("mobile zoom and reset stay reachable",async({page},testInfo)=>{
    await checkMapControls(page);
    const canvas=await page.locator(".world-stage canvas").boundingBox();
    if(!canvas)throw new Error("Map did not render");
    const session=await page.context().newCDPSession(page);
    const x=canvas.x+canvas.width/2,y=canvas.y+canvas.height*.45;
    const fingers=(distance:number)=>[{id:1,x:x-distance,y},{id:2,x:x+distance,y}];
    await session.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:fingers(25)});
    for(const distance of [35,45,55,65])await session.send("Input.dispatchTouchEvent",{type:"touchMove",touchPoints:fingers(distance)});
    await session.send("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});
    await expect.poll(()=>zoomPercent(page)).toBeGreaterThan(100);
    await session.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:fingers(65)});
    for(const distance of [55,45,35,25])await session.send("Input.dispatchTouchEvent",{type:"touchMove",touchPoints:fingers(distance)});
    await session.send("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});
    await expect.poll(()=>zoomPercent(page)).toBeLessThanOrEqual(100);
    await session.detach();
    await page.getByRole("button",{name:"Reset camera"}).tap();
    await expect(page.getByLabel("Map zoom")).toHaveText("100%");
    await page.getByRole("button",{name:"Zoom in",exact:true}).tap();
    await expect(page.getByLabel("Map zoom")).toHaveText("125%");
    await page.getByRole("button",{name:"Zoom out",exact:true}).tap();
    await expect(page.getByLabel("Map zoom")).toHaveText("100%");
    await page.screenshot({path:testInfo.outputPath("map-mobile.png"),fullPage:true});
  });
});
