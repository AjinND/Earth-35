import {test,expect} from "@playwright/test";
import * as THREE from "three";
import {coastDistance,ISLAND_BOUNDS,islandElevation} from "../lib/island";
import {LANDSCAPES} from "../lib/landscapes";
import {terrainGeometry,renderedTerrainElevation} from "../lib/island-terrain";

test("refined hillsides share closed edges with their coarse terrain neighbors",()=>{
  const geometry=terrainGeometry(),positions=geometry.getAttribute("position"),indices=geometry.getIndex()!;
  const edges=new Map<string,{a:number;b:number;count:number}>();
  try {
    for(let triangle=0;triangle<indices.count;triangle+=3)for(let side=0;side<3;side++) {
      const a=indices.getX(triangle+side),b=indices.getX(triangle+(side+1)%3),key=a<b?`${a}:${b}`:`${b}:${a}`;
      const edge=edges.get(key);if(edge)edge.count++;else edges.set(key,{a,b,count:1});
    }
    const cracks:{x:number;z:number}[]=[];
    for(const {a,b,count} of edges.values()) {
      if(count===2)continue;
      expect(count,"Terrain must not overlap itself along an edge").toBe(1);
      const x=(positions.getX(a)+positions.getX(b))/2,z=(positions.getZ(a)+positions.getZ(b))/2;
      // The coastline and deliberate waterfall opening have real boundaries.
      if(coastDistance({x,z})<20||(x>-115&&x<-73&&z>-385&&z<-350))continue;
      cracks.push({x,z});
    }
    expect(cracks,"Interior terrain edges must be paired, including every refinement transition").toEqual([]);
  } finally {geometry.dispose();}
});

test("rendered terrain height agrees with actual ray hits around scenic hills and survives mesh disposal",()=>{
  const geometry=terrainGeometry(),material=new THREE.MeshBasicMaterial(),mesh=new THREE.Mesh(geometry,material);
  const ray=new THREE.Raycaster(new THREE.Vector3(),new THREE.Vector3(0,-1,0));
  const samples=LANDSCAPES.viewpoints.flatMap(place=>[-6.3,0,4.7].flatMap(dx=>[-5.1,0,5.9].map(dz=>({x:place.x+dx,z:place.z+dz}))));
  // Include slopes between destinations, where analytic terrain and its
  // triangle interpolation differ enough to leave roots hanging in the air.
  for(let x=-580;x<-100;x+=51.3)for(let z=-545;z<-320;z+=43.7)samples.push({x,z});
  let checked=0,maxAnalyticDifference=0;
  try {
    for(const {x,z} of samples) {
      ray.ray.origin.set(x,250,z);
      const hit=ray.intersectObject(mesh)[0],height=renderedTerrainElevation(x,z);
      if(!hit){expect(height,`No terrain triangle at ${x},${z}`).toBeUndefined();continue;}
      expect(height,`Rendered height at ${x},${z}`).toBeDefined();
      expect(height!).toBeCloseTo(hit.point.y,5);checked++;
      maxAnalyticDifference=Math.max(maxAnalyticDifference,Math.abs(islandElevation(x,z)-height!));
    }
    expect(checked).toBeGreaterThan(90);
    expect(maxAnalyticDifference,"The regression must include actual curved hills, not just flat city ground").toBeGreaterThan(.15);
    expect(renderedTerrainElevation(ISLAND_BOUNDS.maxX+1,0)).toBeUndefined();
    const point=samples[0],before=renderedTerrainElevation(point.x,point.z);
    geometry.dispose();
    expect(renderedTerrainElevation(point.x,point.z)).toBe(before);
  } finally {geometry.dispose();material.dispose();}
});
