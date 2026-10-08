import {defineConfig,devices} from "@playwright/test";
export default defineConfig({
  testDir:"./tests",
  timeout:30000,
  retries:0,
  reporter:"list",
  use:{baseURL:process.env.E35_WEB_URL||"http://127.0.0.1:3000",trace:"retain-on-failure",launchOptions:process.env.PLAYWRIGHT_CHROMIUM?{executablePath:process.env.PLAYWRIGHT_CHROMIUM}:{}},
  projects:[{name:"chromium",use:{...devices["Desktop Chrome"]}}],
});
