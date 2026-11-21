import {test,expect,type WebSocketRoute} from "@playwright/test";
import {separateResidents,residentSpacing,type ResidentPoint} from "../lib/resident-spacing";
import type {World} from "../lib/types";
import * as THREE from "three";
import {islandElevation} from "../lib/island";

function assertSeparate(points:ResidentPoint[]) {
  for(let i=0;i<points.length;i++)for(let j=i+1;j<points.length;j++)
    expect(Math.hypot(points[i].x-points[j].x,points[i].z-points[j].z)).toBeGreaterThanOrEqual(residentSpacing-.0001);
}
const courtyardResidentIds=[
  "39db04fb-1552-45bd-a949-56636b8750a8",
  "9705a42a-1596-400b-b12f-71b197098286",
  "bee81d45-b887-4c56-912d-ebd9b4dd4b83",
  "cf67176c-1856-437d-818c-307c699dd6b4",
  "e05a44b5-2b8f-4cd7-9d02-6a183d1d2edc",
].map(id=>`resident-${id}`);
function crowdAxisRatio(points:ResidentPoint[]) {
  const meanX=points.reduce((total,p)=>total+p.x,0)/points.length;
  const meanZ=points.reduce((total,p)=>total+p.z,0)/points.length;
  let xx=0,zz=0,xz=0;
  for(const point of points) {
    const x=point.x-meanX,z=point.z-meanZ;
    xx+=x*x;zz+=z*z;xz+=x*z;
  }
  // Compare the spread along the crowd's own axes, regardless of camera angle.
  const difference=Math.hypot(xx-zz,2*xz),total=xx+zz;
  return Math.sqrt(Math.max(0,total-difference)/(total+difference));
}
test("coincident courtyard crowds occupy a compact area instead of a line",()=>{
  for(const count of [3,5,20]) {
    const original=Array.from({length:count},(_,i)=>({
      id:courtyardResidentIds[i]??`resident-extra-${i}`,x:18,z:6,
    }));
    const before=original.map(p=>({...p})),positions=separateResidents(original);
    assertSeparate(positions);
    expect(crowdAxisRatio(positions)).toBeGreaterThan(.2);
    expect(Math.max(...positions.map(p=>Math.hypot(p.x-18,p.z-6))))
      .toBeLessThanOrEqual(residentSpacing*Math.sqrt(count)*1.5);
    expect(separateResidents([...original].reverse())).toEqual(positions);
    expect(separateResidents([...original.slice(2),...original.slice(0,2)])).toEqual(positions);
    expect(original).toEqual(before);
  }
});
test("crowd spacing preserves seated residents, transit anchors and isolated residents",()=>{
  const seated={id:"resident-seated",x:18,z:6,anchored:true};
  const transit={id:"resident-transit",x:18+residentSpacing*3,z:6,anchored:true};
  const lone={id:"resident-isolated",x:50,z:50};
  const crowd=courtyardResidentIds.map(id=>({id,x:18,z:6}));
  const original=[...crowd,seated,transit,lone],before=original.map(p=>({...p}));
  const obstacles=[{x:transit.x,z:transit.z,radius:1}];
  const positions=separateResidents(original,obstacles);
  assertSeparate(positions);
  for(const fixed of [seated,transit,lone])expect(positions.find(p=>p.id===fixed.id)).toEqual(fixed);
  expect(separateResidents([...original].reverse(),obstacles)).toEqual(positions);
  expect(original).toEqual(before);
  expect(separateResidents([lone])).toEqual([lone]);
});
test("coincident residents and full venues remain distinct with stable ordering",()=>{
  for(const count of [2,3,20,50]) {
    const original=Array.from({length:count},(_,i)=>({id:`resident-${i}`,x:18,z:6}));
    const copy=JSON.stringify(original),positions=separateResidents(original);
    assertSeparate(positions);expect(JSON.stringify(original)).toBe(copy);
    expect(separateResidents([...original].reverse())).toEqual(positions);
  }
});
test("spacing resolves nearby and crossing paths without displacing distant residents",()=>{
  const lone={id:"far-away",x:50,z:50};
  for(let step=0;step<=100;step++) {
    const x=-2+step*.04;
    const points=separateResidents([{id:"a",x,z:0},{id:"b",x:-x,z:0},lone]);
    assertSeparate(points);expect(points.find(p=>p.id===lone.id)).toEqual(lone);
  }
});
test("two courtyard visitors render in separate places and retain individual selection",async({page,request},info)=>{
  test.setTimeout(90000);
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  const original:World=await (await request.get("/api/v1/world")).json();
  const place=original.buildings.find(b=>b.name==="Lantern Courtyard")!;
  let world:World={...original,minute:720,weather:"Clear",tickMs:200,agents:["Test1","Test2"].map((name,i)=>({id:`spacing-${i}`,name,description:"Courtyard visitor",home:"apt-101",occupation:"Explorer",location:place.id,x:place.x,z:place.z,activity:"Idle",status:"paused",fixture:true}))};
  let socket:WebSocketRoute|undefined;
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3000/ws/spacing-test"}}));
  await page.routeWebSocket("**/ws/spacing-test",r=>{socket=r;r.send(JSON.stringify(world));});
  await page.goto("/");await expect(page.locator(".world-stage canvas")).toBeVisible({timeout:15000});
  for(let i=0;i<12&&await page.getByRole("button",{name:"Zoom in",exact:true}).isEnabled();i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await page.getByRole("button",{name:"Browse places"}).click();
  await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
  await page.locator(".inspect-panel").getByRole("button",{name:"Test1",exact:true}).click();
  await page.getByRole("button",{name:"Follow agent",exact:true}).click();
  await expect.poll(async()=>{
    const canvas=await page.getByLabel("Interactive 3D map of New Eden").locator("canvas").boundingBox(),label=await page.locator(".world-stage [data-avatar-motion]").boundingBox();
    return canvas&&label?Math.abs(label.x+label.width/2-canvas.x-canvas.width/2):Infinity;
  }).toBeLessThan(3);
  await page.getByRole("button",{name:"Stop following",exact:true}).click();
  await page.getByRole("button",{name:"Close inspector"}).click();
  const anchors:{x:number;y:number}[]=[];
  for(const name of ["Test1","Test2"]) {
    await page.getByRole("button",{name:"Browse places"}).click();
    await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
    await page.locator(".inspect-panel").getByRole("button",{name,exact:true}).click();
    await expect(page.locator(".inspect-panel h2")).toHaveText(name);
    const label=page.locator(".world-stage [data-avatar-motion]");await expect(label).toHaveText(name);
    // Selecting each person pans to them. Wait for that pan, then compensate
    // for its target so the two anchors describe the same camera view.
    await expect.poll(async()=>{
      const canvas=await page.getByLabel("Interactive 3D map of New Eden").locator("canvas").boundingBox(),box=await label.boundingBox();
      return canvas&&box?Math.abs(box.x+box.width/2-canvas.x-canvas.width/2):Infinity;
    }).toBeLessThan(3);
    const data=await page.getByLabel("Interactive 3D map of New Eden").locator("canvas").evaluate(c=>({...c.dataset}));
    const direction=new THREE.Vector3(Number(data.cameraDirectionX),Number(data.cameraDirectionY),Number(data.cameraDirectionZ));
    const right=new THREE.Vector3(-direction.z,0,direction.x).normalize(),up=right.clone().cross(direction);
    const target=new THREE.Vector3(Number(data.targetX),islandElevation(Number(data.targetX),Number(data.targetZ)),Number(data.targetZ));
    const zoom=Number(data.cameraZoom);
    const box=await label.boundingBox();expect(box).not.toBeNull();anchors.push({x:box!.x+box!.width/2+target.dot(right)*zoom,y:box!.y+box!.height/2-target.dot(up)*zoom});
    await page.getByRole("button",{name:"Close inspector"}).click();
  }
  expect(Math.hypot(anchors[0].x-anchors[1].x,anchors[0].y-anchors[1].y)).toBeGreaterThan(8);
  // Reordering a snapshot must not swap or merge visual identities.
  await expect.poll(()=>!!socket).toBe(true);
  world={...world,version:world.version+1,agents:[...world.agents].reverse()};socket!.send(JSON.stringify(world));
  await page.getByRole("button",{name:"Browse places"}).click();
  await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
  await expect(page.locator(".inspector-people button")).toHaveCount(2);
  await page.screenshot({path:info.outputPath("courtyard-residents-separated.png")});
  expect(errors).toEqual([]);
});
