import {test,expect,type Page,type WebSocketRoute} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import sharp from "sharp";
import {lightningForSlot,lightningAt,lightningFlash,lightningSegments,thunderAt,StormPlayback,STORM_PERIOD} from "../lib/storm";
import {organicCityLayout} from "../lib/city-layout";
import type {World} from "../lib/types";

test("storm strikes are shared, bounded, irregular and exclusive to rain",()=>{
  const gaps:number[]=[];
  for(let id=-10;id<200;id++) {
    const strike=lightningForSlot(id),next=lightningForSlot(id+1);
    expect(strike).toEqual(lightningForSlot(id));expect(strike.minute).toBeGreaterThan(id*STORM_PERIOD+2);
    expect(strike.minute).toBeLessThan(id*STORM_PERIOD+6);
    expect(lightningAt(strike.minute,"Rain")).toEqual(strike);expect(lightningAt(strike.minute-.01,"Rain")).toBeNull();
    expect(lightningAt(strike.minute,"Clear")).toBeNull();expect(lightningAt(strike.minute,"Overcast")).toBeNull();
    gaps.push(next.minute-strike.minute);
    const geometry=lightningSegments(strike);expect(geometry.length).toBe(108);expect([...geometry].every(Number.isFinite)).toBe(true);
    expect(strike.energy).toBeGreaterThanOrEqual(.7);expect(strike.energy).toBeLessThanOrEqual(1);
  }
  expect(new Set(gaps.map(g=>g.toFixed(2))).size).toBeGreaterThan(20);
  expect(Math.min(...gaps)*5).toBeGreaterThan(20);
});

test("lightning is one short bounded pulse and distant thunder is softer, delayed and directional",()=>{
  const strike=lightningForSlot(0),near={x:strike.x+50,z:strike.z,yaw:0},far={...near,x:strike.x+450};
  const nearby=thunderAt(strike,near),distant=thunderAt(strike,far);
  expect(nearby.delay).toBeGreaterThan(1);expect(distant.delay).toBeGreaterThan(nearby.delay);
  expect(distant.gain).toBeLessThan(nearby.gain);expect(distant.cutoff).toBeLessThan(nearby.cutoff);
  expect(nearby.pan).toBe(-1);expect(thunderAt(strike,{...near,yaw:Math.PI}).pan).toBeCloseTo(1);
  expect(thunderAt(strike,near,.5).delay).toBeCloseTo(nearby.delay-.5);
  expect(lightningFlash(0)).toBe(0);expect(lightningFlash(.1)).toBe(1);expect(lightningFlash(1.25)).toBe(0);
  expect(lightningFlash(NaN)).toBe(0);expect(lightningFlash(-1)).toBe(0);
  for(let age=0;age<2;age+=.01){expect(lightningFlash(age)).toBeGreaterThanOrEqual(0);expect(lightningFlash(age)).toBeLessThanOrEqual(1);}
});

function advancingStorm() {
  const strike=lightningForSlot(0),base=Math.floor(strike.minute),clock=new StormPlayback();
  clock.ingest(base,5000,"Rain",0);clock.sample(0);
  clock.ingest(base+1,5000,"Rain",1);
  const when=(strike.minute-base)*5000+1;
  for(let time=100;time<when;time+=100)clock.sample(time);
  return {clock,strike,when};
}
test("received intervals trigger once and flashes finish without extrapolating new storms",()=>{
  const {clock,strike,when}=advancingStorm();expect(clock.sample(when+100).strike?.id).toBe(strike.id);
  expect(clock.sample(when+150).flash).toBeGreaterThan(0);
  for(let time=when+250;time<when+4000;time+=100)clock.sample(time);
  expect(clock.sample(when+4000).flash).toBe(0);
  for(let time=when+4100;time<when+40000;time+=100)clock.sample(time);
  expect(clock.sample(when+40000).strike).toBeNull();
  const resumed=new StormPlayback();resumed.ingest(Math.ceil(strike.minute),5000,"Rain",0);expect(resumed.sample(100).strike).toBeNull();
});
test("weather transitions, missing snapshots and hidden tabs do not replay past strikes",()=>{
  const {clock,strike,when}=advancingStorm();clock.sample(when+100);
  clock.ingest(Math.ceil(strike.minute),5000,"Clear",when+200);expect(clock.sample(when+200).strike).toBeNull();
  clock.ingest(Math.ceil(strike.minute),5000,"Rain",when+300);expect(clock.sample(when+300).strike).toBeNull();
  const jump=new StormPlayback();jump.ingest(0,5000,"Rain",0);jump.sample(0);jump.ingest(100,5000,"Rain",1000);expect(jump.sample(1100).strike).toBeNull();
  const hidden=advancingStorm();hidden.clock.sample(hidden.when+100,true);expect(hidden.clock.sample(hidden.when+200).strike).toBeNull();
  const frozen=advancingStorm();expect(frozen.clock.sample(frozen.when+20000).strike).toBeNull();
});

