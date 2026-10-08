"use client";
import {useEffect,useMemo,useRef} from "react";
import {useFrame,useThree} from "@react-three/fiber";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import {ISLAND_BOUNDS,islandElevation,islandLandmarks,onIsland,coastDistance} from "@/lib/island";
import type {Building} from "@/lib/types";
import {clearsRoad,prototypeLayout,type CityLayout} from "@/lib/city-layout";
import {nearestRiver} from "@/lib/river";
import {landscapeClear,trailDistance,LANDSCAPES,coastalWeight} from "@/lib/landscapes";
import HighlandLandscape from "./HighlandLandscape";
import ConnectedLandscape from "./ConnectedLandscape";
import {createIslandSurfaces} from "@/lib/island-surface-geometry";

const seed=(i:number,salt=1)=>{const n=Math.sin(i*127.1+salt*311.7)*43758.5453;return n-Math.floor(n);};

export function terrainGeometry() {
  const {minX,maxX,minZ,maxZ}=ISLAND_BOUNDS,steps=192;
  // Refine just the highland bands; avoid loading planet-sized terrain detail.
  const axis=(min:number,max:number,nearMin:number,nearMax:number)=>{
    const values=Array.from({length:steps+1},(_,i)=>min+(max-min)*i/steps);
    for(let value=nearMin;value<=nearMax;value+=1.5)values.push(value);
    return [...new Set(values)].sort((a,b)=>a-b);
  };
  const xs=axis(minX,maxX,-182,-65),zs=axis(minZ,maxZ,-432,-330),columns=xs.length;
  const positions:number[]=[],colors:number[]=[],indices:number[]=[];
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
  const emit=(a:number,b:number,c:number,depth=0)=>{
    const x=(positions[a*3]+positions[b*3]+positions[c*3])/3,z=(positions[a*3+2]+positions[b*3+2]+positions[c*3+2])/3;
    const radius=Math.max(...[a,b,c].map(index=>Math.hypot(positions[index*3]-x,positions[index*3+2]-z)));
    // Keep the actual terrain triangles beneath mountain paths as detailed as
    // their walking surface; the old coarse mesh could bury a trail by metres.
    const detailedCoast=LANDSCAPES.coastalZones.some(zone=>coastalWeight(zone,x,z)>0)&&coastDistance({x,z})<radius+50;
    if(depth<6&&radius>.65&&(trailDistance(x,z)<radius+3||detailedCoast)) {
      const ab=midpoint(a,b),bc=midpoint(b,c),ca=midpoint(c,a);
      emit(a,ab,ca,depth+1);emit(ab,b,bc,depth+1);emit(ca,bc,c,depth+1);emit(ab,bc,ca,depth+1);return;
    }
    indices.push(a,b,c);
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
      if(triangle.every(i=>onIsland({x:positions[i*3],z:positions[i*3+2]})))emit(triangle[0],triangle[1],triangle[2]);
    }
  }
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute("position",new THREE.Float32BufferAttribute(positions,3));
  geometry.setAttribute("color",new THREE.Float32BufferAttribute(colors,3));
  geometry.setIndex(indices);geometry.computeVertexNormals();return geometry;
}

