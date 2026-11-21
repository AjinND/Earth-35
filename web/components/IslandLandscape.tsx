"use client";
import WeatherMaterial from "./WeatherMaterial";
import {useEffect,useMemo,useRef} from "react";
import {useThree} from "@react-three/fiber";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import {ISLAND_BOUNDS,islandElevation,islandLandmarks,onIsland,coastDistance} from "@/lib/island";
import type {Building} from "@/lib/types";
import {clearsRoad,prototypeLayout,type CityLayout} from "@/lib/city-layout";
import {nearestRiver} from "@/lib/river";
import {landscapeClear,trailDistance} from "@/lib/landscapes";
import HighlandLandscape from "./HighlandLandscape";
import ConnectedLandscape from "./ConnectedLandscape";
import CoastalWater from "./CoastalWater";
import {createIslandSurfaces} from "@/lib/island-surface-geometry";
import {sharedResourceCache} from "@/lib/shared-resource";
import {terrainGeometry} from "@/lib/island-terrain";
export {terrainGeometry} from "@/lib/island-terrain";

const seed=(i:number,salt=1)=>{const n=Math.sin(i*127.1+salt*311.7)*43758.5453;return n-Math.floor(n);};

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
    <instancedMesh ref={trunks} args={[undefined,undefined,trees.length]}><cylinderGeometry args={[1,1,1,4]}/><WeatherMaterial surface="ground" color="#736957"/></instancedMesh>
    <instancedMesh ref={crowns} args={[undefined,undefined,trees.filter(t=>t.pine).length]}><coneGeometry args={[1,2.4,6]}/><WeatherMaterial surface="ground" roughness={1}/></instancedMesh>
    <instancedMesh ref={broadleaf} args={[undefined,undefined,trees.filter(t=>!t.pine).length]}><icosahedronGeometry args={[1.2,0]}/><WeatherMaterial surface="ground" roughness={1}/></instancedMesh>
  </group>;
}

const createGeometry=()=>({...createIslandSurfaces(),terrain:terrainGeometry()});
const islandGeometry=sharedResourceCache<string,ReturnType<typeof createGeometry>>(geometries=>Object.values(geometries).forEach(g=>g.dispose()));

export default function IslandLandscape({showLabels,buildings=[],layout=prototypeLayout}:{showLabels:boolean;buildings?:readonly Building[];layout?:CityLayout}) {
  const {size}=useThree();
  const landmarks=size.width<640?islandLandmarks.filter(place=>["cloudmere","moonwash-cove","south-coast","silverstream-estuary"].includes(place.id)):islandLandmarks;
  const geometries=useMemo(()=>islandGeometry.get("island",createGeometry),[]);
  useEffect(()=>islandGeometry.retain("island"),[geometries]);
  return <group name="active-island">
    <CoastalWater ocean={geometries.ocean} sea={geometries.sea} shelf={geometries.shelf}/>
    <mesh geometry={geometries.shore} receiveShadow><WeatherMaterial surface="ground" color="#dfd5b3"/></mesh>
    <mesh geometry={geometries.land} receiveShadow><WeatherMaterial surface="ground" color="#83a879"/></mesh>
    <mesh geometry={geometries.terrain} receiveShadow><WeatherMaterial surface="ground" vertexColors flatShading roughness={1}/></mesh>
    {layout.version<4&&<Woodland buildings={buildings} layout={layout}/>}
    {layout.version>=2&&<><HighlandLandscape/><ConnectedLandscape/></>}
    {showLabels&&landmarks.map(p=><Html key={p.id} position={[p.x,islandElevation(p.x,p.z)+20,p.z]} center style={{pointerEvents:"none"}}><span className="island-landmark">{p.name}<small>WILDERNESS</small></span></Html>)}
  </group>;
}
