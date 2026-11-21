import * as THREE from "three";
import {FALLS,LANDSCAPES} from "./landscapes";
import {riverLevel,islandElevation,METERS_PER_MAP_UNIT} from "./island";
import {riverSample} from "./river-geometry";
import type {WindSample} from "./wind";

const seed=(i:number,salt=1)=>{const n=Math.sin(i*127.1+salt*311.7)*43758.5453;return n-Math.floor(n);};
const level=riverLevel(LANDSCAPES.lakeRiverEnd+1);
export const WATERFALL_DROP=riverLevel(LANDSCAPES.lakeRiverEnd)-level;
export const SPRAY_COUNT=96,MIST_COUNT=32;
export const INLET_SPRAY_COUNT=32,INLET_MIST_COUNT=12;
const inlet=riverSample(LANDSCAPES.lakeRiverStart);
export const INLET_IMPACT={x:inlet.x,z:inlet.z,y:inlet.y,dx:-inlet.sz,dz:inlet.sx};
export function atInlet<T extends {x:number;y:number;z:number}>(point:T):T {
  const dx=point.x-FALLS.x,dz=point.z-FALLS.z,along=(dx*FALLS.dx+dz*FALLS.dz)*.65,across=(dx*FALLS.dz-dz*FALLS.dx)*.65;
  return {...point,x:INLET_IMPACT.x+INLET_IMPACT.dx*along+INLET_IMPACT.dz*across,
    z:INLET_IMPACT.z+INLET_IMPACT.dz*along-INLET_IMPACT.dx*across,y:INLET_IMPACT.y+(point.y-level)*.6};
}
function local(along:number,across:number,y:number) {
  return {x:FALLS.x+FALLS.dx*along+FALLS.dz*across,z:FALLS.z+FALLS.dz*along-FALLS.dx*across,y};
}
type ParticleWind=Pick<WindSample,"x"|"z">;
type ParticleKind="spray"|"mist";
const calmWind:ParticleWind={x:0,z:0};
const wrap=(value:number,period:number)=>((value%period)+period)%period;
function particleAge(i:number,time:number,kind:ParticleKind) {
  const duration=kind==="spray"?1.05+seed(i,3)*.4:3.4+seed(i,3)*1.8;
  return {duration,age:wrap((kind==="spray"?time:time*.55)+i*(kind==="spray"?.137:.271),duration)};
}

/** Air drag grows over each finite particle lifetime, without crossing behind the impact cliff. */
export function advectWaterfallParticle<T extends {x:number;y:number;z:number}>(point:T,i:number,time:number,kind:ParticleKind,wind:ParticleWind=calmWind,inlet=false):T {
  if(!Number.isFinite(wind.x)||!Number.isFinite(wind.z)||Math.hypot(wind.x,wind.z)<1e-8)return point;
  const {age}=particleAge(i,time,kind),response=kind==="spray"?.45:.9,limit=kind==="spray"?.8:2.5;
  const drag=(age-response*(1-Math.exp(-age/response)))/METERS_PER_MAP_UNIT;
  let dx=wind.x*drag,dz=wind.z*drag;
  const scale=Math.min(1,limit/Math.max(1e-8,Math.hypot(dx,dz)));dx*=scale;dz*=scale;
  const impact=inlet?INLET_IMPACT:FALLS,along=(point.x-impact.x)*impact.dx+(point.z-impact.z)*impact.dz;
  const windAlong=dx*impact.dx+dz*impact.dz;
  const behind=Math.min(0,along+windAlong-.06);
  dx-=impact.dx*behind;dz-=impact.dz*behind;
  return {...point,x:point.x+dx,z:point.z+dz};
}

