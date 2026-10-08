import {test,expect} from "@playwright/test";

test("deployed organic island renders and all wilderness regions are explorable",async({page},testInfo)=>{
  test.setTimeout(120000);
  await page.setViewportSize({width:1536,height:960});
  const response=await page.request.get("/api/v1/world");expect(response.ok()).toBe(true);
  const world=await response.json();expect(world.geography.version).toBe(2);
  expect(world.buildings.length).toBeGreaterThanOrEqual(80);
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  await page.goto("/");
  const canvas=page.locator(".world-stage canvas");await expect(canvas).toBeVisible({timeout:45000});
  await expect(canvas).toHaveAttribute("data-river-course","mountain-to-sea",{timeout:30000});
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("organic-island-live.png")});
  await page.getByRole("button",{name:"View settlement",exact:true}).click();
  await expect(page.getByLabel("Map zoom")).toHaveText("200%");
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("organic-settlement-live.png")});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});
  await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:30000});
  for(const destination of [
    {name:/Cloudpeak Mountain Lookout.*Visit/,position:"-225.0, -450.0"},
    {name:/Western Woodland.*Visit/,position:"-595.0, -100.0"},
    {name:/South Coast Beach.*Visit/,position:"-150.0, 350.0"},
    {name:/Eastern Shore.*Visit/,position:"250.0, -80.0"},
  ]) {
    await page.getByRole("button",{name:"World map",exact:true}).click();
    await page.getByRole("button",{name:destination.name}).click();
    await expect(page.getByLabel("Your world position")).toHaveText(destination.position);
    await page.keyboard.down("w");
    await expect(page.getByLabel("Your world position")).not.toHaveText(destination.position);
    await page.keyboard.up("w");
  }
  await page.getByRole("button",{name:"Back to dashboard"}).click();expect(errors).toEqual([]);
});
