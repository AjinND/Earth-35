import {test,expect} from "@playwright/test";
import {gatheringBenches,gatheringSeats,restingSeats,fountainObstacles} from "../lib/gathering-furniture";
import {residentSpacing,separateResidents} from "../lib/resident-spacing";
import type {Agent,Building,World} from "../lib/types";

const square={id:"town-square",type:"plaza",x:18,z:6} as Building;
const visitor=(id:string,activity="REST"):Agent=>({id,name:id,description:"Square visitor",home:"apt-101",occupation:"Explorer",location:square.id,x:square.x,z:square.z,activity,status:"paused",fixture:true,raincoatWorn:false});
test("bench assignments have finite capacity, match furniture and survive snapshot reordering",()=>{
  for(const type of ["plaza","park","promenade","garden","hangout"]) {
    const building={...square,type};const seats=gatheringSeats(building),benches=gatheringBenches(type);
    expect(seats).toHaveLength(4);
    for(const [i,seat] of seats.entries()) {
      const bench=benches[Math.floor(i/2)];
      expect(Math.hypot(seat.x-building.x-bench.x,seat.z-building.z-bench.z)).toBeCloseTo(.7);
      expect(seat.rotation).toBe(bench.rotation);expect(seat.y).toBe(.83);
    }
  }
  const agents=Array.from({length:6},(_,i)=>visitor(`visitor-${i}`));
  const assigned=restingSeats(agents,[square]);
  expect(assigned.size).toBe(4);expect(restingSeats([...agents].reverse(),[square])).toEqual(assigned);
  expect(restingSeats([visitor("reader","READ_BOOK"),visitor("walker","MOVE"),visitor("idle","Idle")],[square]).size).toBe(1);
  const copy=JSON.stringify(agents);
  const positions=agents.map(a=>{const seat=assigned.get(a.id);return {id:a.id,x:seat?.x??a.x,z:seat?.z??a.z,anchored:!!seat};});
  const separated=separateResidents(positions,fountainObstacles([square]));
  for(const p of separated) {
    expect(Math.hypot(p.x-square.x,p.z-square.z)).toBeGreaterThanOrEqual(2.55);
    if(p.anchored)expect(p).toEqual(positions.find(original=>original.id===p.id));
  }
  for(let i=0;i<separated.length;i++)for(let j=i+1;j<separated.length;j++)
    expect(Math.hypot(separated[i].x-separated[j].x,separated[i].z-separated[j].z)).toBeGreaterThanOrEqual(residentSpacing-.0001);
  expect(JSON.stringify(agents)).toBe(copy);
});
test("crowds stay outside fountains and leave occupied bench seats fixed",()=>{
  const seats=gatheringSeats(square).map((seat,i)=>({id:`seat-${i}`,x:seat.x,z:seat.z,anchored:true}));
  const crowded=[...seats,...Array.from({length:46},(_,i)=>({id:`visitor-${i}`,x:square.x,z:square.z}))];
  const result=separateResidents(crowded,fountainObstacles([square]));
  for(const seat of seats)expect(result.find(p=>p.id===seat.id)).toEqual(seat);
  for(const p of result)expect(Math.hypot(p.x-square.x,p.z-square.z)).toBeGreaterThanOrEqual(2.55);
  for(let i=0;i<result.length;i++)for(let j=i+1;j<result.length;j++)
    expect(Math.hypot(result[i].x-result[j].x,result[i].z-result[j].z)).toBeGreaterThanOrEqual(residentSpacing-.0001);
});
test("reserved seats stay fixed when another resident joins or leaves, and full-seat REST stays on the ground",()=>{
  const first={...visitor("z-first"),seatIndex:0},second={...visitor("a-second"),seatIndex:1};
  const seats=gatheringSeats(square);
  expect(restingSeats([first,second],[square]).get(first.id)).toEqual(seats[0]);
  expect(restingSeats([second],[square]).get(second.id)).toEqual(seats[1]);
  const standing={...visitor("ground-rest"),seatIndex:null};
  expect(restingSeats([first,second,standing],[square]).has(standing.id)).toBe(false);
  expect(restingSeats([{...first,activity:"Idle",seatIndex:null}],[square]).size).toBe(0);
});
test("Founders Square residents sit on benches and stand up when rest ends",async({page,request},info)=>{
  test.setTimeout(90000);
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  const original:World=await (await request.get("/api/v1/world")).json();
  const place=original.buildings.find(b=>b.id===square.id)!;
  let world:World={...original,minute:720,weather:"Clear",tickMs:200,agents:[visitor("Test1"),visitor("Test2"),visitor("Test3"),visitor("Test4"),visitor("Test5","Idle")].map((a,i)=>({...a,seatIndex:i<4?i:null}))};
  let send:(world:World)=>void=()=>{};
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/seating-test"}}));
  await page.routeWebSocket("**/ws/seating-test",r=>{send=w=>r.send(JSON.stringify(w));send(world);});
  await page.goto("/");await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
  for(let i=0;i<12&&await page.getByRole("button",{name:"Zoom in",exact:true}).isEnabled();i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await page.getByRole("button",{name:"Browse places"}).click();
  await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
  await page.locator(".inspect-panel").getByRole("button",{name:"Test1",exact:true}).click();
  const label=page.locator(".world-stage [data-avatar-motion]");
  await expect(label).toHaveAttribute("data-seated","true");await expect(label).toHaveAttribute("data-avatar-motion","Rest");
  await expect(label).toHaveAttribute("data-raincoat-worn","false");
  await page.getByRole("button",{name:"Follow agent",exact:true}).click();
  await expect.poll(async()=>{
    const canvas=await page.getByLabel("Interactive 3D map of New Eden").locator("canvas").boundingBox(),box=await label.boundingBox();
    return canvas&&box?Math.abs(box.x+box.width/2-canvas.x-canvas.width/2):Infinity;
  }).toBeLessThan(3);
  await page.screenshot({path:info.outputPath("founders-square-bench-seats.png")});
  world={...world,minute:world.minute+1,version:world.version+1,agents:world.agents.map(a=>a.id==="Test1"?{...a,activity:"Idle"}:a)};send(world);
  await expect(label).toHaveAttribute("data-seated","false");await expect(label).toHaveAttribute("data-avatar-motion","Idle");
  expect(errors).toEqual([]);
});
