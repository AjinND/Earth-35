import * as THREE from "three";
import {COASTLINE,ISLAND_CENTER,islandElevation,walkElevation,onIsland,coastDistance,type IslandPoint} from "./island";
import {LANDSCAPES,LAKE,LAKE_SHORE,coastalWeight,BRIDGE_RAMP_LENGTH,type LandscapeBridge} from "./landscapes";
import {nearestRiver} from "./river";

function mesh(positions:number[],indices:number[],colors?:number[]) {
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute("position",new THREE.Float32BufferAttribute(positions,3));
  if(colors)geometry.setAttribute("color",new THREE.Float32BufferAttribute(colors,3));
  geometry.setIndex(indices);geometry.computeVertexNormals();return geometry;
}

export function createTrailGeometry(points:readonly IslandPoint[],width=1.7) {
  const positions:number[]=[],indices:number[]=[];
  for(let segment=1;segment<points.length;segment++) {
    const a=points[segment-1],b=points[segment],length=Math.hypot(b.x-a.x,b.z-a.z);
    if(length===0)continue;
    const steps=Math.ceil(length/.65),sx=(b.z-a.z)/length*width/2,sz=-(b.x-a.x)/length*width/2;
    for(let step=0;step<steps;step++) {
      const start=positions.length/3;
      for(const t of [step/steps,(step+1)/steps])for(const side of [-1,1]) {
        const x=a.x+(b.x-a.x)*t+sx*side,z=a.z+(b.z-a.z)*t+sz*side;
        positions.push(x,walkElevation(x,z)+.10,z);
      }
      indices.push(start,start+2,start+1,start+1,start+2,start+3);
    }
  }
  return mesh(positions,indices);
}

export function createBridgeDeckGeometry(bridge:LandscapeBridge) {
  const positions:number[]=[],indices:number[]=[],length=bridge.length+BRIDGE_RAMP_LENGTH*2,steps=Math.ceil(length/.25);
  for(let step=0;step<=steps;step++)for(const side of [-1,0,1]) {
    const x=bridge.x-length/2+length*step/steps,z=bridge.z+side*bridge.width/2;
    positions.push(x,walkElevation(x,z)+.035,z);
    if(step<steps&&side<1){const start=step*3+side+1;indices.push(start,start+3,start+1,start+1,start+3,start+4);}
  }
  return mesh(positions,indices);
}

