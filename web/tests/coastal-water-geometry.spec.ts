import {test,expect} from "@playwright/test";
import * as THREE from "three";
import {createCoastalSurfGeometry} from "../lib/coastal-water";
import {coastDistance,ISLAND_CENTER,onIsland} from "../lib/island";
import {nearestRiver,RIVER} from "../lib/river";
import {riverSample} from "../lib/river-geometry";
import {outletHalfWidth,RIVER_MOUTH,RIVER_OUTLET_END,scaledCoastline} from "../lib/river-outlet";

test("coastal surf forms a finite upward ribbon with closed segment seams",()=>{
  const geometry=createCoastalSurfGeometry(),position=geometry.getAttribute("position"),surf=geometry.getAttribute("surf"),indices=geometry.getIndex()!;
  try {
    for(const [name,attribute] of Object.entries(geometry.attributes))
      expect(Array.from(attribute.array).every(Number.isFinite),`${name} contains invalid values`).toBe(true);
    expect(indices.count,"A missing surf mesh must not pass geometry checks").toBeGreaterThan(0);
    // Weld coincident segment endpoints for topology checks without requiring
    // a particular tessellation or an exact vertex count.
    const welded=new Map<string,number>(),vertices:number[]=[],edges=new Map<string,{a:number;b:number;count:number}>();
    let inner=Infinity,outer=-Infinity,minUpwardArea=Infinity,deepestSpill=0;
    for(let vertex=0;vertex<position.count;vertex++) {
      const key=[position.getX(vertex),position.getY(vertex),position.getZ(vertex)].map(value=>Math.round(value*1e4)).join(",");
      const existing=welded.get(key);
      if(existing===undefined)welded.set(key,vertex);
      vertices.push(existing??vertex);
      inner=Math.min(inner,surf.getX(vertex));outer=Math.max(outer,surf.getX(vertex));
    }
    for(let triangle=0;triangle<indices.count;triangle+=3) {
      const [a,b,c]=[indices.getX(triangle),indices.getX(triangle+1),indices.getX(triangle+2)];
      const upwardArea=(position.getZ(b)-position.getZ(a))*(position.getX(c)-position.getX(a))
        -(position.getX(b)-position.getX(a))*(position.getZ(c)-position.getZ(a));
      minUpwardArea=Math.min(minUpwardArea,upwardArea);
      const point={x:(position.getX(a)+position.getX(b)+position.getX(c))/3,z:(position.getZ(a)+position.getZ(b)+position.getZ(c))/3};
      if(onIsland(point))deepestSpill=Math.max(deepestSpill,coastDistance(point));
      const corners=[vertices[a],vertices[b],vertices[c]];
      for(let side=0;side<3;side++) {
        const a=corners[side],b=corners[(side+1)%3],key=a<b?`${a}:${b}`:`${b}:${a}`,edge=edges.get(key);
        if(edge)edge.count++;else edges.set(key,{a,b,count:1});
      }
    }
    expect(minUpwardArea,"Surf faces must point upward and have nonzero area").toBeGreaterThan(1e-8);
    expect(deepestSpill,"A filled surf polygon must not cover dry island interiors").toBeLessThan(6);
    const cracks:{x:number;z:number;count:number}[]=[];
    for(const {a,b,count} of edges.values()) {
      if(count===2)continue;
      const x=(position.getX(a)+position.getX(b))/2,z=(position.getZ(a)+position.getZ(b))/2;
      const boundary=(surf.getX(a)===inner&&surf.getX(b)===inner)||(surf.getX(a)===outer&&surf.getX(b)===outer);
      const river=nearestRiver(x,z);
      // Inner/outer ribbon edges and the deliberate estuary opening are real
      // boundaries. All other segment edges must meet a neighboring face.
      if(count===1&&(boundary||river.distance<river.width/2+6))continue;
      if(cracks.length<10)cracks.push({x,z,count});
    }
    expect(cracks,"Unpaired segment seams or overlapping surf faces").toEqual([]);
  } finally {geometry.dispose();}
});

test("actual surf triangles leave the estuary and dry island interior open",()=>{
  const geometry=createCoastalSurfGeometry(),material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide}),mesh=new THREE.Mesh(geometry,material);
  const ray=new THREE.Raycaster(new THREE.Vector3(),new THREE.Vector3(0,-1,0));
  const hits=(x:number,z:number)=>{ray.ray.origin.set(x,20,z);return ray.intersectObject(mesh);};
  try {
    let offshore=0,interior=0,mouth=0;
    for(const point of scaledCoastline(1.04).filter((_,index)=>index%16===0)) {
      const river=nearestRiver(point.x,point.z);
      if(river.distance<river.width/2+8)continue;
      expect(hits(point.x,point.z).length,`Missing surf beside shore at ${point.x},${point.z}`).toBeGreaterThan(0);offshore++;
    }
    for(const dx of [-120,-60,0,60,120])for(const dz of [-120,0,120]) {
      const point={x:ISLAND_CENTER.x+dx,z:ISLAND_CENTER.z+dz};
      if(!onIsland(point)||coastDistance(point)<15)continue;
      expect(hits(point.x,point.z),`Surf covers dry island interior at ${point.x},${point.z}`).toEqual([]);interior++;
    }
    // Probe the center and both sides of the flared, rendered river mouth.
    // Ray hits catch triangles that bridge the channel between clear vertices.
    for(let index=RIVER_MOUTH-2;index<RIVER_OUTLET_END;index++) {
      const a=RIVER[index],b=RIVER[index+1],steps=Math.ceil(Math.hypot(b.x-a.x,b.z-a.z));
      for(let step=0;step<steps;step++) {
        const point=riverSample(index,(step+.37)/steps),half=outletHalfWidth(index,point.fraction,point.width);
        for(const across of [-.75,0,.75]) {
          const x=point.x+point.sx*half*across,z=point.z+point.sz*half*across;
          expect(hits(x,z),`Surf bridges the estuary at ${index}+${point.fraction}, across ${across}`).toEqual([]);mouth++;
        }
      }
    }
    expect(offshore,"Positive controls must sample surf around the island").toBeGreaterThan(8);
    expect(interior).toBeGreaterThan(8);expect(mouth).toBeGreaterThan(30);
  } finally {geometry.dispose();material.dispose();}
});
