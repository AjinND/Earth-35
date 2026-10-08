import {test,expect,type Page} from "@playwright/test";
import type {World} from "../lib/types";

async function worldAt(page:Page,world:World) {
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3001/ws/traffic-test"}}));
  await page.routeWebSocket("**/ws/traffic-test",route=>route.send(JSON.stringify(world)));
  await page.goto("/");
  await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
}

test("cars buses signals and compatible stop departures appear on the map",async({page,request},info)=>{
  test.setTimeout(60000);
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  const world:World=await (await request.get("/api/v1/world")).json();
  expect(world.transport!.stops).toHaveLength(5);expect(world.transport!.routes).toHaveLength(2);
  if(!world.transport!.vehicles.some(v=>v.type==="bus"))world.transport!.vehicles.push({id:"bus:R1:0:600",type:"bus",route:"R1",name:"City loop clockwise",x:-47.35,z:-48,heading:0,stop:"stop-residential",seatsAvailable:20});
  // Freeze the display to an actual API snapshot and a known departure for the panel.
  world.transport!.departures=[{bus:"bus:R1:0:600",route:"R1",stop:"stop-residential",arrivalMinute:600,departureMinute:601,remainingStops:["stop-downtown","stop-market","stop-park","stop-garden","stop-residential"],seatsAvailable:20}];
  await worldAt(page,world);
  await expect(page.locator(".bus-route-label")).toHaveCount(0);
  expect(world.transport!.signals).toHaveLength(8);
  await expect(page.locator(".world-stage canvas")).toHaveAttribute("data-signals","8");
  await expect(page.locator(".world-stage canvas")).toHaveAttribute("data-buses",String(world.transport!.vehicles.filter(v=>v.type==="bus").length));
  await expect(page.locator(".world-stage canvas")).toHaveAttribute("data-vehicles",String(world.transport!.vehicles.length));
  await page.getByRole("button",{name:"Browse places",exact:true}).click();
  await page.getByRole("button",{name:"All places",exact:true}).click();
  await page.getByRole("button",{name:"Residential bus stop",exact:true}).click();
  const timetable=page.getByRole("region",{name:"Bus timetable"});
  await expect(timetable.getByText("R1 · City loop clockwise",{exact:true})).toBeVisible();
  await expect(timetable.getByText("R2 · City loop anticlockwise",{exact:true})).toBeVisible();
  await expect(timetable.getByText("R1 · 10:01",{exact:true})).toBeVisible();
  await expect(timetable.getByText(/20 seats/)).toBeVisible();
  await page.screenshot({path:info.outputPath("traffic-and-timetable.png")});
  expect(errors).toEqual([]);
});

test("agents choose their daily routine while owners can only view it",async({page},info)=>{
  const request=page.request;
  test.skip(!process.env.E35_WEB_URL?.includes(":3001"),"This test creates a resident in the disposable test world only.");
  test.setTimeout(60000);
  const headers={"X-E35-Client":"web"};
  const registered=await request.post("/api/v1/auth/register",{headers,data:{email:`routine-${Date.now()}@example.test`,password:"one-very-long-test-password"}});
  expect(registered.ok()).toBe(true);
  const created=await request.post("/api/v1/agents",{headers,data:{name:"Routine Owner Test",description:"Routine UI regression",personality:"Early bird who enjoys jogging",goals:"Find work",homeType:"any",fixture:false}});
  expect(created.ok()).toBe(true);
  const registeredAgent=await created.json(),id=registeredAgent.agent.id;
  const preference={wakeMinute:345,sleepMinute:1305,morningActivity:"meditation"};
  const chosen=await request.put("http://127.0.0.1:8081/v1/agent/routine",{headers:{"X-E35-Client":"agent",Authorization:`Bearer ${registeredAgent.token}`},data:preference});
  expect(chosen.ok()).toBe(true);
  const ownerOverride=await request.put(`/api/v1/owner/agents/${id}/routine`,{headers,data:preference});
  expect(ownerOverride.status()).toBe(404);
  await page.goto("/dashboard");
  await expect(page.locator(".routine-panel")).toBeVisible();
  await expect(page.getByText("Adjust routine",{exact:true})).toHaveCount(0);
  await expect(page.getByLabel("Wake time",{exact:true})).toHaveCount(0);
  await expect(page.locator(".routine-panel")).toContainText("Sleep 21:45–05:45 · Morning: meditation");
  const detail=await (await request.get(`/api/v1/owner/agents/${id}`)).json();
  expect(detail.state.routine).toEqual({wakeMinute:345,sleepMinute:1305,morningActivity:"meditation"});
  await page.screenshot({path:info.outputPath("owner-routine.png")});
});
