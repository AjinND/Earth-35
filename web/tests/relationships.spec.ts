import {test,expect} from "@playwright/test";
import savedWorld from "./fixtures/organic-world.json";

for(const mobile of [false,true])test(`private relationships preserve personal feelings and evidence ${mobile?"on mobile":"on desktop"}`,async({page},info)=>{
  if(mobile)await page.setViewportSize({width:390,height:844});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const agent={id:"alex",name:"Alex",description:"A curious resident",home:"apt-101",occupation:"Unemployed",location:"park",x:0,z:0,activity:"REST",status:"online",fixture:false};
  const sam={...agent,id:"sam",name:"Sam"},ada={...agent,id:"ada",name:"Ada"};
  const world={...savedWorld,minute:1500,agents:[agent,sam,ada]};
  const recent={id:"chat-2",kind:"conversation",actor:"sam",description:"Sam listened while Alex described a difficult morning.",minute:1480};
  const earlier={id:"chat-1",kind:"conversation",actor:"alex",description:"Alex and Sam introduced themselves at the park.",minute:700};
  const bonds={
    sam:{interactionCount:2,lastInteractionMinute:1480,appraisedMinute:1490,
      appraisal:{label:"outdated label",feeling:"Comfortable but still cautious",reason:"Their patience helped me feel heard, but we have only spoken twice.",evidence:[recent.id],warmth:65,trust:0,respect:72,closeness:null,attraction:null,tension:15},
      evidence:[recent],history:[{minute:710,appraisal:{label:"new acquaintance",feeling:"Curious",reason:"We have just met, so I know very little about them.",evidence:[earlier.id],warmth:30},evidence:[earlier]}]},
    "absent-resident":{name:"Riley",interactionCount:1,lastInteractionMinute:800,appraisedMinute:-1,evidence:[{id:"expired-plan",kind:"plan_expired",actor:null,description:"A plan with Riley expired before they met.",minute:800}],history:[]},
  };
  let populated=true;
  await page.route("**/api/v1/auth/me",r=>r.fulfill({json:{id:"owner",email:"relationships@example.test"}}));
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/relationships-test"}}));
  await page.routeWebSocket("**/ws/relationships-test",r=>r.send(JSON.stringify(world)));
  await page.route("**/api/v1/hosted/config",r=>r.fulfill({json:{available:false,providers:[],defaultProvider:"",defaultModel:"",providerOptions:[]}}));
  await page.route("**/api/v1/owner/agents",r=>r.fulfill({json:[agent]}));
  await page.route("**/api/v1/owner/agents/alex",r=>r.fulfill({json:{
    state:{...agent,ownerId:"owner",personality:"Curious",goals:"Get to know the neighborhood",paused:false,energy:80,hunger:10,money:100000,arrears:0,
      routine:null,nightPlan:null,pending:null,inventory:{meal:2},relationships:populated?{sam:"Getting to know them",ada:"old neighbor"}:{},...(populated?{bonds}:{})},
    memoryCount:0,reportedTokens:0,transactions:[],actions:[],workSchedule:null,socialPlans:[],
  }}));
  await page.route("**/api/v1/owner/agents/alex/runtime",r=>r.fulfill({json:{mode:"external",provider:"",model:"",status:"offline",error:"",callsToday:0,tokensToday:0,totalCalls:0,totalTokens:0,lastRunAt:null}}));
  await page.route("**/api/v1/owner/agents/alex/memories/recall",r=>r.fulfill({json:[]}));
  await page.goto("/dashboard");
  const panel=page.getByRole("region",{name:"Relationships and feelings"});
  const samView=panel.getByRole("article",{name:"Alex’s view of Sam"});
  await expect(panel).toContainText("PRIVATE");
  await expect(samView).toContainText("Getting to know them");
  await expect(samView).not.toContainText("outdated label");
  await expect(samView).toContainText(bonds.sam.appraisal.feeling);
  await expect(samView).toContainText(bonds.sam.appraisal.reason);
  await expect(samView).toContainText("Resident-authored · Day 2, 00:50");
  await expect(samView).toContainText("2 recorded interactions · Last interaction Day 2, 00:40");
  const assessments=samView.getByRole("definition");
  await expect(assessments).toHaveText(["65 / 100","0 / 100","72 / 100","15 / 100"]);
  await expect(samView.getByText("attraction",{exact:true})).toHaveCount(0);
  await expect(samView.getByText("closeness",{exact:true})).toHaveCount(0);
  await samView.getByText("What shaped this view",{exact:true}).click();
  await expect(samView.getByText(recent.description,{exact:true}).first()).toBeVisible();
  await samView.getByText("How feelings changed (1)",{exact:true}).click();
  await expect(samView).toContainText("Day 1, 11:50");
  await expect(samView).toContainText(bonds.sam.history[0].appraisal.reason);
  await samView.getByText("Experiences behind this reflection",{exact:true}).click();
  await expect(samView.getByText(earlier.description,{exact:true})).toBeVisible();
  const legacy=panel.getByRole("article",{name:"Alex’s view of Ada"});
  await expect(legacy).toContainText("old neighbor");
  await expect(legacy).toContainText("No feelings recorded yet");
  await expect(legacy.getByRole("definition")).toHaveCount(0);
  const unappraised=panel.getByRole("article",{name:"Alex’s view of Riley"});
  await expect(unappraised).toContainText("No feelings recorded yet");
  await unappraised.getByText("Recent interactions",{exact:true}).click();
  await expect(unappraised.getByText(bonds["absent-resident"].evidence[0].description,{exact:true})).toBeVisible();
  await expect(panel.getByRole("button")).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await panel.screenshot({path:info.outputPath("relationships-and-feelings.png")});
  populated=false;await page.reload();
  await expect(panel).toContainText("No relationships or shared experiences recorded yet");
  expect(errors).toEqual([]);
});
