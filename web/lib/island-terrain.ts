import * as THREE from "three";
import {ISLAND_BOUNDS,islandElevation,onIsland,coastDistance} from "./island";
import {nearestRiver} from "./river";
import {trailDistance,LANDSCAPES,coastalWeight} from "./landscapes";

const seed=(i:number,salt=1)=>{const n=Math.sin(i*127.1+salt*311.7)*43758.5453;return n-Math.floor(n);};
function createTerrain() {
  const {minX,maxX,minZ,maxZ}=ISLAND_BOUNDS,steps=192;
  // Refine just the highland bands; avoid loading planet-sized terrain detail.
  const axis=(min:number,max:number,nearMin:number,nearMax:number)=>{
    const values=Array.from({length:steps+1},(_,i)=>min+(max-min)*i/steps);
    for(let value=nearMin;value<=nearMax;value+=1.5)values.push(value);
    return [...new Set(values)].sort((a,b)=>a-b);
  };
  const xs=axis(minX,maxX,-182,-65),zs=axis(minZ,maxZ,-432,-330),columns=xs.length;
  const positions:number[]=[],colors:number[]=[],indices:number[]=[],leaves:number[][]=[];
  const cells=Array.from({length:(xs.length-1)*(zs.length-1)},()=>[] as number[]);
  const green=new THREE.Color("#83a879"),wood=new THREE.Color("#507d62"),rock=new THREE.Color("#9caaa1"),snow=new THREE.Color("#e9eee5"),sand=new THREE.Color("#ded4b0");
  const addVertex=(x:number,z:number,variation:number)=>{
    const index=positions.length/3;
    const h=islandElevation(x,z),coast=coastDistance({x,z});
    positions.push(x,h-.08,z);
    const color=green.clone().lerp(wood,Math.min(.7,h/70));
    if(h>46)color.lerp(rock,Math.min(1,(h-46)/30));
    if(h>84)color.lerp(snow,Math.min(1,(h-84)/16));
    if(x>-180&&x<-65&&z>-380&&z<-330) {
      const river=nearestRiver(x,z);
      if(river.index>=LANDSCAPES.lakeRiverEnd&&river.index<LANDSCAPES.cliffRiverEnd&&river.distance<12)color.lerp(rock,.85);
    }
    if(coast<18)color.lerp(sand,1-coast/18);
    for(const zone of LANDSCAPES.coastalZones)color.lerp(zone.kind==="cove"?sand:rock,coastalWeight(zone,x,z)*Math.max(0,1-coast/(zone.kind==="cove"?48:32)));
    color.multiplyScalar(.94+seed(variation,4)*.09);colors.push(color.r,color.g,color.b);return index;
  };
  for(let row=0;row<zs.length;row++)for(let col=0;col<xs.length;col++)addVertex(xs[col],zs[row],row*113+col);
  const midpoints=new Map<string,number>();
  const midpoint=(a:number,b:number)=>{
    const key=a<b?`${a}:${b}`:`${b}:${a}`;const existing=midpoints.get(key);if(existing!==undefined)return existing;
    const index=addVertex((positions[a*3]+positions[b*3])/2,(positions[a*3+2]+positions[b*3+2])/2,a+b);midpoints.set(key,index);return index;
  };
  const subdivide=(a:number,b:number,c:number,cell:number,depth=0)=>{
    const x=(positions[a*3]+positions[b*3]+positions[c*3])/3,z=(positions[a*3+2]+positions[b*3+2]+positions[c*3+2])/3;
    const radius=Math.max(...[a,b,c].map(index=>Math.hypot(positions[index*3]-x,positions[index*3+2]-z)));
    // Keep the actual terrain triangles beneath mountain paths as detailed as
    // their walking surface; the old coarse mesh could bury a trail by metres.
    const detailedCoast=LANDSCAPES.coastalZones.some(zone=>coastalWeight(zone,x,z)>0)&&coastDistance({x,z})<radius+50;
    if(depth<6&&radius>.65&&(trailDistance(x,z)<radius+3||detailedCoast)) {
      const ab=midpoint(a,b),bc=midpoint(b,c),ca=midpoint(c,a);
      subdivide(a,ab,ca,cell,depth+1);subdivide(ab,b,bc,cell,depth+1);subdivide(ca,bc,c,cell,depth+1);subdivide(ab,bc,ca,cell,depth+1);return;
    }
    leaves.push([a,b,c,cell]);
  };
  for(let row=0;row<zs.length-1;row++)for(let col=0;col<xs.length-1;col++) {
    const a=row*columns+col,b=a+1,c=a+columns,d=c+1;
    for(const triangle of [[a,c,b],[b,c,d]]) {
      // A coarse triangle can bridge the near-vertical fall and cover its water.
      // The detailed rock bed, solid cliff and plunge basin fill this patch.
      const x=triangle.reduce((sum,i)=>sum+positions[i*3],0)/3,z=triangle.reduce((sum,i)=>sum+positions[i*3+2],0)/3;
      if(x>-110&&x<-78&&z>-380&&z<-355) {
        const river=nearestRiver(x,z),position=river.index+river.fraction;
        if(position>=LANDSCAPES.lakeRiverEnd+.1&&position<LANDSCAPES.lakeRiverEnd+1.08&&river.distance<river.width/2+4.5)continue;
      }
      if(triangle.every(i=>onIsland({x:positions[i*3],z:positions[i*3+2]})))subdivide(triangle[0],triangle[1],triangle[2],row*(columns-1)+col);
    }
  }
  // Adjacent triangles can choose different refinement depths. Include every
  // neighboring midpoint on both sides of each shared edge; otherwise the
  // fine side follows a different height curve and opens a crack in the hill.
  const edge=(a:number,b:number,vertices:number[])=>{
    const middle=midpoints.get(a<b?`${a}:${b}`:`${b}:${a}`);
    if(middle===undefined){vertices.push(a);return;}
    edge(a,middle,vertices);edge(middle,b,vertices);
  };
  const emit=(a:number,b:number,c:number,cell:number)=>{
    cells[cell].push(indices.length);indices.push(a,b,c);
  };
  for(const [a,b,c,cell] of leaves) {
    const boundary:number[]=[];
    edge(a,b,boundary);edge(b,c,boundary);edge(c,a,boundary);
    if(boundary.length===3){emit(a,b,c,cell);continue;}
    const center=addVertex((positions[a*3]+positions[b*3]+positions[c*3])/3,(positions[a*3+2]+positions[b*3+2]+positions[c*3+2])/3,a+b+c);
    for(let i=0;i<boundary.length;i++)emit(center,boundary[i],boundary[(i+1)%boundary.length],cell);
  }
  // Queries use the same rounded coordinates uploaded to WebGL, including
  // at grid boundaries, rather than a second approximation of the hillside.
  return {xs:xs.map(Math.fround),zs:zs.map(Math.fround),positions:new Float32Array(positions),colors:new Float32Array(colors),indices:new Uint32Array(indices),cells};
}

