import {test,expect} from "@playwright/test";

test("new memories appear without reselecting the agent and keep the submitted search",async({page,request})=>{
  const world=await (await request.get("/api/v1/world")).json();
  const agent={id:"memory-agent",name:"Alex",description:"Curious resident",home:"apt-101",occupation:"Unemployed",location:"park",x:30,z:30,activity:"Idle",status:"online",fixture:false};
  world.agents=[agent];
  let count=0;
  let revision="";
  const queries:string[]=[];
  const content="Conversation with Maya: We discussed visiting the library in a friendly exchange.";
  await page.route("**/api/v1/auth/me",r=>r.fulfill({json:{id:"owner",email:"memory@example.test"}}));
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3001/ws/memory-test"}}));
  await page.routeWebSocket("**/ws/memory-test",r=>r.send(JSON.stringify(world)));
  await page.route("**/api/v1/owner/agents",r=>r.fulfill({json:[agent]}));
  await page.route("**/api/v1/owner/agents/memory-agent",r=>r.fulfill({json:{
    state:{...agent,ownerId:"owner",personality:"Curious",goals:"Learn",paused:false,energy:80,hunger:10,money:100000,arrears:0,
      routine:{wakeMinute:420,sleepMinute:1380,morningActivity:"reading"},inventory:{meal:3},relationships:{},pending:null},
    memoryCount:count,reportedTokens:0,transactions:[],actions:[],workSchedule:null,socialPlans:[],
  }}));
  await page.route("**/api/v1/owner/agents/memory-agent/runtime",r=>r.fulfill({json:{mode:"external",status:"offline"}}));
  await page.route("**/api/v1/owner/agents/memory-agent/memories/recall",r=>{
    const query=r.request().postDataJSON().query;queries.push(query);
    return r.fulfill({json:count?[{id:"memory",kind:"episodic",content:(count===1?content:content+" We can study gardening.")+revision,worldMinute:720,entities:"[]",sourceEvents:"[1]"}]:[]});
  });
  await page.goto("/dashboard");
  await expect(page.getByText("No matching memories yet.",{exact:false})).toBeVisible();
  count=1;
  await expect(page.locator(".memory-list")).toContainText(content,{timeout:10000});
  await page.getByRole("textbox",{name:"Search memories"}).fill("Maya");
  await page.getByRole("button",{name:"Recall",exact:true}).click();
  await expect.poll(()=>queries.at(-1)).toBe("Maya");
  count=2;
  await expect(page.locator(".memory-list")).toContainText("study gardening",{timeout:10000});
  expect(queries.at(-1)).toBe("Maya");
  await expect(page.getByRole("textbox",{name:"Search memories"})).toHaveValue("Maya");
  revision=" We agreed to meet again.";
  await expect(page.locator(".memory-list")).toContainText("agreed to meet again",{timeout:10000});
  expect(queries.at(-1)).toBe("Maya");
});
