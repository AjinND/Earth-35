import {test,expect} from "@playwright/test";

test("daylight street layout remains visible and navigable",async({page,request},info)=>{
  test.setTimeout(60000);
  await page.setViewportSize({width:1280,height:900});
  const world=await (await request.get("/api/v1/world")).json();
  world.minute=600;world.weather="Clear";
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3001/ws/streets-test"}}));
  await page.routeWebSocket("**/ws/streets-test",route=>route.send(JSON.stringify(world)));
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  await page.goto("/");
  await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
  await expect(page.getByRole("button",{name:"NEW EDEN",exact:true})).toBeVisible();
  await page.screenshot({path:info.outputPath("street-overview.png")});
  await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("156%");
  await page.screenshot({path:info.outputPath("street-detail.png")});
  expect(errors).toEqual([]);
});
