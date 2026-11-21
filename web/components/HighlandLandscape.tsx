"use client";
import WeatherMaterial from "./WeatherMaterial";
import {useEffect,useMemo} from "react";
import * as THREE from "three";
import {LAKE,LAKE_SHORE,HIGHLAND_TRAILS,LANDSCAPES} from "@/lib/landscapes";
import {islandElevation,walkElevation} from "@/lib/island";
import {createPlungePool} from "@/lib/river-geometry";
import WaterfallEffects from "./WaterfallEffects";
import WindWaterMaterial from "./WindWaterMaterial";
import {createTrailGeometry} from "@/lib/landscape-geometry";
import {sharedResourceCache} from "@/lib/shared-resource";

function lakeSurface() {
  const vertices=[LAKE.x,LAKE.level+.035,LAKE.z],indices:number[]=[];
  for(const point of LAKE_SHORE)vertices.push(point.x,LAKE.level+.035,point.z);
  for(let i=0;i<LAKE_SHORE.length;i++)indices.push(0,(i+1)%LAKE_SHORE.length+1,i+1);
  const geometry=new THREE.BufferGeometry();geometry.setAttribute("position",new THREE.Float32BufferAttribute(vertices,3));
  geometry.setIndex(indices);geometry.computeVertexNormals();return geometry;
}
function trailSurface() {
  const parts=HIGHLAND_TRAILS.map(trail=>createTrailGeometry(trail)),vertices:number[]=[],indices:number[]=[];
  for(const part of parts) {
    const offset=vertices.length/3;vertices.push(...Array.from(part.getAttribute("position").array));
    indices.push(...Array.from(part.index!.array).map(index=>index+offset));part.dispose();
  }
  const geometry=new THREE.BufferGeometry();geometry.setAttribute("position",new THREE.Float32BufferAttribute(vertices,3));geometry.setIndex(indices);geometry.computeVertexNormals();return geometry;
}
const createGeometry=()=>({lake:lakeSurface(),trail:trailSurface(),...createPlungePool()});
const highlandGeometry=sharedResourceCache<string,ReturnType<typeof createGeometry>>(geometry=>Object.values(geometry).forEach(g=>g.dispose()));
export default function HighlandLandscape() {
  const geometry=useMemo(()=>highlandGeometry.get("highlands",createGeometry),[]);
  useEffect(()=>highlandGeometry.retain("highlands"),[geometry]);
  return <group name="cloudmere-lake-waterfall-valley">
    <mesh name="cloudmere-lake" geometry={geometry.lake}><WindWaterMaterial color="#347e92" roughness={.28} metalness={.2} side={THREE.DoubleSide}/></mesh>
    <mesh name="highland-trail" geometry={geometry.trail} receiveShadow><WeatherMaterial surface="ground" color="#c6b48b" roughness={1} side={THREE.DoubleSide}/></mesh>
    <mesh name="waterfall-plunge-basin" geometry={geometry.basin} receiveShadow><WeatherMaterial surface="ground" vertexColors flatShading roughness={1} side={THREE.DoubleSide}/></mesh>
    <mesh name="waterfall-plunge-pool" geometry={geometry.water}><WindWaterMaterial color="#4a979c" roughness={.25} metalness={.15} side={THREE.DoubleSide}/></mesh>
    <WaterfallEffects/>
    {LANDSCAPES.viewpoints.map(place=><group key={place.id} name={`trail-marker-${place.id}`} position={[place.x,islandElevation(place.x,place.z),place.z]}>
      <mesh position={[0,.8,3]}><cylinderGeometry args={[.13,.16,1.6,5]}/><WeatherMaterial surface="ground" color="#786e5b"/></mesh>
      <mesh position={[0,1.6,3]}><boxGeometry args={[1.4,.55,.12]}/><WeatherMaterial surface="ground" color="#dbc994"/></mesh>
    </group>)}
  </group>;
}