function Woodland({buildings,layout}:{buildings:readonly Building[];layout:CityLayout}) {
  const crowns=useRef<THREE.InstancedMesh>(null),broadleaf=useRef<THREE.InstancedMesh>(null),trunks=useRef<THREE.InstancedMesh>(null);
  const spatialKey=buildings.map(b=>`${b.id}:${b.x}:${b.z}`).join("|");
  const trees=useMemo(()=>{
    const result:{x:number;z:number;y:number;size:number;pine:boolean}[]=[];
    for(let i=0;i<6200;i++) {
      const x=ISLAND_BOUNDS.minX+seed(i)* (ISLAND_BOUNDS.maxX-ISLAND_BOUNDS.minX);
      const z=ISLAND_BOUNDS.minZ+seed(i,2)*(ISLAND_BOUNDS.maxZ-ISLAND_BOUNDS.minZ);
      if(!onIsland({x,z})||!landscapeClear(x,z)||trailDistance(x,z)<4||coastDistance({x,z})<27||nearestRiver(x,z).distance<7)continue;
      if(layout.version>=2?!clearsRoad(x,z,5,layout)||buildings.some(b=>Math.hypot(b.x-x,b.z-z)<(b.outdoorRadius&&!['wilderness','mountain','hill_station','trekking_trail','river','riverside_walk'].includes(b.type)?b.outdoorRadius:10)):(Math.abs(x+150)<302&&Math.abs(z)<302))continue;
      const y=islandElevation(x,z);
      if(y>58||seed(i,6)>.48+.25*Math.sin(x*.012)*Math.cos(z*.009))continue;
      result.push({x,z,y,size:2.3+seed(i,3)*2.5,pine:y>25||seed(i,7)<.3});
    }
    return result;
  },[spatialKey,layout.version]);
  useEffect(()=>{
    if(!crowns.current||!broadleaf.current||!trunks.current)return;
    const dummy=new THREE.Object3D(),color=new THREE.Color();
    let pines=0,leaves=0;
    trees.forEach((tree,i)=>{
      const mesh=tree.pine?crowns.current!:broadleaf.current!,index=tree.pine?pines++:leaves++;
      dummy.position.set(tree.x,tree.y+tree.size*1.5,tree.z);dummy.scale.set(tree.size,tree.size*(tree.pine?1.6:1.25),tree.size);
      dummy.updateMatrix();mesh.setMatrixAt(index,dummy.matrix);
      color.set(i%3===0?"#356c56":i%3===1?"#538365":"#6e946b");mesh.setColorAt(index,color);
      dummy.position.y=tree.y+tree.size*.5;dummy.scale.set(tree.size*.13,tree.size,tree.size*.13);
      dummy.updateMatrix();trunks.current!.setMatrixAt(i,dummy.matrix);
    });
    for(const mesh of [crowns.current,broadleaf.current,trunks.current]){mesh.instanceMatrix.needsUpdate=true;mesh.computeBoundingSphere();}
  },[trees]);
  return <group name="island-woodland">
    <instancedMesh ref={trunks} args={[undefined,undefined,trees.length]}><cylinderGeometry args={[1,1,1,4]}/><meshStandardMaterial color="#736957"/></instancedMesh>
    <instancedMesh ref={crowns} args={[undefined,undefined,trees.filter(t=>t.pine).length]}><coneGeometry args={[1,2.4,6]}/><meshStandardMaterial roughness={1}/></instancedMesh>
    <instancedMesh ref={broadleaf} args={[undefined,undefined,trees.filter(t=>!t.pine).length]}><icosahedronGeometry args={[1.2,0]}/><meshStandardMaterial roughness={1}/></instancedMesh>
  </group>;
}

export default function IslandLandscape({showLabels,buildings=[],layout=prototypeLayout}:{showLabels:boolean;buildings?:readonly Building[];layout?:CityLayout}) {
  const {size}=useThree();
  const landmarks=size.width<640?islandLandmarks.filter(place=>["cloudmere","moonwash-cove","south-coast","silverstream-estuary"].includes(place.id)):islandLandmarks;
  const geometries=useMemo(()=>({...createIslandSurfaces(),terrain:terrainGeometry()}),[]);
  const foam=useRef<THREE.MeshBasicMaterial>(null),motion=useRef(false);
  useEffect(()=>{
    const query=window.matchMedia("(prefers-reduced-motion: reduce)");const update=()=>{motion.current=!query.matches;};
    update();query.addEventListener("change",update);return()=>query.removeEventListener("change",update);
  },[]);
  useEffect(()=>()=>Object.values(geometries).forEach(g=>g.dispose()),[geometries]);
  useFrame(({clock})=>{if(foam.current)foam.current.opacity=motion.current?.19+Math.sin(clock.elapsedTime*.65)*.055:.19;});
  return <group name="active-island">
    <mesh geometry={geometries.ocean} receiveShadow raycast={()=>null}><meshStandardMaterial color="#5497a4" roughness={.65}/></mesh>
    {/* Fixed depth-buffer units separate the coplanar water bands. A slope
        factor can pull the filled polygons over dry land at island zoom. */}
    <mesh geometry={geometries.sea} renderOrder={1} raycast={()=>null}><meshStandardMaterial color="#69b1b6" roughness={.28} metalness={.12} depthWrite={false} polygonOffset polygonOffsetFactor={0} polygonOffsetUnits={-8}/></mesh>
    <mesh geometry={geometries.shelf} renderOrder={2} raycast={()=>null}><meshStandardMaterial color="#9bcac1" roughness={.28} metalness={.12} depthWrite={false} polygonOffset polygonOffsetFactor={0} polygonOffsetUnits={-16}/></mesh>
    <mesh geometry={geometries.shore} receiveShadow><meshStandardMaterial color="#dfd5b3"/></mesh>
    <mesh geometry={geometries.land} receiveShadow><meshStandardMaterial color="#83a879"/></mesh>
    <mesh geometry={geometries.terrain} receiveShadow><meshStandardMaterial vertexColors flatShading roughness={1}/></mesh>
    <mesh geometry={geometries.foam} raycast={()=>null}><meshBasicMaterial ref={foam} color="#f0eee0" transparent opacity={.19} depthWrite={false}/></mesh>
    <Woodland buildings={buildings} layout={layout}/>
    {layout.version>=2&&<><HighlandLandscape/><ConnectedLandscape/></>}
    {showLabels&&landmarks.map(p=><Html key={p.id} position={[p.x,islandElevation(p.x,p.z)+20,p.z]} center style={{pointerEvents:"none"}}><span className="island-landmark">{p.name}<small>WILDERNESS</small></span></Html>)}
  </group>;
}