export function sprayParticle(i:number,time:number,wind:ParticleWind=calmWind) {
  const {duration,age}=particleAge(i,time,"spray");
  const across=(seed(i,2)-.5)*4.2,forward=.6+seed(i,4)*1.3,up=3.7+seed(i,5)*1.4;
  const point={...local(.15+age*forward,(seed(i,6)-.5)*1.8+age*across,level+.15+Math.max(0,up*age-4.5*age*age)),
    size:.025+seed(i,7)*.035,opacity:Math.sin(age/duration*Math.PI)};
  return advectWaterfallParticle(point,i,time,"spray",wind);
}
export function mistParticle(i:number,time:number,wind:ParticleWind=calmWind) {
  const {duration,age}=particleAge(i,time,"mist"),t=age/duration,angle=i*2.399;
  const spread=.3+age*.6;
  const point={...local(.25+age*.37+Math.cos(angle)*spread*.3,Math.sin(angle)*spread,level+.2+age*.8),
    size:.65+age*.35,opacity:Math.sin(t*Math.PI)*(.45+seed(i,4)*.55)};
  return advectWaterfallParticle(point,i,time,"mist",wind);
}
export const WATERFALL_ROCKS=(()=>{
  const rocks:{x:number;y:number;z:number;sx:number;sy:number;sz:number;angle:number}[]=[];
  // Broad outcrops sit against a continuous cliff, rather than forming columns
  // of separate stones that could themselves appear to float.
  for(const side of [-1,1])for(let tier=0;tier<3;tier++) {
    const i=rocks.length,along=-1.3-seed(i,2)*.7,across=side*(3.1+seed(i,3)*.65);
    rocks.push({...local(along,across,level+2+tier*8.3),
      sx:1.7+seed(i)*.8,sy:4.5+seed(i,4)*1.4,sz:2.1+seed(i,5)*.8,angle:seed(i,6)*Math.PI});
  }
  for(const side of [-1,1])rocks.push({...local(-1.3,side*2.9,level+WATERFALL_DROP-.4),sx:1.7,sy:1.4,sz:2,angle:side*.6});
  for(const side of [-1,1])for(let i=0;i<4;i++) {
    const point=local(.3+i*.8,side*(3.35+i*.15),level);
    rocks.push({...point,y:islandElevation(point.x,point.z)-.3,sx:.8+seed(i,4)*.4,sy:.8+seed(i)*.6,sz:.8+seed(i,2)*.6,angle:i*.7});
  }
  // Small exposed rocks break up the inlet cascade without blocking the trail.
  for(let i=0;i<18;i++) {
    const position=17+i*2.98/18,index=Math.floor(position),p=riverSample(index,position-index),side=i%2?1:-1;
    rocks.push({x:p.x+p.sx*p.width*.39*side,z:p.z+p.sz*p.width*.39*side,y:p.y-.3,
      sx:.28+seed(i)*.12,sy:.55+seed(i,2)*.5,sz:.65+seed(i,3)*.35,angle:Math.atan2(-p.sz,p.sx)});
  }
  return rocks;
})();
export function waterfallCliffGeometry() {
  const positions:number[]=[],colors:number[]=[],indices:number[]=[],rows=18,columns=20;
  const granite=new THREE.Color("#53645d"),lichen=new THREE.Color("#697663"),wet=new THREE.Color("#394f49");
  for(let row=0;row<=rows;row++)for(let column=0;column<=columns;column++) {
    const height=row/rows,acrossFraction=column/columns*2-1;
    const width=5.5-height*1.5+.3*Math.sin(row*1.6),across=acrossFraction*width;
    const core=Math.abs(across)<1.65;
    const along=-.38-height*.28+(core?0:.4*Math.sin(row*1.7+column*.8));
    const y=level-.8+height*(WATERFALL_DROP+.35)+(core?0:.22*Math.sin(column*2.3+row*.9));
    const point=local(along,across,y);positions.push(point.x,point.y,point.z);
    const color=granite.clone().lerp(core?wet:lichen,core?.65:.25).multiplyScalar(.88+.17*Math.sin(row*3+column*1.3)**2);
    colors.push(color.r,color.g,color.b);
    if(row<rows&&column<columns){const n=row*(columns+1)+column;indices.push(n,n+1,n+columns+1,n+1,n+columns+2,n+columns+1);}
  }
  // Close the top and sides into the plateau. The rear lies inside the terrain.
  const frontCount=positions.length/3;
  for(let row=0;row<=rows;row++)for(let column=0;column<=columns;column++) {
    const n=row*(columns+1)+column,front=new THREE.Vector3().fromArray(positions,n*3);
    positions.push(front.x-FALLS.dx*5,front.y,front.z-FALLS.dz*5);colors.push(colors[n*3],colors[n*3+1],colors[n*3+2]);
    if(row===rows&&column<columns)indices.push(n,n+frontCount,n+1,n+1,n+frontCount,n+frontCount+1);
    if(column===0&&row<rows)indices.push(n,n+columns+1,n+frontCount,n+frontCount,n+columns+1,n+frontCount+columns+1);
    if(column===columns&&row<rows)indices.push(n,n+frontCount,n+columns+1,n+columns+1,n+frontCount,n+frontCount+columns+1);
  }
  const geometry=new THREE.BufferGeometry();geometry.setAttribute("position",new THREE.Float32BufferAttribute(positions,3));geometry.setAttribute("color",new THREE.Float32BufferAttribute(colors,3));
  geometry.setIndex(indices);geometry.computeVertexNormals();return geometry;
}
export function boulderGeometry() {
  const geometry=new THREE.IcosahedronGeometry(1,1),position=geometry.getAttribute("position");
  // Coordinate-based perturbation keeps duplicate triangle corners coincident.
  for(let i=0;i<position.count;i++) {
    const x=position.getX(i),y=position.getY(i),z=position.getZ(i),scale=.83+.22*Math.sin(x*17+y*23+z*31)**2;
    position.setXYZ(i,x*scale,y*scale,z*scale);
  }
  geometry.computeVertexNormals();return geometry;
}