// Only the named coastal regions receive dense bands. They follow the same
// survey as navigation, including the steep basalt edge and broad cove beach.
export function createCoastalGeometry() {
  const positions:number[]=[],colors:number[]=[],indices:number[]=[];
  // Sparse inland bands could span the estuary channel and cover its water.
  // Sub-unit samples preserve narrow channels and meet the refined terrain.
  const bands=Array.from({length:65},(_,index)=>index*.75);
  const palette={cliff:new THREE.Color("#69726d"),rocky:new THREE.Color("#a4afa2"),cove:new THREE.Color("#ddcfaa")};
  const inward=COASTLINE.map((a,index)=>{
    const b=COASTLINE[(index+1)%COASTLINE.length],length=Math.hypot(b.x-a.x,b.z-a.z);
    let x=-(b.z-a.z)/length,z=(b.x-a.x)/length;
    const middle={x:(a.x+b.x)/2,z:(a.z+b.z)/2};
    if(!onIsland({x:middle.x+x,z:middle.z+z})){x=-x;z=-z;}
    return {x,z};
  });
  // Shared inset corners close the wedges left by independent parallel strips.
  const corners=inward.map((next,index)=>{
    const previous=inward[(index-1+inward.length)%inward.length];
    const x=previous.x+next.x,z=previous.z+next.z,length=Math.hypot(x,z);
    const mx=x/length,mz=z/length,scale=1/Math.max(.25,mx*next.x+mz*next.z);
    return {x:mx*scale,z:mz*scale};
  });
  for(let segment=0;segment<COASTLINE.length;segment++) {
    const a=COASTLINE[segment],b=COASTLINE[(segment+1)%COASTLINE.length],length=Math.hypot(b.x-a.x,b.z-a.z);
    const middle={x:(a.x+b.x)/2,z:(a.z+b.z)/2};
    if(!LANDSCAPES.coastalZones.some(zone=>[a,middle,b].some(point=>coastalWeight(zone,point.x,point.z)>0)))continue;
    const startCorner=corners[segment],endCorner=corners[(segment+1)%corners.length];
    const steps=Math.ceil(length/.75),start=positions.length/3;
    for(let step=0;step<=steps;step++)for(const distance of bands) {
      const t=step/steps,nx=startCorner.x+(endCorner.x-startCorner.x)*t,nz=startCorner.z+(endCorner.z-startCorner.z)*t;
      const x=a.x+(b.x-a.x)*step/steps+nx*distance,z=a.z+(b.z-a.z)*step/steps+nz*distance;
      const color=new THREE.Color("#83a879");
      for(const zone of LANDSCAPES.coastalZones)color.lerp(palette[zone.kind as keyof typeof palette],coastalWeight(zone,x,z)*Math.max(0,1-distance/(zone.kind==="cove"?50:38)));
      color.multiplyScalar(.96+.04*Math.sin(x*.7+z*.3));
      positions.push(x,islandElevation(x,z)+.025,z);colors.push(color.r,color.g,color.b);
    }
    for(let step=0;step<steps;step++)for(let band=0;band<bands.length-1;band++) {
      const a=start+step*bands.length+band,b=a+bands.length,c=a+1,d=b+1;
      for(const triangle of [[a,b,c],[b,d,c]]) {
        // The land test excludes some points on the polygon's exact edge.
        // Retain those boundary vertices so the beach closes at the shore.
        if(!triangle.every(i=>{
          const point={x:positions[i*3],z:positions[i*3+2]};
          return onIsland(point)||coastDistance(point)<=1e-6;
        }))continue;
        const x=triangle.reduce((sum,i)=>sum+positions[i*3],0)/3,z=triangle.reduce((sum,i)=>sum+positions[i*3+2],0)/3;
        const river=nearestRiver(x,z);
        // The continuous river bed supplies these banks. Keep the coastal
        // material overlay from drawing a second surface across the channel.
        if(river.distance<river.width/2+1)continue;
        indices.push(...triangle);
      }
    }
  }
  return mesh(positions,indices,colors);
}

export function createLakeShoreGeometry() {
  const positions:number[]=[],colors:number[]=[],indices:number[]=[];
  const rings=[.97,1,1.035,1.10,1.20,1.32],sand=new THREE.Color("#b9b195"),moss=new THREE.Color("#728c70");
  for(const radius of rings)for(const edge of LAKE_SHORE) {
    const x=LAKE.x+(edge.x-LAKE.x)*radius,z=LAKE.z+(edge.z-LAKE.z)*radius;
    const color=sand.clone().lerp(moss,Math.min(1,(radius-1)*4));
    positions.push(x,islandElevation(x,z)+.04,z);colors.push(color.r,color.g,color.b);
  }
  for(let ring=0;ring<rings.length-1;ring++)for(let point=0;point<LAKE_SHORE.length;point++) {
    const a=ring*LAKE_SHORE.length+point,b=ring*LAKE_SHORE.length+(point+1)%LAKE_SHORE.length,c=a+LAKE_SHORE.length,d=b+LAKE_SHORE.length;
    indices.push(a,c,b,b,c,d);
  }
  return mesh(positions,indices,colors);
}

export function coastalBoulders() {
  const result:{x:number;y:number;z:number;sx:number;sy:number;sz:number;angle:number}[]=[];
  for(let i=0;i<COASTLINE.length*3;i++) {
    const a=COASTLINE[Math.floor(i/3)],b=COASTLINE[(Math.floor(i/3)+1)%COASTLINE.length],t=(i%3)/3;
    const x=a.x+(b.x-a.x)*t,z=a.z+(b.z-a.z)*t;
    if(!LANDSCAPES.coastalZones.some(zone=>zone.kind!=="cove"&&coastalWeight(zone,x,z)>.15))continue;
    const dx=ISLAND_CENTER.x-x,dz=ISLAND_CENTER.z-z,length=Math.hypot(dx,dz),offset=1.8+2*Math.abs(Math.sin(i*27.1));
    const px=x+dx/length*offset,pz=z+dz/length*offset,size=.7+1.5*Math.abs(Math.sin(i*7.91));
    result.push({x:px,y:islandElevation(px,pz)+size*.32,z:pz,sx:size*1.4,sy:size*.8,sz:size,angle:i*2.39});
  }
  return result;
}
