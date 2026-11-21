"use client";
import WeatherMaterial from "./WeatherMaterial";
import {useEffect,useMemo} from "react";
import {Html} from "@react-three/drei";
import type * as THREE from "three";
import {createBuildingGeometry} from "@/lib/building-geometry";
import {walkElevation} from "@/lib/island";
import type {Building} from "@/lib/types";

export default function DesignedBuilding({building:b,selected,windows,onSelect,detailed,showLabels}:{building:Building;selected:boolean;windows:THREE.Material;onSelect:()=>void;detailed:boolean;showLabels:boolean}) {
  const geometry=useMemo(()=>createBuildingGeometry(b,detailed),[b.id,b.type,b.district,b.x,b.z,b.entrance?.x,b.entrance?.z,detailed]);
  useEffect(()=>()=>geometry.dispose(),[geometry]);
  const {design}=geometry;
  const elevation=walkElevation(b.x+design.door.x,b.z+design.door.z);
  return <group name={`architecture-${design.architecture.style}-${b.id}`} position={[b.x,elevation,b.z]} userData={{buildingId:b.id,style:design.architecture.style,detailed,door:design.door}} onClick={event=>{event.stopPropagation();onSelect();}}>
    <mesh name={`building-shell-${b.id}`} geometry={geometry.opaque} castShadow receiveShadow>
      <WeatherMaterial surface="building" vertexColors color={selected?"#c1dfca":"#ffffff"} roughness={.88}/>
    </mesh>
    {design.glass.length>0&&<mesh name={`building-windows-${b.id}`} geometry={geometry.windows} material={windows}/>}
    {(selected||b.type==="office"&&showLabels)&&<Html center position={[0,design.top+1.2,0]}><button className={`map-label ${selected?"selected":""}`} onClick={onSelect}>{b.name}</button></Html>}
  </group>;
}
