import type {Building,PlacedWorldObject} from "./types";
import {ISLAND_BOUNDS,coastDistance,islandElevation,onIsland} from "./island";
import {organicStreets,streetDistance} from "./organic-layout";
import {LANDSCAPES,landscapeClear,trailDistance} from "./landscapes";
import {nearestRiver} from "./river";
import {enclosedPlace} from "./city-layout";
import {outdoorDimensions} from "./outdoor-layout";

export type Habitat="city"|"forest"|"mountain"|"coast"|"meadow"|"riverbank";
export type PlantKind="oak"|"birch"|"pine"|"alpine-pine"|"willow"|"shrub"|"fern"|"grass"|"flowers"|"alpine-flowers"|"reeds";
export type Plant={id:number;x:number;y:number;z:number;size:number;angle:number;radius:number;habitat:Habitat;kind:PlantKind;detail:boolean};
export type WildlifeKind="gull"|"songbird"|"rabbit"|"butterfly";
export type Wildlife={id:number;kind:WildlifeKind;habitat:Habitat;phase:number;path:{x:number;y:number;z:number}[]};
export const LIVING_LIMITS={plants:7200,trees:2600,wildlife:36,tileSize:240,detailPixels:2.4,detailDistance:170,wildlifeDistance:110} as const;
export const livingSeed=(i:number,salt=1)=>{const value=Math.sin(i*127.1+salt*311.7)*43758.5453;return value-Math.floor(value);};
const naturalPlaces=new Set(["wilderness","mountain","hill_station","trekking_trail","river","riverside_walk"]);

/** All plants are decorative; these reservations keep existing usable space clear. */
export function habitatAt(x:number,z:number):Habitat {
  const elevation=islandElevation(x,z),coast=coastDistance({x,z}),river=nearestRiver(x,z);
  if(coast<48)return "coast";
  if(elevation>36)return "mountain";
  if(river.distance<river.width/2+22)return "riverbank";
  if(x>-440&&x<155&&z>-285&&z<285)return "city";
  if(x<-445&&z<90&&z>-420)return "forest";
  return "meadow";
}

export function plantingClear(x:number,z:number,radius:number,buildings:readonly Building[],objects:readonly PlacedWorldObject[]=[]) {
  if(!onIsland({x,z})||coastDistance({x,z})<radius+3||!landscapeClear(x,z))return false;
  const river=nearestRiver(x,z);
  if(river.distance<river.width/2+radius+1||trailDistance(x,z)<radius+2.6||streetDistance(x,z)<radius+4.2)return false;
  if(LANDSCAPES.bridges.some(b=>Math.abs(x-b.x)<b.length/2+radius+5&&Math.abs(z-b.z)<b.width/2+radius+2))return false;
  for(const b of buildings) {
    if((b.outdoorRadius??0)>0) {
      // OutdoorDestination owns the entire leisure ground, including its paths
      // and seating. Do not add a second forest layer through that planting.
      const {radiusX,radiusZ}=outdoorDimensions(b);
      if(Math.hypot((x-b.x)/radiusX,(z-b.z)/radiusZ)<1.1+(radius+2)/Math.min(radiusX,radiusZ))return false;
    } else {
      const reserve=naturalPlaces.has(b.type)?7:enclosedPlace(b.type)?6:8;
      if(Math.abs(x-b.x)<reserve+radius&&Math.abs(z-b.z)<reserve+radius)return false;
    }
    if(b.entrance&&Math.hypot(x-b.entrance.x,z-b.entrance.z)<radius+4)return false;
  }
  if(objects.some(o=>o.status!=="carried"&&Math.hypot(x-o.x,z-o.z)<radius+2.5))return false;
  // Check the entire crown/patch on dry ground, not just its center. This also
  // prevents floating roots on steep relief and cover at lake/cliff boundaries.
  const y=islandElevation(x,z);
  for(let i=0;i<8;i++) {
    const px=x+Math.cos(i*Math.PI/4)*radius,pz=z+Math.sin(i*Math.PI/4)*radius;
    if(!landscapeClear(px,pz)||Math.abs(islandElevation(px,pz)-y)>Math.max(.35,radius*.65))return false;
  }
  return true;
}

