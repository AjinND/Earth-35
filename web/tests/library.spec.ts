import {test,expect,type WebSocketRoute} from "@playwright/test";

for(const mobile of [false,true])test(`library loans and persistent knowledge ${mobile?"on mobile":"on desktop"}`,async({page,request},info)=>{
  if(mobile)await page.setViewportSize({width:390,height:844});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const world={...await (await request.get("/api/v1/world")).json(),minute:600};
  const agent={id:"reader",name:"Alex",description:"A curious resident",home:"apt-101",occupation:"Unemployed",location:"library",x:18,z:54,activity:"READ_BOOK",status:"online",fixture:false};
  world.agents=[agent];
  const catalog=[{id:"garden-basics",title:"A Small Garden",subject:"gardening",description:"Plant care, light, water and compost.",copies:3,availableCopies:2,readingMinutes:60,studyMinutes:60,masteryMinutes:120}];
  let socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/auth/me",r=>r.fulfill({json:{id:"owner",email:"reader@example.test"}}));
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/library-ui-test"}}));
  await page.routeWebSocket("**/ws/library-ui-test",r=>{socket=r;r.send(JSON.stringify(world));});
  await page.route("**/api/v1/owner/agents",r=>r.fulfill({json:[agent]}));
  await page.route("**/api/v1/owner/agents/reader",r=>r.fulfill({json:{
    state:{...agent,ownerId:"owner",personality:"Curious",goals:"Learn gardening",paused:false,energy:80,hunger:10,money:100000,arrears:0,
      routine:{wakeMinute:420,sleepMinute:1380,morningActivity:"reading"},nightPlan:null,inventory:{meal:2},relationships:{},
      pending:{id:"read",type:"READ_BOOK",target:"garden-basics",until:630},
      knowledge:{gardening:{readingMinutes:60,studyMinutes:60,lastStudiedMinute:600,level:"introductory",concepts:["Plant roots take up water and mineral nutrients from soil.","Plants use light in photosynthesis to make sugars."]}}},
    memoryCount:0,reportedTokens:0,transactions:[],actions:[],workSchedule:null,socialPlans:[],
    library:{location:"library",loanDays:7,loanLimit:3,catalog,loans:[{book:"garden-basics",title:"A Small Garden",subject:"gardening",borrowedMinute:0,dueMinute:660,minutesUntilDue:60,overdue:false}]},
  }}));
  await page.route("**/api/v1/owner/agents/reader/runtime",r=>r.fulfill({json:{mode:"external",provider:"",model:"",status:"offline",error:"",callsToday:0,tokensToday:0,totalCalls:0,totalTokens:0,lastRunAt:null}}));
  await page.route("**/api/v1/owner/agents/reader/memories/recall",r=>r.fulfill({json:[]}));
  await page.goto("/dashboard");
  const panel=page.getByRole("region",{name:"Books and knowledge"});
  await expect(panel).toContainText("Reading A Small Garden");await expect(panel).toContainText("30 minutes remaining");
  await expect(panel.getByText("Due soon",{exact:true})).toBeVisible();await expect(panel).toContainText("Due day 1, 11:00");
  await expect(panel).toContainText("60 minutes read · 2 concepts learned");
  await panel.getByText("Learned concepts",{exact:true}).click();await expect(panel).toContainText("Plant roots take up water");
  await panel.getByText("Library collection (1 book)",{exact:true}).click();await expect(panel).toContainText("2/3 copies available");
  await expect(panel.getByRole("button")).toHaveCount(0);
  const bounds=await panel.boundingBox();expect(bounds!.width).toBeLessThanOrEqual(mobile?390:1440);
  await expect.poll(()=>!!socket).toBe(true);socket!.send(JSON.stringify({...world,minute:661,version:world.version+1}));
  await expect(panel.getByText("Overdue",{exact:true})).toBeVisible();
  await expect(panel).toContainText("Return this book to the library before borrowing again.");
  await panel.screenshot({path:info.outputPath("books-and-knowledge.png")});expect(errors).toEqual([]);
});

