import {COASTLINE,ISLAND_CENTER,coastDistance,onIsland,riverLevel,type IslandPoint} from "./island";
import {RIVER} from "./river";

export const OCEAN_LEVEL=-.8;
export const OCEAN_COLOR="#69b1b6";
export const RIVER_MOUTH=RIVER.findIndex(point=>!onIsland(point));

export function scaledCoastline(scale:number) {
  return COASTLINE.map(point=>({x:ISLAND_CENTER.x+(point.x-ISLAND_CENTER.x)*scale,z:ISLAND_CENTER.z+(point.z-ISLAND_CENTER.z)*scale}));
}
function inside(point:IslandPoint,outline:readonly IslandPoint[]) {
  let result=false;
  for(let index=0,previous=outline.length-1;index<outline.length;previous=index++) {
    const a=outline[index],b=outline[previous];
    if((a.z>point.z)!==(b.z>point.z)&&point.x<(b.x-a.x)*(point.z-a.z)/(b.z-a.z)+a.x)result=!result;
  }
  return result;
}
const shelf=scaledCoastline(1.04);
const beyondShelf=RIVER.findIndex((point,index)=>index>=RIVER_MOUTH&&!inside(point,shelf));
export const RIVER_OUTLET_END=Math.min(RIVER.length-1,(beyondShelf<0?RIVER_MOUTH+4:beyondShelf)+1);
export const RIVER_COURSE=RIVER.slice(0,RIVER_OUTLET_END+1);

const upstream=RIVER[RIVER_MOUTH-1],offshore=RIVER[RIVER_MOUTH];
let low=0,high=1;
for(let step=0;step<40;step++) {
  const fraction=(low+high)/2;
  if(onIsland({x:upstream.x+(offshore.x-upstream.x)*fraction,z:upstream.z+(offshore.z-upstream.z)*fraction}))low=fraction;
  else high=fraction;
}
export const RIVER_SHORE_POSITION=RIVER_MOUTH-1+(low+high)/2;
const clamp=(value:number)=>Math.max(0,Math.min(1,value));
const smooth=(value:number)=>{const t=clamp(value);return t*t*(3-2*t);};

export function outletProgress(index:number,fraction=0) {
  return smooth((index+fraction-RIVER_SHORE_POSITION)/(RIVER_OUTLET_END-RIVER_SHORE_POSITION));
}
export function outletWaterLevel(index:number,fraction=0) {
  // Rendering beyond the coast must not alter authoritative inland heights.
  const level=riverLevel(index,fraction);
  return level+(OCEAN_LEVEL-riverLevel(RIVER_OUTLET_END))*outletProgress(index,fraction);
}
export function outletHalfWidth(index:number,fraction:number,width:number) {
  return width/2*(1+.6*outletProgress(index,fraction));
}
export function outletBankLevel(x:number,z:number,height:number) {
  if(onIsland({x,z}))return height;
  const blend=smooth(coastDistance({x,z})/4);
  return height+(OCEAN_LEVEL-.45-height)*blend;
}

// A local convex cutout opens only the terminal channel through the flat
// island backing and beach layers. The river's own bed supplies its banks.
export const OUTLET_CHANNEL=(()=>{
  const points:IslandPoint[]=[];
  for(let index=RIVER_MOUTH-3;index<=RIVER_OUTLET_END;index++) {
    const point=RIVER[index],before=RIVER[index-1],after=RIVER[Math.min(index+1,RIVER.length-1)];
    const dx=after.x-before.x,dz=after.z-before.z,length=Math.hypot(dx,dz),sx=dz/length,sz=-dx/length;
    const width=2.2+3.2*index/(RIVER.length-1),half=outletHalfWidth(index,0,width)*1.16+.65;
    for(const side of [-1,1])for(const end of [-1,1])points.push({x:point.x+sx*half*side+dx/length*.7*end,z:point.z+sz*half*side+dz/length*.7*end});
  }
  points.sort((a,b)=>a.x-b.x||a.z-b.z);
  const cross=(a:IslandPoint,b:IslandPoint,c:IslandPoint)=>(b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x);
  const halfHull=(input:IslandPoint[])=>{
    const hull:IslandPoint[]=[];
    for(const point of input){while(hull.length>1&&cross(hull[hull.length-2],hull[hull.length-1],point)<=0)hull.pop();hull.push(point);}
    return hull.slice(0,-1);
  };
  return [...halfHull(points),...halfHull([...points].reverse())];
})();
