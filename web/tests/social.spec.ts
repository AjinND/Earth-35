import {test,expect} from "@playwright/test";

for(const mobile of [false,true])test(`shared plans and chat pauses are visible to owners ${mobile?"on mobile":"on desktop"}`,async({page,request},info)=>{
  if(mobile)await page.setViewportSize({width:390,height:844});
  const world=await (await request.get("/api/v1/world")).json();
  const alex={id:"social-alex",name:"Alex",description:"A social resident",home:"apt-101",occupation:"Unemployed",location:"road",x:2.4,z:2.4,activity:"CHAT",status:"online",fixture:false};
  const maya={...alex,id:"social-maya",name:"Maya",activity:"Idle",x:4.4};
  world.agents=[alex,maya];
  const plans=[
    {id:"agreed",proposer:alex.id,invited:maya.id,destination:"coffee",activity:"Catch up over coffee",status:"accepted",meetingMinute:world.minute+15,expires:world.minute+75},
    {id:"outgoing",proposer:alex.id,invited:maya.id,destination:"park",activity:"Discuss weekend ideas",status:"proposed",meetingMinute:world.minute+30,expires:world.minute+90},
    {id:"incoming",proposer:maya.id,invited:alex.id,destination:"library",activity:"Read together",status:"proposed",meetingMinute:world.minute+45,expires:world.minute+105},
  ];
  await page.route("**/api/v1/auth/me",route=>route.fulfill({json:{id:"social-owner",email:"social@example.test"}}));
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/social-ui-test"}}));
  await page.routeWebSocket("**/ws/social-ui-test",route=>route.send(JSON.stringify(world)));
  await page.route("**/api/v1/owner/agents",route=>route.fulfill({json:[alex]}));
  await page.route("**/api/v1/owner/agents/social-alex",route=>route.fulfill({json:{
    state:{...alex,ownerId:"social-owner",personality:"Sociable but considerate",goals:"Build friendships",paused:false,energy:80,hunger:10,money:100000,arrears:0,
      routine:{wakeMinute:420,sleepMinute:1380,morningActivity:"breakfast"},nightPlan:null,inventory:{meal:2},relationships:{[maya.id]:"close friend"},
      pending:{id:"original-walk",type:"MOVE",target:"office",waitingReason:"Paused to chat with Maya",socialWith:maya.id}},
    memoryCount:2,reportedTokens:25,transactions:[],actions:[],workSchedule:null,socialPlans:plans,
  }}));
  await page.route("**/api/v1/owner/agents/social-alex/runtime",route=>route.fulfill({json:{mode:"hosted",provider:"nvidia",model:"test-model",status:"running",error:"",callsToday:1,tokensToday:25,totalCalls:1,totalTokens:25,lastRunAt:null}}));
  await page.route("**/api/v1/owner/agents/social-alex/memories/recall",route=>route.fulfill({json:[]}));
  await page.goto("/dashboard");
  const panel=page.getByRole("region",{name:"Shared plans"});
  await expect(panel.getByText("Catch up over coffee")).toBeVisible();
  await expect(panel.getByText("accepted",{exact:true})).toBeVisible();
  await expect(panel.getByText("Awaiting reply",{exact:true})).toBeVisible();
  await expect(panel.getByText("Invitation received",{exact:true})).toBeVisible();
  await expect(panel).toContainText("With Maya");await expect(panel).toContainText("Common Ground");
  await expect(panel.getByRole("button")).toHaveCount(0);
  await expect(page.getByText("Chatting",{exact:true})).toBeVisible();
  await expect(page.getByText("Paused to chat with Maya",{exact:true})).toBeVisible();
  await panel.screenshot({path:info.outputPath("shared-plans.png")});
});