test("accelerated clocks keep flashes at least twelve real seconds apart",()=>{
  const clock=new StormPlayback(),events:{id:number;time:number}[]=[];
  clock.ingest(0,100,"Rain",0);clock.sample(0);
  for(let time=25;time<=30000;time+=25) {
    if(time%100===0)clock.ingest(time/100,100,"Rain",time);
    const frame=clock.sample(time);
    if(frame.strike&&events.at(-1)?.id!==frame.strike.id)events.push({id:frame.strike.id,time});
  }
  expect(events.length).toBeGreaterThanOrEqual(2);expect(events.length).toBeLessThanOrEqual(3);
  for(let i=1;i<events.length;i++)expect(events[i].time-events[i-1].time).toBeGreaterThanOrEqual(12000);
});

type StormWindow=Window&{__stormContexts:AudioContext[];__stormScheduled:{start:number;created:number;meter:AnalyserNode;stopped:boolean}[];__stormPeak:{flash:number;brightness:number;png:string};__stormAudiblePeak:number};
async function controlled(page:Page) {
  const strike=lightningForSlot(0),base=Math.floor(strike.minute);
  let world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  world={...world,geography:{...organicCityLayout,version:4},minute:base,weather:"Rain",agents:[]};
  let socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Storm review"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3006/ws/storm-review"}}));
  await page.routeWebSocket("**/ws/storm-review",route=>{socket=route;route.send(JSON.stringify(world));});
  return {strike,update:(minute:number,weather="Rain")=>{world={...world,minute,weather,version:world.version+1};socket!.send(JSON.stringify(world));}};
}
async function inspectThunder(page:Page) {
  await page.addInitScript(()=>{
    const scope=window as unknown as StormWindow;scope.__stormContexts=[];scope.__stormScheduled=[];
    const Existing=window.AudioContext;
    window.AudioContext=class extends Existing {
      constructor(options?:AudioContextOptions){super(options);scope.__stormContexts.push(this);}
      createBufferSource() {
        const source=super.createBufferSource(),start=source.start.bind(source),stop=source.stop.bind(source);
        let record:StormWindow["__stormScheduled"][number]|undefined;
        source.start=(when=0,offset=0,duration?:number)=>{
          if(when>this.currentTime+.2) {
            const meter=this.createAnalyser();meter.fftSize=512;source.connect(meter);
            record={start:when,created:this.currentTime,meter,stopped:false};scope.__stormScheduled.push(record);
          }
          if(duration===undefined)start(when,offset);else start(when,offset,duration);
        };
        source.stop=(when=0)=>{if(record&&when<=this.currentTime+.05)record.stopped=true;stop(when);};
        return source;
      }
    };
  });
}

