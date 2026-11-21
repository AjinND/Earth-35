import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {livingPlan} from "../lib/living-environment";
import {createVegetationTiles,plantGeometry} from "../lib/vegetation-geometry";
import {renderedTerrainElevation} from "../lib/island-terrain";
import {islandElevation} from "../lib/island";
import {viewerEyeElevation} from "../components/ImmersiveWorld";
import {outdoorSurfaceElevation} from "../lib/outdoor-layout";
import {LANDSCAPES} from "../lib/landscapes";
import type {World} from "../lib/types";

test("hillside tree roots meet the rendered ground even where analytic heights disagree",()=>{
  test.setTimeout(60000);
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  const trees=livingPlan(world.buildings,world.objects).plants.filter(p=>!p.detail&&p.kind!=="shrub")
    .sort((a,b)=>Math.abs(b.y-(renderedTerrainElevation(b.x,b.z)??b.y))-Math.abs(a.y-(renderedTerrainElevation(a.x,a.z)??a.y))).slice(0,20);
  expect(Math.abs(trees[0].y-renderedTerrainElevation(trees[0].x,trees[0].z)!)).toBeGreaterThan(.1);
  for(const tree of trees) {
    const template=plantGeometry(tree.kind),source=template.getAttribute("position");
    const result=createVegetationTiles([tree]),tile=result.tiles[0],positions=tile.canopy!.getAttribute("position");
    let roots=0;
    for(let i=0;i<source.count;i++)if(Math.abs(source.getY(i))<1e-5) {
      const x=positions.getX(i)+tile.x,z=positions.getZ(i)+tile.z;
      expect(positions.getY(i)-renderedTerrainElevation(x,z)!,`Floating root for ${tree.kind} at ${x},${z}`).toBeCloseTo(-.06,3);roots++;
    }
    expect(roots).toBeGreaterThan(4);template.dispose();result.dispose();
  }
});

test("outdoor planting follows its raised destination surface",()=>{
  const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
  const b=world.buildings.find(b=>b.type==="hill_station")!;
  expect(b).toBeTruthy();
  const x=b.x+8.3,z=b.z+8.7,surface=(x:number,z:number)=>outdoorSurfaceElevation(b,x,z);
  const tree={id:1,x,z,y:islandElevation(x,z),size:1,angle:0,radius:1.5,habitat:"mountain" as const,kind:"birch" as const,detail:false};
  const template=plantGeometry(tree.kind),source=template.getAttribute("position"),result=createVegetationTiles([tree],surface),tile=result.tiles[0],positions=tile.canopy!.getAttribute("position");
  for(let i=0;i<source.count;i++)if(Math.abs(source.getY(i))<1e-5)
    expect(positions.getY(i)-surface(positions.getX(i)+tile.x,positions.getZ(i)+tile.z)).toBeCloseTo(-.06,3);
  template.dispose();result.dispose();
});

test("hilltop viewing heights stay above both walking and rendered surfaces",()=>{
  for(const place of LANDSCAPES.viewpoints)for(let dx=-12;dx<=12;dx+=1.5)for(let dz=-12;dz<=12;dz+=1.5) {
    const x=place.x+dx,z=place.z+dz,ground=renderedTerrainElevation(x,z);
    if(ground===undefined)continue;
    expect(viewerEyeElevation(x,z)-ground).toBeGreaterThanOrEqual(1.65-1e-8);
    expect(viewerEyeElevation(x,z)-islandElevation(x,z)).toBeGreaterThanOrEqual(1.65-1e-8);
  }
});