let terrain:ReturnType<typeof createTerrain>|undefined;
const terrainData=()=>terrain??=createTerrain();

export function terrainGeometry() {
  const data=terrainData();
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute("position",new THREE.BufferAttribute(data.positions,3));
  geometry.setAttribute("color",new THREE.BufferAttribute(data.colors,3));
  geometry.setIndex(new THREE.BufferAttribute(data.indices,1));geometry.computeVertexNormals();return geometry;
}

function interval(values:readonly number[],value:number) {
  let low=0,high=values.length-1;
  while(low+1<high){const middle=(low+high)>>>1;if(value<values[middle])high=middle;else low=middle;}
  return low;
}

/** Height of the actual terrain triangle, absent beyond its shore/waterfall cutout. */
export function renderedTerrainElevation(x:number,z:number):number|undefined {
  const {xs,zs,positions,indices,cells}=terrainData();
  if(x<xs[0]||x>xs[xs.length-1]||z<zs[0]||z>zs[zs.length-1])return undefined;
  const col=interval(xs,x),row=interval(zs,z);
  for(const triangle of cells[row*(xs.length-1)+col]) {
    const a=indices[triangle]*3,b=indices[triangle+1]*3,c=indices[triangle+2]*3;
    const ax=positions[a],az=positions[a+2],bx=positions[b],bz=positions[b+2],cx=positions[c],cz=positions[c+2];
    const area=(bz-cz)*(ax-cx)+(cx-bx)*(az-cz);
    if(area===0)continue;
    const u=((bz-cz)*(x-cx)+(cx-bx)*(z-cz))/area,v=((cz-az)*(x-cx)+(ax-cx)*(z-cz))/area,w=1-u-v;
    if(u>=-1e-7&&v>=-1e-7&&w>=-1e-7)return positions[a+1]*u+positions[b+1]*v+positions[c+1]*w;
  }
  return undefined;
}

