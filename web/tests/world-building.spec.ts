import {test,expect,type Page,type WebSocketRoute} from "@playwright/test";
import {constructionProgress} from "../lib/construction";
import {avatarMotion} from "../lib/avatar";
import {walkable,arrival} from "../components/ImmersiveWorld";
import {prototypeLayout} from "../lib/city-layout";
import type {World,ConstructionSiteView} from "../lib/types";

const builder={id:"builder",name:"Maya",description:"Building a useful home",home:"house",occupation:"Builder",location:"road",x:18,z:57.6,activity:"BUILD_CONSTRUCTION",status:"online",fixture:false};
const installer={...builder,id:"installer",name:"Ravi",x:27,z:55,activity:"PLACE_OBJECT",occupation:"Founder"};
const site:ConstructionSiteView={id:"site:home",name:"Maya’s House",plannedType:"house",sponsor:"builder",status:"building",completedHours:5,requiredHours:16,reservedHours:1,hourlyWageCents:2000,acceptingWorkers:true,entranceTarget:"point:18,57.6",workStartMinute:360,workEndMinute:1200,unreservedBudgetCents:30000,laborMode:"paid",completedWorkMinutes:330,requiredWorkMinutes:960,workers:[{id:builder.id,name:builder.name,workMinutes:30,requiredMinutes:60,selfBuild:false}]};
const board={id:"object:advert",owner:installer.id,kind:"advertisement",text:"Ravi’s fresh bread",business:"bread",x:27,z:58,status:"installed"};
const world:World={minute:600,version:1,tickMs:5000,weather:"Clear",temperature:25,agents:[builder,installer],events:[],buildings:[{id:site.id,name:site.name,type:"construction",plannedType:"house",district:"Development",x:18,z:54,capacity:0,objects:{},social:false,restAllowed:false,description:"A resident-funded home"}],economy:{nextRestockMinute:1800,lastSettlementMinute:-1,subsidyTodayCents:0,totalSubsidyCents:0},objects:[board],construction:{options:[],sites:[site]}};
const financing={offers:[{termYears:3,annualSimpleInterestBasisPoints:500,installments:36},{termYears:5,annualSimpleInterestBasisPoints:500,installments:60}],outstandingCents:30000,overdueCents:1000,borrowingLimitCents:970000,loans:[{id:"loan:home",purpose:"construction:site:home",principalCents:30000,interestCents:4500,totalRepaymentCents:34500,paidCents:4500,outstandingCents:30000,overdueCents:1000,termYears:3,installments:36,installmentCents:959,startedMinute:60,nextDueMinute:43860,maturityMinute:1576860,status:"overdue"}]};

async function fixture(page:Page,owner=false) {
  let socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/auth/me",route=>route.fulfill({json:owner?{id:"owner",email:"owner@example.test"}:null}));
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3015/ws/building-test"}}));
  await page.routeWebSocket("**/ws/building-test",route=>{socket=route;route.send(JSON.stringify(world));});
  if(owner){
    await page.route("**/api/v1/owner/agents",route=>route.fulfill({json:[builder]}));
    await page.route("**/api/v1/owner/agents/builder",route=>route.fulfill({json:{state:{...builder,ownerId:"owner",personality:"Practical",goals:"Build a home",paused:false,hunger:20,energy:70,wetness:0,money:3000,arrears:0,routine:null,nightPlan:null,relationships:{},inventory:{},pending:{id:"labor",type:"BUILD_CONSTRUCTION",target:site.id,until:630}},memoryCount:0,reportedTokens:0,transactions:[],actions:[],workSchedule:null,socialPlans:[],livelihood:{financing,construction:world.construction}}}));
    await page.route("**/api/v1/owner/agents/builder/runtime",route=>route.fulfill({json:{mode:"external",provider:"",model:"",status:"offline",error:"",callsToday:0,tokensToday:0,totalCalls:0,totalTokens:0,lastRunAt:null}}));
    await page.route("**/api/v1/owner/agents/builder/memories/recall",route=>route.fulfill({json:[]}));
  }
  return ()=>socket;
}