for(const mobile of [false,true])test(`expanded library filters and grounded works ${mobile?"on mobile":"on desktop"}`,async({page,request},info)=>{
  if(mobile)await page.setViewportSize({width:390,height:844});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  const world={...await (await request.get("/api/v1/world")).json(),minute:900};
  const agent={id:"creator",name:"Alex",description:"A curious resident",home:"apt-101",occupation:"Unemployed",location:"library",x:18,z:54,activity:"Idle",status:"online",fixture:false};
  world.agents=[agent];
  const subjects=["gardening","science","history","communication","technology","computing","business","arts","mathematics","physics","chemistry","biology","engineering","economics","finance","design","music","literature","philosophy","psychology","environment","geography"];
  const catalog=subjects.flatMap(subject=>Array.from({length:5},(_,index)=>{
    const depth=index<2?"introductory":index<4?"intermediate":"advanced",lessonMinutes=index<2?30:index<4?40:60;
    const prerequisitePoints=index<2?0:index<4?12:28,studied=subject==="science"&&index<3?lessonMinutes*4:0;
    return {id:`${subject}-${index}`,title:`${subject} ${depth} volume ${index+1}`,subject,description:`Study ${subject} through practical examples at ${depth} depth.`,
      copies:3,availableCopies:3,readingMinutes:studied,studyMinutes:studied,masteryMinutes:lessonMinutes*4,depth,lessonMinutes,lessonCount:4,prerequisitePoints,
      unlocked:prerequisitePoints===0||subject==="science"};
  }));
  const concepts=["A control group helps isolate the effect of an intervention.","A repeatable measurement makes comparisons more reliable.","Evidence can revise an earlier explanation.","Measurements should document uncertainty."];
  const reasoning={id:"reason-water",subject:"science",problem:"How could a balcony planter use less water?",
    analysis:`${concepts[0]} ${concepts[1]} Compare two otherwise identical planters and record water use, soil moisture and plant growth; repeat the trial before deciding.`,concepts:concepts.slice(0,2),minute:860};
  const creation={id:"work-planter",subject:"science",kind:"product",title:"Water-saving planter",content:`${concepts[0]} Build an adjustable reservoir for a balcony planter. Compare it with a plain planter over two weeks, recording water use and plant growth before claiming savings.`,
    reasoningId:reasoning.id,concepts:reasoning.concepts,minute:890};
  await page.route("**/api/v1/auth/me",r=>r.fulfill({json:{id:"owner",email:"creator@example.test"}}));
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/library-creation-test"}}));
  await page.routeWebSocket("**/ws/library-creation-test",r=>r.send(JSON.stringify(world)));
  await page.route("**/api/v1/owner/agents",r=>r.fulfill({json:[agent]}));
  await page.route("**/api/v1/owner/agents/creator",r=>r.fulfill({json:{
    state:{...agent,ownerId:"owner",personality:"Curious",goals:"Invent a useful planter",paused:false,energy:80,hunger:10,money:100000,arrears:0,
      routine:{wakeMinute:420,sleepMinute:1380,morningActivity:"reading"},nightPlan:null,inventory:{meal:2},relationships:{},pending:null,
      knowledge:{science:{readingMinutes:400,studyMinutes:400,lastStudiedMinute:840,level:"intermediate",concepts,points:28,maximumPoints:56,understanding:50,depth:"intermediate",intermediateConcepts:4,advancedConcepts:0,canReason:true,canCreate:true}}},
    memoryCount:0,reportedTokens:0,transactions:[],actions:[],workSchedule:null,socialPlans:[],
    library:{location:"library",loanDays:7,loanLimit:3,catalog,loans:[],reasoning:[reasoning],creations:[creation]},
  }}));
  await page.route("**/api/v1/owner/agents/creator/runtime",r=>r.fulfill({json:{mode:"external",provider:"",model:"",status:"offline",error:"",callsToday:0,tokensToday:0,totalCalls:0,totalTokens:0,lastRunAt:null}}));
  await page.route("**/api/v1/owner/agents/creator/memories/recall",r=>r.fulfill({json:[]}));
  await page.goto("/dashboard");
  const panel=page.getByRole("region",{name:"Books and knowledge"});
  await expect(panel).toContainText("110 books · 22 subjects");
  await expect(panel).toContainText("28/56 knowledge points · 50% of subject curriculum");
  await expect(panel.getByRole("progressbar",{name:"science understanding"})).toHaveAttribute("value","50");
  await expect(panel.getByText("Reasoning available",{exact:true})).toBeVisible();
  await expect(panel.getByText("Creation knowledge ready",{exact:true})).toBeVisible();
  const work=panel.locator(".library-artifacts details").filter({has:page.getByText("Water-saving planter")});
  await work.locator("summary").click();
  await expect(work).toContainText("recording water use and plant growth");
  await work.getByRole("link",{name:"Based on reasoning reason-water"}).click();
  const thought=panel.locator(".library-artifacts details").filter({has:page.getByText(reasoning.problem,{exact:true})});
  await expect(thought).toHaveAttribute("open","");
  await expect(thought).toContainText(concepts[0]);await expect(thought).toContainText(concepts[1]);
  await panel.getByText("Library collection (110 books)",{exact:true}).click();
  await expect(panel).toContainText("Showing 110 of 110 books");
  await expect(panel.getByRole("combobox",{name:"Subject",exact:true}).locator("option")).toHaveCount(23);
  await panel.getByRole("combobox",{name:"Subject",exact:true}).selectOption("computing");
  await expect(panel).toContainText("Showing 5 of 110 books");
  await panel.getByRole("combobox",{name:"Depth",exact:true}).selectOption("advanced");
  await expect(panel).toContainText("Showing 1 of 110 books");
  await expect(panel).toContainText("Requires 28 knowledge points in computing");
  await expect(panel).toContainText("60 minutes per lesson");
  await panel.getByLabel("Search books",{exact:true}).fill("business");
  await expect(panel).toContainText("No books match these filters.");
  await panel.getByRole("combobox",{name:"Subject",exact:true}).selectOption("");await panel.getByRole("combobox",{name:"Depth",exact:true}).selectOption("");
  await expect(panel).toContainText("Showing 5 of 110 books");
  await panel.getByLabel("Search books",{exact:true}).fill("science introductory");
  await expect(panel).toContainText("Showing 2 of 110 books");
  await expect(panel).toContainText("4/4 lessons learned");
  await expect(panel).toContainText("All lessons learned; available to reread");
  await expect(panel.getByRole("button")).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await panel.screenshot({path:info.outputPath("expanded-library.png")});expect(errors).toEqual([]);
});
