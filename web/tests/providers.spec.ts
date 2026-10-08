import {test,expect} from "@playwright/test";

const models={nvidia:"openai/gpt-oss-20b",openrouter:"openai/gpt-4.1-mini",openai:"gpt-4.1-mini",anthropic:"claude-haiku-4-5"};

for(const provider of ["openrouter","openai","anthropic"] as const) {
  test(`${provider} registration and dashboard provider switching`,async ({page},testInfo)=>{
    test.skip(!process.env.E35_WEB_URL?.includes(":3001"),"Runs only against the isolated mock providers");
    if(provider==="anthropic") await page.setViewportSize({width:390,height:844});
    await page.goto("/register");
    await page.getByRole("button",{name:"Sign in to register"}).click();
    await page.getByRole("button",{name:"New here? Create an account"}).click();
    await page.getByLabel("Email address").fill(`providers-${provider}-${Date.now()}@example.test`);
    await page.getByLabel("Password").fill("one-very-long-test-password");
    await page.getByRole("button",{name:"Create account"}).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.getByLabel("Agent name").fill(`Browser ${provider}`);
    await page.getByLabel("Public description").fill("A resident with a choice of model provider.");
    await page.getByLabel(/Personality/).fill("Curious");
    await page.getByLabel(/Goals/).fill("Explore New Eden");
    const selector=page.getByLabel("AI provider");
    await expect(selector.locator("option")).toHaveCount(4);
    await expect(page.getByRole("textbox",{name:/^Model ID/})).toHaveValue(models.nvidia);
    await page.getByLabel(/Provider API key/).fill("key-that-must-not-cross-providers");
    await selector.selectOption(provider);
    await expect(page.getByLabel(/Provider API key/)).toHaveValue("");
    await expect(page.getByRole("textbox",{name:/^Model ID/})).toHaveValue(models[provider]);
    if(provider==="openrouter") await page.getByRole("textbox",{name:/^Model ID/}).fill(`${models.openrouter}:free`);
    await page.getByLabel(/Provider API key/).fill(`test-${provider}-key`);
    await page.getByRole("button",{name:"Connect provider and create agent"}).click();
    await expect(page.getByRole("heading",{name:`Browser ${provider} has a home in New Eden.`})).toBeVisible();
    await expect(page.getByText("Your agent token")).toHaveCount(0);
    await page.getByRole("link",{name:"View agent dashboard"}).click();
    await expect(page.getByRole("heading",{name:"Hosted agent runtime"})).toBeVisible();
    await page.getByText("Change provider, model or API key",{exact:true}).click();
    await expect(page.getByLabel("AI provider")).toHaveValue(provider);
    await expect(page.getByRole("textbox",{name:/^Model ID/})).toHaveValue(models[provider]+(provider==="openrouter"?":free":""));
    await page.getByLabel("AI provider").selectOption("nvidia");
    await expect(page.getByRole("textbox",{name:/^Model ID/})).toHaveValue(models.nvidia);
    await page.getByLabel(/Provider API key/).fill("wrong-key");
    await page.getByRole("button",{name:"Verify and save connection"}).click();
    await expect(page.getByRole("alert").filter({hasText:"NVIDIA rejected"})).toBeVisible();
    await page.getByLabel(/Provider API key/).fill("test-nvidia-key");
    await page.getByRole("button",{name:"Verify and save connection"}).click();
    await expect(page.getByLabel("AI provider")).toHaveValue("nvidia");
    await expect(page.getByLabel(/Provider API key/)).toHaveValue("");
    await expect(page.getByRole("alert").filter({hasText:"NVIDIA rejected"})).toHaveCount(0);
    await page.screenshot({path:testInfo.outputPath(`${provider}-connection.png`),fullPage:true});
    await page.reload();
    await expect(page.getByText("Earth 35 runs this agent using your NVIDIA API Catalog account.",{exact:false})).toBeVisible();
  });
}

test("provider presets can be cleared and edited after switching",async ({page})=>{
  await page.goto("/register");
  await expect(page.getByLabel("AI provider")).toBeVisible();
  await page.getByLabel("AI provider").selectOption("openrouter");
  const model=page.getByRole("textbox",{name:/^Model ID/});
  await model.fill("");await expect(model).toHaveValue("");
  await model.fill("custom/model:free");await expect(model).toHaveValue("custom/model:free");
  await page.getByLabel("AI provider").selectOption("anthropic");
  await expect(model).toHaveValue(models.anthropic);
});
