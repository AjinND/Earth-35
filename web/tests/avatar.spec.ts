import {test,expect,type WebSocketRoute} from "@playwright/test";
import {avatarStyle,avatarMotion} from "../lib/avatar";
import type {World} from "../lib/types";

test("avatar identity is stable and actions select distinct poses",()=>{
  expect(avatarStyle("resident-1")).toEqual(avatarStyle("resident-1"));
  const styles=new Set(Array.from({length:50},(_,i)=>{const {coat,accent,longEars}=avatarStyle(`resident-${i}`);return JSON.stringify({coat,accent,longEars});}));
  expect(styles.size).toBeGreaterThan(15);
  for(const [activity,motion] of [["MOVE","Walk"],["RUN","Run"],["JUMP","Jump"],["REST","Rest"],["SLEEP","Sleep"],["WORK","Work"],["Idle","Idle"]])expect(avatarMotion(activity)).toBe(motion);
});

test("plush portraits, live poses and animation previews preserve real activity",async({page,request},testInfo)=>{
  test.setTimeout(90000);
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  let world:World={...await (await request.get("/api/v1/world")).json(),minute:720,weather:"Clear",tickMs:5000};
  world.agents=[{id:"avatar-test",name:"Clover",description:"A curious resident",home:"apt-101",occupation:"Explorer",location:"road",x:0,z:0,activity:"Idle",status:"paused",fixture:true}];
  let socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/avatar-test"}}));
  await page.routeWebSocket("**/ws/avatar-test",r=>{socket=r;r.send(JSON.stringify(world));});
  await page.goto("/agents");
  await expect(page.getByRole("img",{name:"Clover's fluffy avatar"})).toBeVisible();
  await page.locator(".resident-card").click();
  await expect(page.locator(".avatar-preview-stage canvas")).toBeVisible();
  await expect(page.locator(".clock-pace")).toHaveText("12×");
  await expect(page.locator(".world-clock")).toHaveAttribute("title",/one day lasts 120 real minutes/);
  await expect.poll(()=>!!socket).toBe(true);
  const preview=page.getByLabel("Avatar animation preview");
  for(const motion of ["Walk","Run","Jump","Rest","Sleep","Work","Wave","Idle"]){
    await preview.selectOption(motion);
    await expect(page.locator(".avatar-preview-stage")).toHaveAttribute("data-motion",motion);
    await expect(page.locator(".inspect-panel dd").filter({hasText:/^idle$/})).toBeVisible();
    if(motion==="Idle"||motion==="Sleep")await page.locator(".inspect-panel").screenshot({path:testInfo.outputPath(`plush-${motion.toLowerCase()}.png`)});
  }
  await preview.selectOption("Live");
  for(const [activity,motion] of [["RUN","Run"],["JUMP","Jump"],["SLEEP","Sleep"]]){
    world={...world,version:world.version+1,agents:[{...world.agents[0],activity}]};socket!.send(JSON.stringify(world));
    await expect(page.locator(".avatar-preview-stage")).toHaveAttribute("data-motion",motion);
  }
  await page.emulateMedia({reducedMotion:"reduce"});
  await page.setViewportSize({width:390,height:844});
  await preview.selectOption("Idle");
  await page.screenshot({path:testInfo.outputPath("plush-mobile.png")});
  expect(errors).toEqual([]);
});
