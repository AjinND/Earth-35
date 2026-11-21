import {test,expect} from "@playwright/test";
import savedWorld from "./fixtures/organic-world.json";

for(const mobile of [false,true])test(`private awareness and intentions ${mobile?"on mobile":"on desktop"}`,async({page},info)=>{
  if(mobile)await page.setViewportSize({width:390,height:844});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const agent={id:"aware",name:"Alex",description:"A curious resident",home:"apt-101",occupation:"Unemployed",location:"park",x:0,z:0,activity:"REST",status:"online",fixture:false};
  const world={...savedWorld,minute:750,agents:[agent]};
  let hasMind=true;
  const mind={observedMinute:720,feeling:"Curious about growing food",feelingMinute:720,
    scene:{location:"park",place:"Central Park",purpose:"A place to meet and relax",indoors:false,weather:"Clear",temperature:25,daylight:"daylight",objects:["Bench"],visiblePlaces:["Library"]},
    intention:{focus:"Learn to grow a balcony garden",nextStep:"Visit the library after my break",reason:"I want to try a useful project at home",target:"library",chosenMinute:710,reviewMinute:740},
    notices:[{revision:1,kind:"people",description:"Sam is no longer in view; their current location and intentions are unknown",priority:45,minute:720}],
    outcomes:[{id:"break",action:"REST",target:null,status:"accepted",result:"Resting for 30 minutes",minute:720},
      {id:"talk",action:"TALK",target:"sam",status:"rejected",result:"This resident is unavailable for conversation",minute:715}],
    reflections:[{lesson:"I will check availability before trying another greeting",outcomeId:"talk",minute:720}],
    visits:{park:{location:"park",name:"Central Park",count:2,lastMinute:720}},
  };
  await page.route("**/api/v1/auth/me",r=>r.fulfill({json:{id:"owner",email:"aware@example.test"}}));
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/cognition-test"}}));
  await page.routeWebSocket("**/ws/cognition-test",r=>r.send(JSON.stringify(world)));
  await page.route("**/api/v1/hosted/config",r=>r.fulfill({json:{available:false,providers:[],defaultProvider:"",defaultModel:"",providerOptions:[]}}));
  await page.route("**/api/v1/owner/agents",r=>r.fulfill({json:[agent]}));
  await page.route("**/api/v1/owner/agents/aware",r=>r.fulfill({json:{
    state:{...agent,ownerId:"owner",personality:"Curious",goals:"Learn gardening",paused:false,energy:80,hunger:10,money:100000,arrears:0,
      routine:{wakeMinute:420,sleepMinute:1380,morningActivity:"reading"},nightPlan:null,inventory:{meal:2},relationships:{},
      pending:{id:"break",type:"REST",target:null,until:760},...(hasMind?{mind}:{})},
    memoryCount:0,reportedTokens:0,transactions:[],actions:[],workSchedule:null,socialPlans:[],
  }}));
  await page.route("**/api/v1/owner/agents/aware/runtime",r=>r.fulfill({json:{mode:"external",provider:"",model:"",status:"offline",error:"",callsToday:0,tokensToday:0,totalCalls:0,totalTokens:0,lastRunAt:null}}));
  await page.route("**/api/v1/owner/agents/aware/memories/recall",r=>r.fulfill({json:[]}));
  await page.goto("/dashboard");
  const panel=page.getByRole("region",{name:"Awareness and intentions"});
  await expect(panel).toContainText("PRIVATE");
  await expect(panel).toContainText("Central Park · daylight · Clear · 25°C");
  await expect(panel).toContainText("Last observed Day 1, 12:00");
  await expect(panel).toContainText("Surroundings may have changed");
  await expect(panel).toContainText("Learn to grow a balcony garden");
  await expect(panel).toContainText("Due for review · Day 1, 12:20 · A plan, awaiting outcomes");
  await expect(panel).toContainText("Agent-authored");
  await panel.getByText("Recent observations",{exact:true}).click();
  await expect(panel.getByText(/Sam is no longer in view/)).toBeVisible();
  await panel.getByText("Recent outcomes",{exact:true}).click();
  await expect(panel.getByText("In progress",{exact:true})).toBeVisible();
  await expect(panel.getByText("rejected",{exact:true})).toBeVisible();
  await panel.getByText("What changed their thinking",{exact:true}).click();
  await expect(panel.getByText(mind.reflections[0].lesson)).toBeVisible();
  await expect(panel).toContainText("Personal interpretation");
  await expect(panel.getByRole("button")).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await panel.screenshot({path:info.outputPath("awareness-and-intentions.png")});
  hasMind=false;await page.reload();
  await expect(panel).toContainText("Waiting for the resident to observe their surroundings");
  await expect(panel).toContainText("No intention recorded yet");
  expect(errors).toEqual([]);
});
