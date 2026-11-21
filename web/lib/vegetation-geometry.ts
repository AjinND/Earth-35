import * as THREE from "three";
import {mergeGeometries} from "three/addons/utils/BufferGeometryUtils.js";
import {renderedTerrainElevation} from "./island-terrain";
import {LIVING_LIMITS,livingSeed,type Plant,type PlantKind} from "./living-environment";
import {VEGETATION_MAX_BEND,vegetationFlex,vegetationPhase} from "./vegetation-wind";

function colored(source:THREE.BufferGeometry,tint:string,kind:PlantKind,foliage:boolean) {
  const geometry=source.index?source.toNonIndexed():source;if(source!==geometry)source.dispose();
  geometry.deleteAttribute("uv");
  const color=new THREE.Color(tint),values=new Float32Array(geometry.getAttribute("position").count*3);
  for(let i=0;i<values.length;i+=3){values[i]=color.r;values[i+1]=color.g;values[i+2]=color.b;}
  const positions=geometry.getAttribute("position"),flex=new Float32Array(positions.count);
  for(let i=0;i<positions.count;i++)flex[i]=vegetationFlex(kind,positions.getY(i),foliage);
  geometry.setAttribute("windFlex",new THREE.BufferAttribute(flex,1));
  geometry.setAttribute("color",new THREE.BufferAttribute(values,3));return geometry;
}
function merge(parts:THREE.BufferGeometry[]) {
  const result=mergeGeometries(parts)!;parts.forEach(part=>part.dispose());
  result.computeBoundingSphere();return result;
}

/** Low-poly silhouettes share vertex-color materials, including flower heads. */
export function plantGeometry(kind:PlantKind) {
  const parts:THREE.BufferGeometry[]=[];
  const ellipsoid=(x:number,y:number,z:number,sx:number,sy:number,sz:number,color:string)=>parts.push(colored(new THREE.IcosahedronGeometry(1,0).scale(sx,sy,sz).translate(x,y,z),color,kind,true));
  const stem=(x:number,z:number,height:number,radius:number,color:string)=>parts.push(colored(new THREE.CylinderGeometry(radius*.65,radius,height,5).translate(x,height/2,z),color,kind,false));
  if(["oak","birch","willow"].includes(kind)) {
    const birch=kind==="birch",willow=kind==="willow";
    stem(0,0,birch?4.3:3.4,birch?.16:.3,birch?"#d8d8bd":"#78614c");
    if(birch) {
      ellipsoid(0,4.2,0,1.3,1.9,1.35,"#83a45c");ellipsoid(.3,5.5,0,.85,1.2,.95,"#b1ba70");
    } else if(willow) {
      ellipsoid(0,4,0,2.55,1.45,2.5,"#779c65");
      for(let i=0;i<5;i++){const a=i/5*Math.PI*2;ellipsoid(Math.cos(a)*1.45,2.9,Math.sin(a)*1.45,.95,1.65,.9,"#91ae75");}
    } else {
      ellipsoid(-.55,3.6,0,1.55,1.65,1.6,"#456f50");ellipsoid(.7,4.1,.2,1.5,1.65,1.6,"#648752");ellipsoid(0,5.1,-.35,1.35,1.1,1.35,"#829956");
    }
  } else if(kind==="pine"||kind==="alpine-pine") {
    stem(0,0,5,.2,"#6f624e");
    for(let i=0;i<3;i++)parts.push(colored(new THREE.ConeGeometry(1.9-i*.48,3.8-i*.6,7).translate(0,2.8+i*1.45,0),i===0?"#365d4e":i===1?"#527867":"#749080",kind,true));
  } else if(kind==="shrub") {
    ellipsoid(-.4,.48,0,.75,.65,.8,"#7e9564");ellipsoid(.35,.65,.2,.85,.8,.9,"#98a573");
  } else {
    const fern=kind==="fern",reeds=kind==="reeds",flowers=kind==="flowers"||kind==="alpine-flowers";
    const green=fern?"#497657":reeds?"#879668":kind==="alpine-flowers"?"#899b6a":"#91a165";
    for(let i=0;i<(fern?7:5);i++) {
      const angle=i*2.399,radius=fern?0:.52,dx=Math.cos(angle),dz=Math.sin(angle),x=dx*radius,z=dz*radius;
      if(fern) {
        const leaf=new THREE.ConeGeometry(.16,.95,4).rotateZ(-.9).rotateY(-angle).translate(dx*.32,.28,dz*.32);
        parts.push(colored(leaf,green,kind,true));
      } else {
        const height=reeds?1.25+i%3*.18:flowers?.4+i%3*.06:.55+i%3*.18;
        parts.push(colored(new THREE.ConeGeometry(.1,height,3).translate(x,height/2,z),green,kind,true));
        if(flowers)ellipsoid(x,height,z,.17,.1,.17,kind==="alpine-flowers"?(i%2?"#dacbdc":"#efe4b7"):i%3===0?"#dac071":i%3===1?"#c99193":"#e8e0b7");
        if(reeds)stem(x,z,height+.15,.025,"#a49b72");
      }
    }
  }
  return merge(parts);
}

