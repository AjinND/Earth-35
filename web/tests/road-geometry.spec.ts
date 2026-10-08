import {test,expect} from "@playwright/test";
import * as THREE from "three";
import {roadSurfaceGeometry} from "../lib/road-geometry";
import {organicPoint,organicStreets,streetSurveys} from "../lib/organic-layout";
import {nearestRiver} from "../lib/river";

test("every street endpoint joins another surveyed road without a missing connector",()=>{
  for(const [index,street] of streetSurveys.entries())for(const [x,z] of [[street[0],street[1]],[street[2],street[3]]]) {
    const connected=streetSurveys.some(([ax,az,bx,bz],other)=>{
      if(other===index)return false;
      const dx=bx-ax,dz=bz-az,t=((x-ax)*dx+(z-az)*dz)/(dx*dx+dz*dz);
      return t>=0&&t<=1&&Math.hypot(x-ax-dx*t,z-az-dz*t)<.000001;
    });
    expect(connected,`Disconnected endpoint ${x},${z}`).toBe(true);
  }
});

test("asphalt covers bends, the repaired connector and outer corners of joined streets",()=>{
  const material=new THREE.MeshBasicMaterial(),meshes=organicStreets.map(points=>new THREE.Mesh(roadSurfaceGeometry(points),material));
  const ray=new THREE.Raycaster();
  const covered=(x:number,z:number)=>{
    ray.set(new THREE.Vector3(x,10,z),new THREE.Vector3(0,-1,0));
    return ray.intersectObjects(meshes).length>0;
  };
  for(const street of organicStreets)for(let i=1;i<street.length;i++) {
    const a=street[i-1],b=street[i],length=Math.hypot(b.x-a.x,b.z-a.z),sx=(b.z-a.z)/length,sz=-(b.x-a.x)/length;
    for(const side of [-1.5,0,1.5])expect(covered((a.x+b.x)/2+sx*side,(a.z+b.z)/2+sz*side)).toBe(true);
  }
  for(let z=40;z<=48;z+=.5){const p=organicPoint(-246,z);expect(covered(p.x,p.z)).toBe(true);}
  // Check a circle inside each perimeter turn, including the quarter which
  // independent butt-ended strips used to leave as bare land.
  for(const [x,z] of [[-390,-240],[90,-240],[90,240],[-390,240]]) {
    const p=organicPoint(x,z);
    for(let i=0;i<32;i++)expect(covered(p.x+Math.cos(i*Math.PI/16)*1.4,p.z+Math.sin(i*Math.PI/16)*1.4)).toBe(true);
  }
  meshes.forEach(mesh=>mesh.geometry.dispose());material.dispose();
});

test("repeated survey points cannot create nonfinite road vertices",()=>{
  const geometry=roadSurfaceGeometry([{x:0,z:0},{x:0,z:0},{x:0,z:8},{x:0,z:8}]);
  expect(Array.from(geometry.getAttribute("position").array).every(Number.isFinite)).toBe(true);
  geometry.dispose();
});

test("the river water, banks and walking strip stay clear of the street surface",()=>{
  for(const street of organicStreets)for(const p of street) {
    const river=nearestRiver(p.x,p.z);
    // The river-side walk is its widest surface: its outer edge lies at
    // width/2 + 3.625. Include the street half-width and sampling margin.
    expect(river.distance).toBeGreaterThan(river.width/2+4+1.6);
  }
});
