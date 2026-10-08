import * as THREE from "three";
import {RIVER} from "./river";
import {islandElevation,riverLevel} from "./island";
import {FALLS,LAKE,LANDSCAPES,lakeRadius,poolPoint,poolRadius} from "./landscapes";
import {RIVER_COURSE,RIVER_MOUTH,OCEAN_COLOR,outletProgress,outletWaterLevel,outletHalfWidth,outletBankLevel} from "./river-outlet";

export {RIVER_MOUTH,RIVER_COURSE} from "./river-outlet";
const clamp=(n:number)=>Math.min(1,Math.max(0,n));

export function riverSample(index:number,fraction=0) {
  const a=RIVER[index],b=RIVER[Math.min(index+1,RIVER.length-1)];
  const previous=RIVER[Math.max(0,index-1)],next=RIVER[Math.min(index+2,RIVER.length-1)];
  const dx=(b.x-previous.x)*(1-fraction)+(next.x-a.x)*fraction;
  const dz=(b.z-previous.z)*(1-fraction)+(next.z-a.z)*fraction,length=Math.hypot(dx,dz);
  return {x:a.x+(b.x-a.x)*fraction,z:a.z+(b.z-a.z)*fraction,y:outletWaterLevel(index,fraction),
    sx:dz/length,sz:-dx/length,width:2.2+3.2*(index+fraction)/(RIVER.length-1),index,fraction};
}

// Surface and bed use the SAME sub-metre samples, including both cliff edges.
// They therefore meet even where a coarse Cartesian terrain triangle misses
// the narrow river or bridges a steep drop.
function samples() {
  return RIVER_COURSE.flatMap((point,index)=>{
    if(index===RIVER_COURSE.length-1)return [riverSample(index)];
    const next=RIVER_COURSE[index+1],steps=Math.ceil(Math.hypot(next.x-point.x,next.z-point.z)/.65);
    const fractions=Array.from({length:steps},(_,i)=>i/steps);
    if(index===LANDSCAPES.lakeRiverEnd)fractions.push(.48,.49,.5,.51,.52);
    return [...new Set(fractions)].sort((a,b)=>a-b).map(fraction=>riverSample(index,fraction));
  });
}
function finish(positions:number[],indices:number[],colors?:number[],colorSize=3) {
  const geometry=new THREE.BufferGeometry();geometry.setAttribute("position",new THREE.Float32BufferAttribute(positions,3));
  if(colors)geometry.setAttribute("color",new THREE.Float32BufferAttribute(colors,colorSize));
  geometry.setIndex(indices);geometry.computeVertexNormals();return geometry;
}
export function createRiverSurfaces() {
  const water:number[]=[],bed:number[]=[],waterColors:number[]=[],bedColors:number[]=[],waterIndices:number[]=[],bedIndices:number[]=[];
  const blue=new THREE.Color("#428f9b"),white=new THREE.Color("#d9efed"),sea=new THREE.Color(OCEAN_COLOR),stone=new THREE.Color("#66756e"),silt=new THREE.Color("#aaa98b"),moss=new THREE.Color("#74836b");
  const crossSections=11;
  samples().forEach((p,row,points)=>{
    const roughness=Math.sin((p.index+p.fraction)*3.7)*.11+Math.sin((p.index+p.fraction)*8.3)*.05;
    const progress=outletProgress(p.index,p.fraction),half=outletHalfWidth(p.index,p.fraction,p.width),edge=half*(1+roughness),highland=p.index<LANDSCAPES.cliffRiverEnd+2;
    const inLake=lakeRadius(p.x,p.z)<1&&p.y<=LAKE.level+.1,extent=highland?5.5:3;
    const offsets=[-half-extent,-half-extent*.5,-edge-.55,-edge,-edge*.72,0,edge*.72,edge,edge+.55,half+extent*.5,half+extent];
    // Two edge vertices and a centre avoid a perfectly straight strip of water.
    for(const offset of [-edge,0,edge]) {
      water.push(p.x+p.sx*offset,p.y,p.z+p.sz*offset);
      const falls=p.index===LANDSCAPES.lakeRiverEnd&&p.fraction>=.48&&p.fraction<=.52;
      const steep=clamp((riverLevel(p.index)-riverLevel(p.index+1))/10);
      const color=blue.clone().lerp(white,falls?.83:steep*.45).lerp(sea,progress);
      waterColors.push(color.r,color.g,color.b,1-progress);
    }
    for(let column=0;column<crossSections;column++) {
      const offset=offsets[column],x=p.x+p.sx*offset,z=p.z+p.sz*offset,distance=Math.abs(offset);
      let y:number;
      if(distance<=edge)y=p.y-.38-.12*(1-distance/edge);
      else {
        const t=clamp((distance-edge)/extent);
        const bank=p.y+.32+(highland?.55:.12)*Math.sin((p.index+p.fraction)*2.6+column)**2;
        y=bank+(islandElevation(x,z)-bank)*t+.025;
      }
      if(inLake)y=Math.min(y,LAKE.level-.4);
      if(poolRadius(x,z)<1&&p.index+p.fraction>=LANDSCAPES.lakeRiverEnd+.52)y=Math.min(y,riverLevel(LANDSCAPES.lakeRiverEnd+1)-.6);
      if(p.index>=RIVER_MOUTH-2)y=outletBankLevel(x,z,y);
      bed.push(x,y,z);
      const color=(highland?stone:silt).clone().lerp(moss,distance>half?.22:0);
      color.multiplyScalar(.86+.18*Math.sin(row*1.37+column*2.8)**2);bedColors.push(color.r,color.g,color.b);
    }
    if(row<points.length-1) {
      for(let column=0;column<2;column++){const n=row*3+column;waterIndices.push(n,n+3,n+1,n+1,n+3,n+4);}
      for(let column=0;column<crossSections-1;column++){const n=row*crossSections+column;bedIndices.push(n,n+crossSections,n+1,n+1,n+crossSections,n+crossSections+1);}
    }
  });
  return {water:finish(water,waterIndices,waterColors,4),bed:finish(bed,bedIndices,bedColors)};
}

