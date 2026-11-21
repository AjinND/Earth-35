import {test,expect} from "@playwright/test";
import * as THREE from "three";
import world from "./fixtures/organic-world.json";
import {walkElevation} from "../lib/island";
import {residentSpacing,separateResidents,type ResidentProjection} from "../lib/resident-spacing";

test("projected Pinecrest visitors stay grounded and separately visible on sloping terrain",()=>{
  const place=world.buildings.find(building=>building.name==="Pinecrest Hill Station")!;
  const camera=new THREE.OrthographicCamera();
  camera.position.set(1630,1550,1850);camera.lookAt(0,0,0);camera.updateMatrixWorld();
  const matrix=camera.matrixWorld.elements;
  const projection:ResidentProjection={
    right:{x:matrix[0],y:matrix[1],z:matrix[2]},
    up:{x:matrix[4],y:matrix[5],z:matrix[6]},
    elevation:walkElevation,
  };
  const original=Array.from({length:8},(_,i)=>({
    id:`resident-${i}`,x:place.x,y:walkElevation(place.x,place.z)+.22,z:place.z,
  }));
  const before=original.map(point=>({...point})),positions=separateResidents(original,[],projection);
  expect(positions).toHaveLength(8);
  for(const point of positions)expect(point.y).toBeCloseTo(walkElevation(point.x,point.z)+.22,10);
  for(let i=0;i<positions.length;i++)for(let j=i+1;j<positions.length;j++) {
    const a=positions[i],b=positions[j],dx=b.x-a.x,dy=b.y!-a.y!,dz=b.z-a.z;
    expect(Math.hypot(dx,dz)).toBeGreaterThanOrEqual(residentSpacing-.0001);
    const horizontal=dx*projection.right.x+dy*projection.right.y+dz*projection.right.z;
    const vertical=dx*projection.up.x+dy*projection.up.y+dz*projection.up.z;
    expect(Math.hypot(horizontal,vertical)).toBeGreaterThanOrEqual(residentSpacing-.0001);
  }
  expect(original).toEqual(before);
});