for(const mobile of [false,true])test(`rain lightning and delayed thunder work on ${mobile?"mobile with reduced motion":"desktop"}`,async({page},info)=>{
  test.setTimeout(120000);await page.setViewportSize(mobile?{width:390,height:844}:{width:1440,height:900});
  await page.emulateMedia({reducedMotion:mobile?"reduce":"no-preference"});await inspectThunder(page);
  const {strike,update}=await controlled(page),errors:string[]=[];
  page.on("pageerror",error=>errors.push(error.message));page.on("console",message=>{if(message.type()==="error"&&/THREE|shader|WebGL/i.test(message.text()))errors.push(message.text());});
  await page.goto("/");const map=page.locator(".world-stage canvas");await expect(map).toHaveAttribute("data-storm-weather","Rain",{timeout:45000});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const canvas=page.getByRole("dialog").locator("canvas");await expect(canvas).toHaveAttribute("data-controls-ready","true",{timeout:45000});
  await expect(canvas).toHaveAttribute("data-lightning-flash","0.000");
  expect(await page.evaluate(()=>(window as unknown as StormWindow).__stormContexts.length)).toBe(0);
  await page.getByRole("button",{name:"Enable world sound"}).click();await expect(canvas).toHaveAttribute("data-sound-state","running");
  // Aim above the street, toward this known distant strike, to inspect actual bolt pixels.
  const x=strike.x-organicCityLayout.centerX,z=strike.z-(organicCityLayout.centerZ+8),yaw=Math.atan2(-x,-z),pitch=.75;
  const box=(await canvas.boundingBox())!;const sx=box.x+box.width/2,sy=box.y+box.height/2;
  await page.mouse.move(sx,sy);await page.mouse.down();await page.mouse.move(sx-yaw/.004,sy-pitch/.004,{steps:8});await page.mouse.up();
  const crop={left:mobile?20:100,top:mobile?230:160,width:mobile?300:400,height:160};
  const before=await sharp(await page.screenshot()).extract(crop).removeAlpha().raw().toBuffer();
  // Capture the short pulse inside a render frame; later assertions must not miss it.
  await page.evaluate(crop=>{
    const scope=window as unknown as StormWindow;scope.__stormPeak={flash:0,brightness:0,png:""};scope.__stormAudiblePeak=0;
    const inspect=()=>{
      const canvas=document.querySelector<HTMLCanvasElement>("dialog canvas");
      if(!canvas)return;
      const flash=Number(canvas.dataset.lightningFlash??0);
      if(flash>.05) {
        const gl=canvas.getContext("webgl2")??canvas.getContext("webgl");
        if(gl) {
          const scale=canvas.width/canvas.clientWidth,width=Math.round(crop.width*scale),height=Math.round(crop.height*scale),pixels=new Uint8Array(width*height*4);
          gl.readPixels(Math.round(crop.left*scale),canvas.height-Math.round((crop.top+crop.height)*scale),width,height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
          let sum=0;for(let i=0;i<pixels.length;i+=4)sum+=pixels[i]+pixels[i+1]+pixels[i+2];
          const brightness=sum/(width*height*3),strongest=Math.max(flash,scope.__stormPeak.flash);
          if(brightness>scope.__stormPeak.brightness)scope.__stormPeak={flash:strongest,brightness,png:canvas.toDataURL("image/png")};
          else scope.__stormPeak.flash=strongest;
        }
      }
      for(const sound of scope.__stormScheduled) {
        const samples=new Float32Array(512);sound.meter.getFloatTimeDomainData(samples);
        scope.__stormAudiblePeak=Math.max(scope.__stormAudiblePeak,...samples.map(Math.abs));
      }
      requestAnimationFrame(inspect);
    };
    requestAnimationFrame(inspect);
  },crop);
  update(Math.floor(strike.minute)+1);
  await expect(canvas).toHaveAttribute("data-lightning-id",String(strike.id),{timeout:10000});
  await expect(canvas).toHaveAttribute("data-thunder-id",String(strike.id));
  await expect(canvas).toHaveAttribute("data-thunder-voices","1");
  expect(await page.evaluate(()=>(window as unknown as StormWindow).__stormScheduled.length)).toBe(2);
  const delay=Number(await canvas.getAttribute("data-thunder-delay"));expect(delay).toBeGreaterThan(.8);expect(delay).toBeLessThanOrEqual(9);
  if(mobile) {
    await expect(canvas).toHaveAttribute("data-lightning-flash","0.000");await expect(canvas).toHaveAttribute("data-lightning-bolt-visible","false");
    await page.getByRole("button",{name:"Mute world sound"}).click();await expect(canvas).toHaveAttribute("data-thunder-voices","0");
    expect(await page.evaluate(()=>(window as unknown as StormWindow).__stormScheduled.every(sound=>sound.stopped))).toBe(true);
    await page.getByRole("button",{name:"Enable world sound"}).click();await expect(canvas).toHaveAttribute("data-sound-state","running");
    expect(await page.evaluate(()=>(window as unknown as StormWindow).__stormScheduled.length)).toBe(2);
  }else {
    await expect.poll(()=>page.evaluate(()=>(window as unknown as StormWindow).__stormPeak.flash)).toBeGreaterThan(.2);
    const peak=await page.evaluate(()=>(window as unknown as StormWindow).__stormPeak);
    expect(peak.brightness-before.reduce((sum,value)=>sum+value,0)/before.length).toBeGreaterThan(2);
    fs.writeFileSync(info.outputPath("lightning-flash.png"),Buffer.from(peak.png.split(",")[1],"base64"));
    // A scheduled source remains silent until its distance-based delay, then produces real samples.
    await expect.poll(()=>page.evaluate(()=>(window as unknown as StormWindow).__stormAudiblePeak),{timeout:11000}).toBeGreaterThan(.01);
  }
  update(Math.floor(strike.minute)+1,"Clear");await expect(canvas).toHaveAttribute("data-lightning-id","");await expect(canvas).toHaveAttribute("data-thunder-voices","0");
  await expect(canvas).toHaveAttribute("data-lightning-bolt-visible","false");
  await page.screenshot({path:info.outputPath(mobile?"reduced-motion-mobile.png":"storm-cleared.png")});
  await page.getByRole("button",{name:"Back to dashboard"}).click();
  await expect.poll(()=>page.evaluate(()=>(window as unknown as StormWindow).__stormContexts.every(context=>context.state==="closed"))).toBe(true);
  await expect(map).toHaveAttribute("data-storm-weather","Clear");expect(errors).toEqual([]);
});
