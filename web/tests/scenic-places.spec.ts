import {test,expect} from "@playwright/test";
import {scenicWalkable} from "../lib/scenic-layout";
import type {Building} from "../lib/types";

const destinations=[
  ["Silverstream Riverbank","Riverbank lookout bench"],
  ["Silverstream Riverside Walk","Riverside walking-path bench"],
  ["Cloudpeak Mountain Lookout","Mountain-view resting bench"],
  ["Pinecrest Hill Station","Hill-station lookout bench"],
  ["Pine Ridge Trekking Trail","Trekking trail rest bench"],
  ["New Eden Galleria","Mall atrium lounge sofa"],
];

test("scenic leisure destinations render and expose optional visits and seating",async({page,request},testInfo)=>{
  test.setTimeout(120000);
  await page.setViewportSize({width:1440,height:1000});
  const response=await request.get("/api/v1/world");expect(response.ok()).toBe(true);
  const world={...await response.json(),minute:720,weather:"Clear",temperature:25};
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/scenic-test"}}));
  await page.routeWebSocket("**/ws/scenic-test",route=>route.send(JSON.stringify(world)));
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  await page.goto("/");
  await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
  await expect.poll(async()=>Number(await page.locator(".world-stage canvas").getAttribute("data-daylight")),{timeout:20000}).toBeGreaterThan(.99);
  const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-river-flow",/\d/,{timeout:20000});
  const flow=Number(await canvas.getAttribute("data-river-flow"));
  await expect.poll(async()=>Number(await canvas.getAttribute("data-river-flow"))).toBeGreaterThan(flow+.1);
  for(const [name,seat] of destinations) {
    await page.getByRole("button",{name:"Browse places"}).click();
    await page.locator(".places-popover").getByRole("button",{name,exact:true}).click();
    const inspector=page.locator(".inspect-panel");
    await expect(inspector.getByRole("heading",{name,exact:true})).toBeVisible();
    await expect(inspector.getByText(seat,{exact:true})).toBeVisible();
    await expect(inspector.getByText("Rest and seating",{exact:true})).toBeVisible();
    if(name!=="New Eden Galleria") {
      // A generic building label cannot satisfy this regression check.
      await expect(page.locator('.map-label[data-place-kind="natural"]').filter({hasText:name})).toBeVisible();
      await expect(page.getByLabel("Map zoom")).not.toHaveText("100%");
      await page.locator(".world-stage").screenshot({path:testInfo.outputPath(`${name.replaceAll(" ","-")}.png`)});
    }
    await page.getByRole("button",{name:"Close inspector"}).click();
  }
  await page.emulateMedia({reducedMotion:"reduce"});
  // Allow the media-query event to reach React before sampling the rendered current.
  await page.waitForTimeout(400);
  const stopped=await canvas.getAttribute("data-river-flow");
  await page.waitForTimeout(600);
  await expect(canvas).toHaveAttribute("data-river-flow",stopped!);
  await page.getByRole("button",{name:"Reset camera"}).click();
  await page.screenshot({path:testInfo.outputPath("scenic-city.png"),fullPage:true});
  expect(errors).toEqual([]);
});

test("scenic walking stays on dry riverbanks and clear lookout paths",()=>{
  const places=[{type:"river",x:66,z:18},{type:"riverside_walk",x:66,z:30},{type:"mountain",x:6,z:-54},{type:"hill_station",x:18,z:-54}] as Building[];
  expect(scenicWalkable({x:66,z:18},places)).toBe(true);
  expect(scenicWalkable({x:66,z:30},places)).toBe(true);
  expect(scenicWalkable({x:70,z:24},places)).toBe(false);
  expect(scenicWalkable({x:6,z:-54},places)).toBe(true);
  expect(scenicWalkable({x:6,z:-66},places)).toBe(false);
  expect(scenicWalkable({x:18,z:-66},places)).toBe(false);
});