export function createRiverWalkGeometry(width=1.25) {
  const positions:number[]=[],indices:number[]=[],points=RIVER_COURSE.slice(0,RIVER_MOUTH);
  const samples=points.flatMap((point,index)=>{
    const next=points[index+1];if(!next)return [{...point,index,fraction:0}];
    const steps=Math.ceil(Math.hypot(next.x-point.x,next.z-point.z)/.65),fractions=Array.from({length:steps},(_,step)=>step/steps);
    if(index===LANDSCAPES.lakeRiverEnd)fractions.push(.48,.52);
    return [...new Set(fractions)].sort((a,b)=>a-b).map(fraction=>({x:point.x+(next.x-point.x)*fraction,z:point.z+(next.z-point.z)*fraction,index,fraction}));
  });
  samples.forEach((point,index)=>{
    const a=RIVER[Math.max(0,point.index-1)],b=RIVER[Math.min(RIVER.length-1,point.index+1)],length=Math.hypot(b.x-a.x,b.z-a.z);
    const sx=(b.z-a.z)/length,sz=-(b.x-a.x)/length,riverWidth=2.2+3.2*(point.index+point.fraction)/(RIVER.length-1),offset=-(riverWidth/2+3);
    for(const sign of [-1,1]) {
      const x=point.x+sx*(offset+sign*width/2),z=point.z+sz*(offset+sign*width/2);
      positions.push(x,islandElevation(x,z)+.15,z);
    }
    if(index<samples.length-1&&(point.index<LANDSCAPES.lakeRiverStart-1||point.index>=LANDSCAPES.cliffRiverEnd)) {
      const vertex=index*2;indices.push(vertex,vertex+2,vertex+1,vertex+1,vertex+2,vertex+3);
    }
  });
  return finish(positions,indices);
}

export function createPlungePool() {
  const level=riverLevel(LANDSCAPES.lakeRiverEnd+1),segments=64;
  const water=[FALLS.x+FALLS.dx*FALLS.poolOffset,level+.045,FALLS.z+FALLS.dz*FALLS.poolOffset],waterIndices:number[]=[];
  const bed:number[]=[],bedIndices:number[]=[],colors:number[]=[];
  const granite=new THREE.Color("#718078"),wetRock=new THREE.Color("#596c66");
  for(let ring=0;ring<5;ring++)for(let i=0;i<segments;i++) {
    const angle=i/segments*Math.PI*2,radius=[.01,.65,.92,1.05,1.3][ring],p=poolPoint(angle,radius);
    const downstream=(p.x-FALLS.x)*FALLS.dx+(p.z-FALLS.z)*FALLS.dz;
    const y=ring<2?level-1.3:ring===2?level-.4:ring===3?level+.3:islandElevation(p.x,p.z)+.025;
    // Let the existing cliff occlude the upstream pool edge instead of drawing
    // a raised ledge across the falling sheet.
    bed.push(p.x,downstream<0?Math.min(y,level-.4):y,p.z);
    const color=granite.clone().lerp(wetRock,ring<3?.7:.1).multiplyScalar(.92+.12*Math.sin(i*3.2+ring)**2);
    colors.push(color.r,color.g,color.b);
    if(ring<4){const n=ring*segments+i,next=ring*segments+(i+1)%segments;bedIndices.push(n,n+segments,next,next,n+segments,next+segments);}
    if(ring===0){const edge=poolPoint(angle);water.push(edge.x,level+.045,edge.z);waterIndices.push(0,(i+1)%segments+1,i+1);}
  }
  return {water:finish(water,waterIndices),basin:finish(bed,bedIndices,colors)};
}
