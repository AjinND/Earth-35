import {test,expect,type Page} from "@playwright/test";
import type {ObservedProblem} from "../lib/types";

const agent={id:"maker",name:"Alex",description:"A practical learner",home:"apt-101",occupation:"Founder",location:"workshop",x:18,z:54,activity:"BUILD_PROJECT",status:"online",fixture:false};
const peer={...agent,id:"peer",name:"Maya",activity:"Idle"};
const concept="Measured comparisons support reliable decisions.";
const project={id:"project-care",founder:agent.id,title:"Rain comfort kit",subject:"science",problem:"Residents get wet while walking to their workplace in rain.",description:`${concept} Design a practical care kit for commuters.`,location:"workshop",recipe:"weather-care",members:[agent.id,peer.id],status:"built",productId:"product:project-care",createdMinute:530,concepts:[concept],businessEndorsements:[agent.id],businessId:"business-care",
  research:[{id:"finding-test",project:"project-care",subject:"science",method:"test",lessonKey:"science:weather-care:test",concept:"A comparison records observed change.",result:"Prototype test changed wetness from 75 to 15.",minute:570,evidence:{wetnessBefore:75,wetnessAfter:15}}]};
const product={id:project.productId,project:project.id,title:project.title,subject:"science",recipe:"weather-care",description:"An agent-built care kit for wet commuters.",unitCost:700,effects:"Wetness -60; rain protection for 120 minutes",createdMinute:560};
const business={id:"business-care",project:project.id,name:"New Eden Rain Care",location:"workshop",owners:[agent.id,peer.id],productId:product.id,stock:4,priceCents:1900,sales:3,revenueCents:5700,balanceCents:25100,contributedCents:20000,availableProfitCents:5100,jobId:"job:business-care",createdMinute:580};
const innovations={recipes:[{id:"weather-care",title:"Weather care",subjects:["science"],unitCost:700,buildMinutes:60,effects:product.effects}],projects:[project],products:[product],businesses:[business],interests:["science","engineering"],
  interestedResidents:[{id:peer.id,name:peer.name,interests:["science"]}],problems:[{id:"wetness",kind:"wetness",description:"Current wetness is 75 during rain.",subjects:["science"],evidence:{wetness:75}}],
  sharedConditions:[{id:"weather",kind:"weather",description:"Current city weather is Rain at 22°C.",subjects:["environment"],evidence:{rainNow:1,temperatureC:22}},{id:"community-needs",kind:"community-needs",description:"One resident currently has high wetness.",subjects:["science"],evidence:{residents:2,hungryResidents:0,lowEnergyResidents:0,wetResidents:1,mealStock:100}}],
  invitations:[{id:"invite-care",project:project.id,inviter:agent.id,invited:peer.id,message:"Would you research rain protection with me? We can discuss equal co-ownership if the tested product works.",status:"accepted",minute:535}]};
const world={minute:600,version:1,tickMs:5000,weather:"Rain",temperature:22,agents:[agent,peer],events:[],buildings:[{id:"workshop",name:"New Eden Workshop",type:"workshop",district:"Central",x:18,z:54,capacity:10,objects:{},social:false,restAllowed:false}],jobs:[],innovations};

async function fixture(page:Page,owner:boolean,problems:ObservedProblem[]=innovations.problems) {
  const needs={hunger:20,energy:70,wetness:0,...Object.fromEntries(problems.flatMap(problem=>Object.entries(problem.evidence).filter(([key])=>["hunger","energy","wetness"].includes(key))))};
  await page.route("**/api/v1/auth/me",route=>route.fulfill({json:owner?{id:"owner",email:"maker@example.test"}:null}));
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/innovation-test"}}));
  await page.routeWebSocket("**/ws/innovation-test",socket=>socket.send(JSON.stringify(world)));
  if(!owner)return;
  await page.route("**/api/v1/owner/agents",route=>route.fulfill({json:[agent]}));
  await page.route("**/api/v1/owner/agents/maker",route=>route.fulfill({json:{
    state:{...agent,ownerId:"owner",personality:"Curious and practical",goals:"Build useful protection and support a local business",paused:false,...needs,money:75000,arrears:0,routine:{wakeMinute:420,sleepMinute:1380,morningActivity:"reading"},nightPlan:null,relationships:{},inventory:{[product.id]:2},pending:{id:"build",type:"BUILD_PROJECT",target:project.id,until:630},interests:innovations.interests},
    memoryCount:0,reportedTokens:0,transactions:[],actions:[],workSchedule:null,socialPlans:[],innovations:{...innovations,problems}}}));
  await page.route("**/api/v1/owner/agents/maker/runtime",route=>route.fulfill({json:{mode:"external",provider:"",model:"",status:"offline",error:"",callsToday:0,tokensToday:0,totalCalls:0,totalTokens:0,lastRunAt:null}}));
  await page.route("**/api/v1/owner/agents/maker/memories/recall",route=>route.fulfill({json:[]}));
}

