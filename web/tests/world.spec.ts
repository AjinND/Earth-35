import {test,expect} from "@playwright/test";

test("public world renders persistent city data and its five routes",async ({page},testInfo)=>{
  const errors:string[]=[];
  page.on("pageerror",error=>errors.push(error.message));
  await page.goto("/");
  await expect(page.getByRole("heading",{name:"Life, unfolding."})).toBeVisible();
  await expect(page.locator(".world-stats").getByText("Residents",{exact:true})).toBeVisible();
  await expect(page.getByText("Places to meet",{exact:true})).toBeVisible();
  await expect(page.locator(".world-stage canvas, .city-fallback")).toBeVisible({timeout:15000});
  await page.screenshot({path:testInfo.outputPath("world-desktop.png"),fullPage:true});
  for(const [href,title] of [["/agents","Independent minds."],["/events","Every day, a new story."],["/register","A new life starts here."],["/dashboard","Your agents. Their lives."]] as const){
    await page.goto(href);
    await expect(page.getByRole("heading",{name:new RegExp(title.split(".")[0])})).toBeVisible();
  }
  expect(errors).toEqual([]);
});

test("registration and private dashboard require an owner session",async ({page})=>{
  await page.goto("/register");
  await expect(page.getByRole("button",{name:"Sign in to register"})).toBeVisible();
  await page.getByRole("button",{name:"Sign in to register"}).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByRole("heading",{name:"Welcome back."})).toBeVisible();
  await page.getByRole("button",{name:"Close sign in"}).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.goto("/dashboard");
  await expect(page.getByText("A private view into your agents’ world")).toBeVisible();
});

test("mobile observer remains navigable",async ({page},testInfo)=>{
  await page.setViewportSize({width:390,height:844});
  await page.goto("/");
  await expect(page.getByRole("heading",{name:"Life, unfolding."})).toBeVisible();
  await expect(page.locator(".world-stage canvas, .city-fallback")).toBeVisible({timeout:30000});
  await page.getByRole("button",{name:"Toggle navigation"}).click();
  await expect(page.getByRole("link",{name:"Agent explorer"})).toBeVisible();
  await page.getByRole("link",{name:"Agent explorer"}).click();
  await expect(page.getByRole("heading",{name:/Independent minds/})).toBeVisible();
  await page.screenshot({path:testInfo.outputPath("world-mobile.png"),fullPage:true});
});

test("owner registers an external agent and sees private dashboard",async ({page})=>{
  test.skip(!process.env.E35_WEB_URL?.includes(":3001"),"Runs only against the isolated test world");
  await page.goto("/register");
  await page.getByRole("button",{name:"Sign in to register"}).click();
  await page.getByRole("button",{name:"New here? Create an account"}).click();
  await page.getByRole("textbox",{name:"Email address"}).fill(`browser-${Date.now()}@example.test`);
  await page.getByLabel("Password").fill("one-very-long-test-password");
  await page.getByRole("button",{name:"Create account"}).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("textbox",{name:"Agent name"}).fill("Browser Test Agent");
  await page.getByRole("textbox",{name:"Public description"}).fill("A test resident controlled by an external runtime.");
  await page.getByLabel("Home preference").selectOption("house");
  await page.getByRole("textbox",{name:/Personality/}).fill("Curious and patient");
  await page.getByRole("textbox",{name:/Goals/}).fill("Find stable employment");
  await page.getByRole("button",{name:/I run my own agent/}).click();
  await page.getByRole("button",{name:"Register external agent"}).click();
  await expect(page.getByRole("heading",{name:"Browser Test Agent has a home in New Eden."})).toBeVisible();
  await expect(page.getByText("Your agent token")).toBeVisible();
  await page.goto("/dashboard");
  await expect(page.getByRole("heading",{name:"Browser Test Agent"})).toBeVisible();
  await expect(page.getByText("Curious and patient")).toBeVisible();
  await expect(page.getByRole("heading",{name:"Let Earth 35 run this agent"})).toBeVisible();
  await page.getByRole("textbox",{name:/Provider API key/}).fill("test-nvidia-key");
  await page.getByRole("button",{name:"Connect and start hosted agent"}).click();
  await expect(page.getByRole("heading",{name:"Hosted agent runtime"})).toBeVisible();
});

test("owner connects NVIDIA once and Earth 35 runs the hosted agent",async ({page})=>{
  test.skip(!process.env.E35_WEB_URL?.includes(":3001"),"Runs only against the isolated test provider");
  await page.goto("/register");
  await page.getByRole("button",{name:"Sign in to register"}).click();
  await page.getByRole("button",{name:"New here? Create an account"}).click();
  await page.getByRole("textbox",{name:"Email address"}).fill(`hosted-browser-${Date.now()}@example.test`);
  await page.getByLabel("Password").fill("one-very-long-test-password");
  await page.getByRole("button",{name:"Create account"}).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("textbox",{name:"Agent name"}).fill("Hosted Browser Agent");
  await page.getByRole("textbox",{name:"Public description"}).fill("An independent hosted resident.");
  await page.getByRole("textbox",{name:/Personality/}).fill("Curious and kind");
  await page.getByRole("textbox",{name:/Goals/}).fill("Explore New Eden");
  await page.getByRole("textbox",{name:/Provider API key/}).fill("test-nvidia-key");
  await page.getByRole("button",{name:"Connect provider and create agent"}).click();
  await expect(page.getByRole("heading",{name:"Hosted Browser Agent has a home in New Eden."})).toBeVisible();
  await expect(page.getByText("there is nothing to install or start on your device",{exact:false})).toBeVisible();
  await expect(page.getByText("Your agent token")).toHaveCount(0);
  await page.getByRole("link",{name:"View agent dashboard"}).click();
  await expect(page.getByRole("heading",{name:"Hosted agent runtime"})).toBeVisible();
  await expect(page.getByText("openai/gpt-oss-20b")).toBeVisible();
});
