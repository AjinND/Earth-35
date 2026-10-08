import {test,expect} from "@playwright/test";

test("social places are discoverable on the map and expose their amenities",async({page},testInfo)=>{
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  await page.goto("/");
  await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
  await page.getByRole("button",{name:"Browse places"}).click();
  const browser=page.locator(".places-popover");
  await expect(browser.getByRole("button",{name:"Places to meet",exact:true})).toHaveAttribute("aria-pressed","true");
  await expect(browser.locator(".place-row")).toHaveCount(17);
  for(const name of ["Silverstream Riverbank","Silverstream Riverside Walk","Cloudpeak Mountain Lookout","Pinecrest Hill Station","Pine Ridge Trekking Trail","New Eden Galleria","Willow Park","Maple Community Garden","Founders Square","Sunset Walk","Lantern Courtyard","New Eden Community Center","Little Lantern Library","Olive & Oak Bistro"])
    await expect(browser.getByRole("button",{name,exact:true})).toBeAttached();
  await page.screenshot({path:testInfo.outputPath("gathering-places.png"),fullPage:true});
  await browser.getByRole("button",{name:"All places",exact:true}).click();
  await expect(browser.locator(".place-row")).toHaveCount(76);
  await browser.getByRole("button",{name:"Places to meet",exact:true}).click();
  await browser.getByRole("button",{name:"Maple Community Garden",exact:true}).click();
  const inspector=page.locator(".inspect-panel");
  await expect(inspector.getByRole("heading",{name:"Maple Community Garden"})).toBeVisible();
  await expect(inspector.getByText("Communal picnic table",{exact:true})).toBeVisible();
  await expect(inspector.getByText("Rest and seating",{exact:true})).toBeVisible();
  await expect(inspector.getByText("Visitors here",{exact:true})).toBeVisible();
  await page.screenshot({path:testInfo.outputPath("garden-inspector.png"),fullPage:true});
  expect(errors).toEqual([]);
});

test.describe("mobile gathering places",()=>{
  test.use({viewport:{width:390,height:844},hasTouch:true,isMobile:true});
  test("all gathering places can be browsed and selected",async({page},testInfo)=>{
    await page.goto("/");
    await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
    await expect.poll(()=>page.locator(".world-stage canvas").getAttribute("data-phase")).not.toBeNull();
    await page.getByRole("button",{name:"Browse places"}).tap();
    const browser=page.locator(".places-popover");
    await expect(browser.locator(".place-row")).toHaveCount(17);
    const places=await browser.boundingBox(),controls=await page.getByRole("group",{name:"Map camera controls"}).boundingBox();
    expect(places).not.toBeNull();expect(controls).not.toBeNull();
    if(places&&controls)expect(places.y+places.height).toBeLessThanOrEqual(controls.y);
    await page.screenshot({path:testInfo.outputPath("gathering-places-mobile.png"),fullPage:true});
    await browser.getByRole("button",{name:"Willow Park",exact:true}).tap();
    await expect(page.locator(".inspect-panel").getByRole("heading",{name:"Willow Park",exact:true})).toBeVisible();
  });
});