for(const mobile of [false,true]) {
  test(`owner observes real projects, research and funded businesses ${mobile?"on mobile":"on desktop"}`,async({page},info)=>{
    if(mobile)await page.setViewportSize({width:390,height:844});
    const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
    await fixture(page,true);await page.goto("/dashboard");
    const panel=page.getByRole("region",{name:"Projects and businesses"});
    await expect(panel).toContainText("Building a prototype · Rain comfort kit · 30 minutes remaining");
    await expect(panel).toContainText("science, engineering");await expect(panel).toContainText("Current wetness is 75 during rain.");
    const personal=panel.getByRole("region",{name:"Current personal problems"});
    await expect(personal).toContainText("Wetness75 / 100");await expect(personal).not.toContainText("Current city weather");
    const shared=panel.getByRole("region",{name:"Shared city conditions"});
    await expect(shared).toContainText("Current city weather is Rain at 22°C.");await expect(shared).toContainText("Temperature22°C");
    await expect(shared).toContainText("Residents with high wetness1");
    await expect(panel).toContainText("Each resident decides independently.");await expect(panel).toContainText("Collaborators: Alex, Maya");
    await expect(panel).toContainText("Business consent: 1/2 members endorsed co-ownership");
    await panel.getByText("Research (1 findings)",{exact:true}).click();
    await expect(panel).toContainText("Prototype test changed wetness from 75 to 15.");await expect(panel).toContainText("wetnessBefore");
    await expect(panel).toContainText("2 owned units");await expect(panel).toContainText("World effect: Wetness -60");
    await expect(panel).toContainText("4 units in stock");await expect(panel).toContainText("19.00 E · 3 units sold");
    await expect(panel).toContainText("Business funds251.00 E");await expect(panel).toContainText("Sales revenue57.00 E");
    await expect(panel).toContainText("Available profit51.00 E");await expect(panel).toContainText("job:business-care");
    await expect(panel.getByRole("button")).toHaveCount(0);
    await expect(panel.getByRole("link",{name:"New Eden Workshop"}).first()).toHaveAttribute("href","/?resident=workshop");
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
    await panel.screenshot({path:info.outputPath("projects-businesses.png")});expect(errors).toEqual([]);
  });

  test(`public community work shows products and shops without private details ${mobile?"on mobile":"on desktop"}`,async({page},info)=>{
    if(mobile)await page.setViewportSize({width:390,height:844});
    const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
    await fixture(page,false);await page.goto("/innovations");
    const community=page.locator(".community-innovations");
    await expect(community).toContainText("Ideas taking shape.");await expect(community).toContainText(project.problem);
    await expect(community).toContainText("Collaborators: Alex, Maya");await expect(community).toContainText("Wetness -60; rain protection for 120 minutes");
    await expect(community).toContainText("New Eden Rain Care");await expect(community).toContainText("4 units in stock");
    await expect(community).not.toContainText("Business funds");await expect(community).not.toContainText("Sales revenue");await expect(community).not.toContainText("251.00 E");
    await expect(community).not.toContainText("Prototype test changed wetness");await expect(community).not.toContainText(innovations.invitations[0].message);
    await expect(community.getByRole("link",{name:"New Eden Workshop"}).first()).toHaveAttribute("href","/?resident=workshop");
    await community.getByRole("searchbox").fill("no matching work");await expect(community).toContainText("No matching projects.");await expect(community).toContainText("No matching products.");
    await community.getByRole("searchbox").fill("science");await expect(community.getByRole("region",{name:"Community projects"})).toContainText(project.title);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
    await community.screenshot({path:info.outputPath("community-work.png")});expect(errors).toEqual([]);
  });
}

test("an agent without personal problems has an empty list while city facts remain shared",async({page})=>{
  await fixture(page,true,[]);await page.goto("/dashboard");
  const personal=page.getByRole("region",{name:"Current personal problems"});
  await expect(personal).toContainText("No current personal problems detected.");await expect(personal.getByRole("listitem")).toHaveCount(0);
  await expect(personal).not.toContainText("Study support");await expect(personal).not.toContainText("Current city weather");
  await expect(page.getByRole("region",{name:"Shared city conditions"})).toContainText("Current city weather is Rain at 22°C.");
  await fixture(page,true,[{id:"hunger",kind:"hunger",description:"Current hunger is 80 / 100.",subjects:["biology"],evidence:{hunger:80}}]);await page.reload();
  await expect(personal).toContainText("Hunger80 / 100");await expect(personal).not.toContainText("No current personal problems detected.");
  await expect(personal).not.toContainText("Wetness75 / 100");
});
