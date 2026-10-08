import {test,expect,type WebSocketRoute} from "@playwright/test";
import type {World} from "../lib/types";

test("indoor occupants keep their profile and visitor count without a floating doorway avatar label",async({page,request},info)=>{
  test.setTimeout(60000);
  let world:World={...await (await request.get("/api/v1/world")).json(),minute:720,weather:"Clear",tickMs:200};
  const coffee=world.buildings.find(b=>b.id==="coffee")!;
  world.agents=[{id:"entering-test",name:"Doorway visitor",description:"Visiting the cafe",home:"apt-101",occupation:"Explorer",location:"road",x:coffee.entrance!.x,z:coffee.entrance!.z,activity:"MOVE",status:"online",fixture:true}];
  let socket:WebSocketRoute|undefined;
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/entry-test"}}));
  await page.routeWebSocket("**/ws/entry-test",r=>{socket=r;r.send(JSON.stringify(world));});
  await page.goto("/agents");await page.locator(".resident-card").click();
  await expect(page.locator(".world-stage [data-avatar-motion]")).toHaveText("Doorway visitor");
  await expect.poll(()=>!!socket).toBe(true);
  world={...world,version:world.version+1,agents:[{...world.agents[0],location:coffee.id,x:coffee.x,z:coffee.z,activity:"Idle"}]};
  socket!.send(JSON.stringify(world));
  await expect(page.locator(".inspect-panel")).toContainText("Inside · Common Ground");
  await expect(page.locator(".world-stage [data-avatar-motion]")).toHaveCount(0);
  await expect(page.locator(".avatar-preview-stage canvas")).toBeVisible();
  await page.getByRole("button",{name:"Close inspector"}).click();
  await page.getByRole("button",{name:"Browse places"}).click();
  const place=page.locator(".places-popover").getByRole("button",{name:"Common Ground",exact:true});
  await expect(place).toContainText("1 here");await place.click();
  await expect(page.locator(".inspect-panel").getByRole("button",{name:"Doorway visitor"})).toBeVisible();
  await page.screenshot({path:info.outputPath("visitor-inside.png")});
  expect(errors).toEqual([]);
});
