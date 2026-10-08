import {test,expect} from "@playwright/test";

test("deployed expansion has larger parks, new neighborhoods and visible construction",async({page},testInfo)=>{
  test.setTimeout(120000);await page.setViewportSize({width:1536,height:960});
  const response=await page.request.get("/api/v1/world");expect(response.ok()).toBe(true);const world=await response.json();
  expect(world.buildings.length).toBe(168);expect(world.buildings.filter((b:{type:string})=>b.type==="construction").length).toBe(10);
  expect(world.buildings.find((b:{id:string})=>b.id==="town-square").outdoorRadius).toBe(20);
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  await page.goto("/");const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-expanded-destinations","17",{timeout:45000});
  await expect(canvas).toHaveAttribute("data-construction-sites","10");
  await page.getByRole("button",{name:"View settlement",exact:true}).click();
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("growing-city-live.png")});
  await page.getByRole("button",{name:"Browse places"}).click();await page.getByRole("button",{name:"Founders Square",exact:true}).click();
  await expect(page.locator(".inspect-panel")).toContainText("Founders Square");
  await expect(canvas).toHaveAttribute("data-target-x",world.buildings.find((b:{id:string})=>b.id==="town-square").x.toFixed(3));
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("founders-square-live.png")});
  await page.getByRole("button",{name:"Browse places"}).click();await page.getByRole("button",{name:"All places",exact:true}).click();
  await page.getByRole("button",{name:"Founders Offices · Site 8",exact:true}).click();
  await expect(page.locator(".inspect-panel")).toContainText("Under construction · Closed to visitors");
  await expect(canvas).toHaveAttribute("data-target-x",world.buildings.find((b:{id:string})=>b.id==="construction-208").x.toFixed(3));
  await page.locator(".world-stage").screenshot({path:testInfo.outputPath("construction-live.png")});
  await page.getByRole("button",{name:"Enter the world"}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:30000});
  for(const destination of [{name:/Meadow Commons.*Visit/,position:"-416.0, 12.0"},{name:/South Gardens.*Visit/,position:"-220.0, 290.0"}]) {
    await page.getByRole("button",{name:"World map",exact:true}).click();await page.getByRole("button",{name:destination.name}).click();
    await expect(page.getByLabel("Your world position")).toHaveText(destination.position);
    await page.keyboard.down("w");await expect(page.getByLabel("Your world position")).not.toHaveText(destination.position);await page.keyboard.up("w");
  }
  await page.getByRole("button",{name:"Back to dashboard"}).click();expect(errors).toEqual([]);
});
