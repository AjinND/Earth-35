import {test,expect,type Page,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {organicCityLayout} from "../lib/city-layout";
import {LANDSCAPES} from "../lib/landscapes";
import type {Building,World} from "../lib/types";

type AudioWindow=Window&{__soundContexts:AudioContext[];__soundMeters:AnalyserNode[];__soundHidden:boolean};
async function controlled(page:Page) {
  let world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  world={...world,geography:{...organicCityLayout,version:4},minute:720,weather:"Clear",agents:[]};
  for(const place of LANDSCAPES.viewpoints)if(!world.buildings.some(b=>b.id===place.id))world.buildings.push({...place,type:"wilderness",capacity:100,social:true,restAllowed:true,objects:{},outdoorRadius:0} as Building);
  let socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Sound review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3004/ws/sound-review"}}));
  await page.routeWebSocket("**/ws/sound-review",route=>{socket=route;route.send(JSON.stringify(world));});
  return (minute:number,weather:string)=>{world={...world,minute,weather,version:world.version+1};socket!.send(JSON.stringify(world));};
}
async function inspectAudio(page:Page) {
  await page.addInitScript(()=>{
    const scope=window as unknown as AudioWindow;scope.__soundContexts=[];scope.__soundMeters=[];scope.__soundHidden=false;
    const Existing=window.AudioContext;
    window.AudioContext=class extends Existing {
      constructor(options?:AudioContextOptions){super(options);scope.__soundContexts.push(this);}
      createDynamicsCompressor(){
        const compressor=super.createDynamicsCompressor(),meter=this.createAnalyser();meter.fftSize=512;
        compressor.connect(meter);scope.__soundMeters.push(meter);return compressor;
      }
    };
    // Deterministically exercise the same event used by an inactive browser tab.
    Object.defineProperty(document,"hidden",{get:()=>scope.__soundHidden,configurable:true});
  });
}
async function enter(page:Page) {
  await page.goto("/");await expect(page.getByRole("button",{name:"Enter the world"})).toBeEnabled({timeout:30000});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});
  await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:45000});
  return dialog;
}

test("sound starts on request, renders audio, follows weather and location, and releases its context",async({page},testInfo)=>{
  test.setTimeout(150000);await inspectAudio(page);const update=await controlled(page);
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const dialog=await enter(page),canvas=dialog.locator("canvas");
  await expect(page.getByRole("button",{name:"Enable world sound"})).toHaveAttribute("aria-pressed","false");
  expect(await page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts.length)).toBe(0);
  await page.getByRole("button",{name:"Enable world sound"}).click();
  await expect(page.getByRole("button",{name:"Mute world sound"})).toHaveAttribute("aria-pressed","true");
  await expect(canvas).toHaveAttribute("data-sound-state","running");
  await expect.poll(()=>page.evaluate(()=>{
    const meter=(window as unknown as AudioWindow).__soundMeters[0],samples=new Float32Array(512);
    if(!meter)return 0;meter.getFloatTimeDomainData(samples);return Math.max(...samples.map(Math.abs));
  }),{timeout:10000}).toBeGreaterThan(.00001);
  const volume=page.getByRole("slider",{name:"World sound volume"});
  await volume.focus();await volume.press("Home");for(let i=0;i<12;i++)await volume.press("ArrowRight");
  await expect(volume).toHaveValue("60");expect(await page.evaluate(()=>localStorage.getItem("earth35-world-volume"))).toBe("0.6");
  update(0,"Rain");await expect.poll(async()=>Number(await canvas.getAttribute("data-sound-rain"))).toBeGreaterThan(.7);
  await expect(canvas).toHaveAttribute("data-sound-birds","0.000");await expect(canvas).toHaveAttribute("data-sound-insects","0.000");
  update(0,"Clear");await expect(canvas).toHaveAttribute("data-sound-rain","0.000");
  await expect.poll(async()=>Number(await canvas.getAttribute("data-sound-insects"))).toBeGreaterThan(0);
  const before=Number(await canvas.getAttribute("data-sound-waterfall"));
  await page.getByRole("button",{name:"World map",exact:true}).click();
  const viewpoint=LANDSCAPES.viewpoints.find(p=>/falls/i.test(p.name))!;
  await page.getByRole("button",{name:`Visit ${viewpoint.name}`,exact:true}).click();
  await expect.poll(async()=>Number(await canvas.getAttribute("data-sound-waterfall"))).toBeGreaterThan(before+.1);
  await page.screenshot({path:testInfo.outputPath("world-sound-desktop.png")});
  expect(await page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts.length)).toBe(1);
  await page.evaluate(()=>{(window as unknown as AudioWindow).__soundHidden=true;document.dispatchEvent(new Event("visibilitychange"));});
  await expect.poll(()=>page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts[0].state)).toBe("suspended");
  await page.evaluate(()=>{(window as unknown as AudioWindow).__soundHidden=false;document.dispatchEvent(new Event("visibilitychange"));});
  await expect.poll(()=>page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts[0].state)).toBe("running");
  // A browser/device interruption while still visible must recover in one click.
  await page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts[0].suspend());
  await expect(page.getByRole("button",{name:"Enable world sound"})).toBeVisible();
  await page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts[0].resume());
  await expect.poll(()=>page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts[0].state)).toBe("suspended");
  await page.getByRole("button",{name:"Enable world sound"}).click();await expect(canvas).toHaveAttribute("data-sound-state","running");
  await page.getByRole("button",{name:"Mute world sound"}).click();await expect(canvas).toHaveAttribute("data-sound-state","suspended");
  await page.getByRole("button",{name:"Enable world sound"}).click();await expect(canvas).toHaveAttribute("data-sound-state","running");
  expect(await page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts.length)).toBe(1);
  await page.getByRole("button",{name:"Back to dashboard"}).click();
  await expect.poll(()=>page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts[0].state)).toBe("closed");
  await page.setViewportSize({width:390,height:844});await page.getByRole("button",{name:"Enter the world"}).click();
  await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:45000});
  await page.getByRole("button",{name:"Enable world sound"}).click();await expect(volume).toHaveValue("60");
  await page.screenshot({path:testInfo.outputPath("world-sound-mobile.png")});
  const controls=await page.getByRole("group",{name:"World sound controls"}).boundingBox();
  expect(controls!.x).toBeGreaterThanOrEqual(0);expect(controls!.x+controls!.width).toBeLessThanOrEqual(390);
  await page.getByRole("button",{name:"Back to dashboard"}).click();
  await expect.poll(()=>page.evaluate(()=>(window as unknown as AudioWindow).__soundContexts.every(context=>context.state==="closed"))).toBe(true);
  expect(errors).toEqual([]);
});

test("unsupported audio keeps exploration usable",async({page})=>{
  test.setTimeout(90000);await page.addInitScript(()=>{Object.defineProperty(window,"AudioContext",{value:undefined,configurable:true});Object.defineProperty(window,"webkitAudioContext",{value:undefined,configurable:true});});
  await controlled(page);const dialog=await enter(page);
  await expect(page.getByRole("button",{name:"Enable world sound"})).toBeDisabled();
  await page.getByRole("button",{name:"World map",exact:true}).click();await expect(page.getByLabel("World map and residents")).toBeVisible();
  await page.getByRole("button",{name:"Back to dashboard"}).click();await expect(dialog).toHaveCount(0);
});
