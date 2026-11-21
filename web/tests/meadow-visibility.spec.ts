import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import * as THREE from "three";
import {separateResidents} from "../lib/resident-spacing";
import {islandElevation,walkElevation} from "../lib/island";
import type {World} from "../lib/types";

const ids=[
  "143b0ef3-77a7-4997-b549-c108a463de50","1aed7638-44c1-4eb0-a817-d13ed69c5080",
  "23be25fb-854f-4213-96ae-a3393737398f","bee81d45-b887-4c56-912d-ebd9b4dd4b83",
  "c3adb994-7922-4675-95d2-79018df0de6b","e05a44b5-2b8f-4cd7-9d02-6a183d1d2edc",
  "048ee884-3f4e-4f9e-99a9-73dcb3cbe427","07ee178c-69c9-4332-b031-3c54635abab2",
];

test.use({reducedMotion:"reduce"});
test("all eight Meadow Commons visitors remain visible and individually clickable",async({page},info)=>{
  test.setTimeout(120000);
  const original:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  const place=original.buildings.find(b=>b.id==="meadow-commons")!;
  let world:World={...original,minute:720,weather:"Clear",agents:ids.map((id,i)=>({id,name:`Meadow visitor ${i+1}`,description:"Park visitor",home:"apt-101",occupation:"Explorer",location:place.id,x:place.x,z:place.z,activity:"Idle",status:"paused",fixture:true,seatIndex:null}))};
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  let send:(world:World)=>void=()=>{};
  await page.setViewportSize({width:1536,height:960});
  await page.route("**/api/v1/world",r=>r.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",r=>r.fulfill({status:401,json:{message:"Preview"}}));
  await page.route("**/api/config",r=>r.fulfill({json:{webSocketUrl:"ws://localhost:3004/ws/meadow-visibility"}}));
  await page.routeWebSocket("**/ws/meadow-visibility",socket=>{send=w=>socket.send(JSON.stringify(w));send(world);});
  await page.goto("/");
  const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-expanded-destinations","17",{timeout:60000});
  await page.getByRole("button",{name:"Browse places",exact:true}).click();
  await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
  await expect(page.locator(".inspector-people button")).toHaveCount(8);
  for(let i=0;i<8;i++)await page.getByRole("button",{name:"Zoom in",exact:true}).click();
  await page.screenshot({path:info.outputPath("eight-meadow-visitors.png")});
  await page.getByRole("button",{name:"Close inspector"}).click();

  for(const agent of world.agents) {
    const box=(await canvas.boundingBox())!;
    const data=await canvas.evaluate(c=>({...c.dataset}));
    const target=new THREE.Vector3(Number(data.targetX),islandElevation(Number(data.targetX),Number(data.targetZ)),Number(data.targetZ));
    const direction=new THREE.Vector3(Number(data.cameraDirectionX),Number(data.cameraDirectionY),Number(data.cameraDirectionZ));
    const camera=new THREE.OrthographicCamera(-box.width/2,box.width/2,box.height/2,-box.height/2,.1,30000);
    camera.position.copy(target).addScaledVector(direction,-10000);camera.lookAt(target);camera.zoom=Number(data.cameraZoom);
    camera.updateProjectionMatrix();camera.updateMatrixWorld();
    const m=camera.matrixWorld.elements,projection={right:{x:m[0],y:m[1],z:m[2]},up:{x:m[4],y:m[5],z:m[6]}};
    const positions=separateResidents(world.agents.map(a=>({id:`resident-${a.id}`,x:a.x,y:walkElevation(a.x,a.z)+.22,z:a.z})),[],projection);
    const position=positions.find(p=>p.id===`resident-${agent.id}`)!;
    const head=new THREE.Vector3(position.x,position.y!+2.03*.45,position.z).project(camera);
    const x=box.x+(head.x+1)*box.width/2,y=box.y+(1-head.y)*box.height/2;
    expect(x).toBeGreaterThan(box.x);expect(x).toBeLessThan(box.x+box.width);
    expect(y).toBeGreaterThan(box.y);expect(y).toBeLessThan(box.y+box.height);
    // Clicking the actual head detects occlusion by another avatar or furniture.
    await page.mouse.click(x,y);
    await expect(page.locator(".inspect-panel h2")).toHaveText(agent.name);
    await page.getByRole("button",{name:"Close inspector"}).click();
  }
  world={...world,version:world.version+1,agents:[...world.agents].reverse()};send(world);
  await page.getByRole("button",{name:"Browse places",exact:true}).click();
  await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
  await expect(page.locator(".inspector-people button")).toHaveCount(8);
  expect(errors).toEqual([]);
});