test("construction follows earned work, and installed signs block walking",()=>{
  expect(constructionProgress({...site,completedWorkMinutes:330})).toMatchObject({completed:330,required:960,percent:34});
  expect(constructionProgress({...site,completedWorkMinutes:960})).toMatchObject({fraction:1,percent:100});
  expect(constructionProgress()).toMatchObject({fraction:0,percent:0});
  expect(avatarMotion("BUILD_CONSTRUCTION")).toBe("Build");expect(avatarMotion("PLACE_OBJECT")).toBe("Work");expect(avatarMotion("MOVE_OBJECT")).toBe("Work");
  expect(walkable(board,[],prototypeLayout,[board])).toBe(false);
  expect(walkable(board,[],prototypeLayout,[{...board,status:"pending"}])).toBe(true);
  expect(walkable({...board,x:board.x+1.1},[],prototypeLayout,[board])).toBe(true);
  expect(arrival(board,[],prototypeLayout,[board])).not.toEqual({x:board.x,z:board.z});
});

test("public sites show working residents, live progress, and installed advertisement boards",async({page},info)=>{
  test.setTimeout(120000);await page.setViewportSize({width:1536,height:960});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const socket=await fixture(page);await page.goto("/?resident=site%3Ahome");
  const canvas=page.locator(".world-stage canvas[data-city-buildings]"),inspector=page.locator(".inspect-panel");
  await expect(canvas).toHaveAttribute("data-active-construction-workers","1",{timeout:60000});
  await expect(canvas).toHaveAttribute("data-placed-objects","1");
  await expect(inspector.getByRole("progressbar")).toHaveAttribute("value","34");
  await expect(inspector).toContainText("Building now: Maya");await expect(inspector).toContainText("5.5 / 16 labor hours completed");
  await page.locator(".world-stage").screenshot({path:info.outputPath("construction-active-workers.png")});
  const siteSocket=socket();await page.goto("/?resident=builder");await expect.poll(()=>socket()!==siteSocket).toBe(true);await expect(page.locator(".avatar-preview-stage")).toHaveAttribute("data-motion","Build");
  await expect(canvas).toHaveAttribute("data-target-x","18.000");await expect(page.locator(".map-label[data-avatar-motion='Build']")).toBeVisible();
  await page.locator(".world-stage").screenshot({path:info.outputPath("construction-resident-working.png")});
  socket()!.send(JSON.stringify({...world,version:2,minute:601,construction:{options:[],sites:[{...site,workers:[]}]},agents:[{...builder,status:"paused"},installer]}));
  await expect(page.locator(".avatar-preview-stage")).toHaveAttribute("data-motion","Idle");
  const builderSocket=socket();await page.goto("/?resident=site%3Ahome");await expect.poll(()=>socket()!==builderSocket).toBe(true);
  await expect.poll(()=>!!socket()).toBe(true);
  socket()!.send(JSON.stringify({...world,version:2,minute:601,construction:{options:[],sites:[{...site,completedWorkMinutes:800,workers:[]}]},agents:[{...builder,activity:"Idle"},installer]}));
  await expect(inspector.getByRole("progressbar")).toHaveAttribute("value","83");await expect(inspector).toContainText("No residents currently building");
  await page.locator(".world-stage").screenshot({path:info.outputPath("construction-later-stage.png")});
  await page.goto("/?resident=object%3Aadvert");await expect(inspector).toContainText("Ravi’s fresh bread");await expect(inspector).toContainText("Placed byRavi");
  await expect(canvas).toHaveAttribute("data-target-x","27.000");await page.locator(".world-stage").screenshot({path:info.outputPath("advertisement-board.png")});
  await page.getByRole("button",{name:"Enter the world"}).click();await page.getByRole("button",{name:"World map",exact:true}).click();
  await expect(page.getByRole("button",{name:/Ravi’s fresh bread.*Visit/})).toBeVisible();expect(errors).toEqual([]);
});

for(const mobile of [false,true])test(`owners see financed obligations and construction ${mobile?"on mobile":"on desktop"}`,async({page},info)=>{
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});await fixture(page,true);await page.goto("/dashboard");
  const panel=page.getByRole("region",{name:"Loans and world building"});await expect(panel).toContainText("Outstanding debt300.00 E");await expect(panel).toContainText("Overdue payments10.00 E");
  await expect(panel).toContainText("3 years · 36 installments");await expect(panel).toContainText("Amount borrowed300.00 E");await expect(panel).toContainText("Total repayment345.00 E");
  await expect(panel).toContainText("Building now: Maya");await expect(panel.getByRole("progressbar")).toHaveAttribute("value","34");
  await panel.getByText("Available loan terms",{exact:true}).click();await expect(panel).toContainText("5 world years · 60 installments · 5% annual simple interest");
  await expect(panel.getByRole("button")).toHaveCount(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await panel.screenshot({path:info.outputPath("private-loans-construction.png")});
});