const treePalette:Record<Habitat,PlantKind[]>={city:["oak","birch"],forest:["oak","birch","pine"],mountain:["pine","alpine-pine"],coast:["shrub"],meadow:["oak","birch"],riverbank:["willow","birch"]};
const groundPalette:Record<Habitat,PlantKind[]>={city:["flowers","grass"],forest:["fern","shrub"],mountain:["alpine-flowers","grass"],coast:["grass","shrub"],meadow:["flowers","grass"],riverbank:["reeds","fern"]};
export const crownRadius:Record<PlantKind,number>={oak:2.25,birch:1.5,pine:1.9,"alpine-pine":1.9,willow:2.65,shrub:1.35,fern:.85,grass:.8,flowers:.9,"alpine-flowers":.9,reeds:.8};

// Only spatial changes rebuild the plan. Clock ticks and resident decisions do
// not regenerate thousands of plants; the two canvases can share the same plan.
export function livingSpatialKey(buildings:readonly Building[],objects:readonly PlacedWorldObject[]=[]) {
  return JSON.stringify([buildings.map(b=>[b.id,b.type,b.x,b.z,b.outdoorRadius,b.entrance?.x,b.entrance?.z]).sort((a,b)=>String(a[0]).localeCompare(String(b[0]))),
    objects.map(o=>[o.id,o.x,o.z,o.status]).sort((a,b)=>String(a[0]).localeCompare(String(b[0])))]);
}
const plans=new Map<string,{plants:Plant[];wildlife:Wildlife[]}>();
export function livingPlan(buildings:readonly Building[],objects:readonly PlacedWorldObject[]=[]) {
  const key=livingSpatialKey(buildings,objects),cached=plans.get(key);if(cached)return cached;
  const plants:Plant[]=[],occupied=new Map<string,Plant[]>();let trees=0;
  const add=(id:number,x:number,z:number,detail:boolean,city=false)=>{
    if(plants.length>=LIVING_LIMITS.plants||(!detail&&trees>=LIVING_LIMITS.trees)||!onIsland({x,z}))return;
    const habitat=city?"city":habitatAt(x,z),y=islandElevation(x,z);
    if(y>88||(!detail&&habitat==="mountain"&&y>68))return;
    const palette=(detail?groundPalette:treePalette)[habitat],kind=palette[Math.floor(livingSeed(id,5)*palette.length)];
    const size=(detail?.7+livingSeed(id,3)*.55:habitat==="city"?.85+livingSeed(id,3)*.4:1.1+livingSeed(id,3)*.85)*(kind==="alpine-pine"?.65:1);
    const radius=crownRadius[kind]*size;
    if(!plantingClear(x,z,radius,buildings,objects))return;
    const cx=Math.floor(x/12),cz=Math.floor(z/12);
    for(let dx=-1;dx<=1;dx++)for(let dz=-1;dz<=1;dz++)for(const other of occupied.get(`${cx+dx}:${cz+dz}`)??[]) {
      if(other.detail===detail&&Math.hypot(x-other.x,z-other.z)<radius+other.radius+.5)return;
    }
    const plant={id,x,y:y-.08,z,size,angle:livingSeed(id,8)*Math.PI*2,radius,habitat,kind,detail};
    plants.push(plant);const bucket=occupied.get(`${cx}:${cz}`)??[];bucket.push(plant);occupied.set(`${cx}:${cz}`,bucket);
    if(!detail)trees++;
  };
  // Designed street planting and front/side gardens remain at resident scale.
  let cityId=30000;
  for(const street of organicStreets)for(let i=4;i<street.length;i+=8) {
    const p=street[i],a=street[i-1],length=Math.hypot(p.x-a.x,p.z-a.z);
    for(const side of [-1,1])add(cityId++,p.x-(p.z-a.z)/length*8*side,p.z+(p.x-a.x)/length*8*side,false,true);
  }
  for(const b of [...buildings].sort((a,b)=>a.id.localeCompare(b.id)))if(["house","apartment","office","garden","park"].includes(b.type)) {
    for(const side of [-1,1])for(let i=0;i<5;i++)add(cityId++,b.x+side*(9+i%2*2),b.z-5+i*2.5,true,true);
  }
  for(let i=0;i<13500;i++) {
    const x=ISLAND_BOUNDS.minX+livingSeed(i)*(ISLAND_BOUNDS.maxX-ISLAND_BOUNDS.minX);
    const z=ISLAND_BOUNDS.minZ+livingSeed(i,2)*(ISLAND_BOUNDS.maxZ-ISLAND_BOUNDS.minZ);
    if(!onIsland({x,z}))continue;
    const habitat=habitatAt(x,z),density={city:.03,forest:.88,mountain:.25,coast:.24,meadow:.08,riverbank:.5}[habitat];
    if(livingSeed(i,4)<density)add(i,x,z,false);
    if(livingSeed(i,6)<(habitat==="forest"?.68:habitat==="city"?.06:.5))add(i+15000,x+2.8,z-2.8,true);
  }
  // Local understory patches make trails and scenic arrivals worth inspecting.
  for(const [index,place] of LANDSCAPES.viewpoints.entries())for(let i=0;i<100;i++) {
    const angle=i*2.399,radius=9+livingSeed(i,index+21)*28;
    add(40000+index*100+i,place.x+Math.cos(angle)*radius,place.z+Math.sin(angle)*radius,true);
  }
  const wildlife:Wildlife[]=[];
  const anchors=[...LANDSCAPES.viewpoints,...plants.filter(p=>!p.detail&&p.id%97===0)].slice(0,50);
  for(const [index,anchor] of anchors.entries()) {
    if(wildlife.length>=LIVING_LIMITS.wildlife)break;
    for(let attempt=0;attempt<24;attempt++) {
      const angle=attempt*2.399,x=anchor.x+Math.cos(angle)*(12+attempt*.8),z=anchor.z+Math.sin(angle)*(12+attempt*.8);
      const habitat=habitatAt(x,z),kind:WildlifeKind=habitat==="coast"?"gull":habitat==="mountain"?"songbird":habitat==="city"||habitat==="meadow"?index%2?"rabbit":"butterfly":index%2?"songbird":"rabbit";
      const radius=kind==="gull"||kind==="songbird"?7:kind==="rabbit"?2.4:3.5;
      const path=Array.from({length:48},(_,i)=>{
        const a=i/48*Math.PI*2,px=x+Math.cos(a)*radius,pz=z+Math.sin(a)*radius*.65;
        return {x:px,y:islandElevation(px,pz),z:pz};
      });
      if(path.some(p=>!plantingClear(p.x,p.z,1.1,buildings,objects)))continue;
      wildlife.push({id:index,kind,habitat,phase:livingSeed(index,32),path});break;
    }
  }
  const plan={plants,wildlife};plans.set(key,plan);while(plans.size>2)plans.delete(plans.keys().next().value!);return plan;
}