export type VegetationTile={id:string;x:number;z:number;canopy:THREE.BufferGeometry|null;understory:THREE.BufferGeometry|null};
export function createVegetationTiles(plants:readonly Plant[],surfaceElevation:(x:number,z:number)=>number|undefined=renderedTerrainElevation) {
  const templates=new Map<PlantKind,THREE.BufferGeometry>(),groups=new Map<string,{x:number;z:number;canopy:THREE.BufferGeometry[];understory:THREE.BufferGeometry[]}>();
  for(const plant of plants) {
    const column=Math.floor(plant.x/LIVING_LIMITS.tileSize),row=Math.floor(plant.z/LIVING_LIMITS.tileSize),id=`${column}:${row}`;
    let tile=groups.get(id);if(!tile){tile={x:(column+.5)*LIVING_LIMITS.tileSize,z:(row+.5)*LIVING_LIMITS.tileSize,canopy:[],understory:[]};groups.set(id,tile);}
    if(!templates.has(plant.kind))templates.set(plant.kind,plantGeometry(plant.kind));
    const geometry=templates.get(plant.kind)!.clone().scale(plant.size,plant.size,plant.size).rotateY(plant.angle);
    // Anchor to the triangles the viewer actually sees. Analytic terrain can
    // differ by metres from a coarse hillside triangle. Root rims follow the
    // slope while trunks and crowns remain upright.
    const ground=surfaceElevation(plant.x,plant.z)??plant.y,positions=geometry.getAttribute("position");
    const roots=new Float32Array(positions.count*3),phases=new Float32Array(positions.count),phase=vegetationPhase(plant.id,plant.x,plant.z);
    for(let i=0;i<positions.count;i++){roots[i*3]=plant.x;roots[i*3+1]=ground-.06;roots[i*3+2]=plant.z;phases[i]=phase;}
    geometry.setAttribute("windRoot",new THREE.BufferAttribute(roots,3));geometry.setAttribute("windPhase",new THREE.BufferAttribute(phases,1));
    const heights=new Map<string,number>();
    for(let i=0;i<positions.count;i++)if(plant.detail||Math.abs(positions.getY(i))<1e-5) {
      const x=positions.getX(i),z=positions.getZ(i),key=`${x}:${z}`;
      let height=heights.get(key);
      if(height===undefined){height=surfaceElevation(plant.x+x,plant.z+z)??ground;heights.set(key,height);}
      positions.setY(i,positions.getY(i)+height-ground);
    }
    geometry.computeVertexNormals();
    geometry.translate(plant.x-tile.x,ground-.06,plant.z-tile.z);
    const colors=geometry.getAttribute("color"),variation=.91+livingSeed(plant.id,19)*.15;
    for(let i=0;i<colors.count;i++)colors.setXYZ(i,colors.getX(i)*variation,colors.getY(i)*variation,colors.getZ(i)*variation);
    tile[plant.detail?"understory":"canopy"].push(geometry);
  }
  templates.forEach(template=>template.dispose());
  const layer=(parts:THREE.BufferGeometry[])=>{
    if(!parts.length)return null;
    const geometry=merge(parts);geometry.boundingSphere!.radius+=VEGETATION_MAX_BEND;return geometry;
  };
  const tiles:VegetationTile[]=[...groups].map(([id,tile])=>({id,x:tile.x,z:tile.z,canopy:layer(tile.canopy),understory:layer(tile.understory)}));
  return {tiles,dispose:()=>tiles.forEach(tile=>{tile.canopy?.dispose();tile.understory?.dispose();})};
}