export function wildlifeActive(kind:WildlifeKind,minute:number,weather:string) {
  const day=((minute%1440)+1440)%1440;
  return weather!=="Rain"&&day>=330&&day<1170&&(kind!=="butterfly"||(day>=450&&day<1050));
}
export function wildlifePose(animal:Wildlife,minute:number,reducedMotion=false) {
  const time=reducedMotion?0:minute,speed=animal.kind==="rabbit"?.035:.095;
  const cycle=((time*speed+animal.phase)%1+1)%1;
  // Rabbits stop to graze between short, eased hops along their dry circuit.
  const steps=cycle*12,step=Math.floor(steps),progress=steps-step;
  const hop=animal.kind==="rabbit"?Math.min(1,progress/.45):progress;
  const phase=animal.kind==="rabbit"?(step+hop*hop*(3-2*hop))/12:cycle;
  const point=phase*animal.path.length,a=animal.path[Math.floor(point)%animal.path.length],b=animal.path[(Math.floor(point)+1)%animal.path.length],t=point-Math.floor(point);
  const airborne=animal.kind==="gull"||animal.kind==="songbird";
  return {x:a.x+(b.x-a.x)*t,z:a.z+(b.z-a.z)*t,
    y:a.y+(b.y-a.y)*t+(airborne?8+Math.sin(cycle*Math.PI*2)*1.5:animal.kind==="butterfly"?1.5+Math.sin(cycle*Math.PI*12)*.35:.12+(reducedMotion?0:Math.sin(hop*Math.PI)*.3)),
    heading:Math.atan2(b.x-a.x,b.z-a.z),wing:reducedMotion?.2:Math.sin(time*(animal.kind==="butterfly"?48:18)+animal.phase*6)*.65};
}
