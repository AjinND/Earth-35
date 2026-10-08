"use client";
import {useEffect,useMemo} from "react";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import {COASTLINE,ISLAND_CENTER,type IslandPoint} from "@/lib/island";
import {ATLAS_BOUNDS,ATLAS_CENTER,ATLAS_HEIGHT,ATLAS_WIDTH,LANDMASSES,atlasPoint} from "@/lib/planet";

function silhouette(points:readonly IslandPoint[]) {
  const shape=new THREE.Shape();
  points.forEach(({x,z},index)=>{if(index===0)shape.moveTo(x,-z);else shape.lineTo(x,-z);});
  shape.closePath();return new THREE.ShapeGeometry(shape);
}

function graticule() {
  const positions:number[]=[];
  for(let longitude=-180;longitude<=180;longitude+=30) {
    const {x}=atlasPoint(longitude,0);
    positions.push(x,-3,ATLAS_BOUNDS.minZ,x,-3,ATLAS_BOUNDS.maxZ);
  }
  for(let latitude=-90;latitude<=90;latitude+=30) {
    const {z}=atlasPoint(0,latitude);
    positions.push(ATLAS_BOUNDS.minX,-3,z,ATLAS_BOUNDS.maxX,-3,z);
  }
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute("position",new THREE.Float32BufferAttribute(positions,3));
  return geometry;
}

export default function PlanetLandscape({showLabels,showIsland,onIsland}:{showLabels:boolean;showIsland:boolean;onIsland:()=>void}) {
  const geometry=useMemo(()=>({
    land:LANDMASSES.map(land=>silhouette(land.outline)),island:silhouette(COASTLINE),grid:graticule(),
  }),[]);
  useEffect(()=>()=>{geometry.land.forEach(land=>land.dispose());geometry.island.dispose();geometry.grid.dispose();},[geometry]);
  // Draw flat atlas layers explicitly: planetary depth spans cannot resolve metre gaps.
  // Local terrain draws afterwards and keeps its own normal depth behavior.
  return <group name="planet-atlas">
    <mesh renderOrder={-100} rotation={[-Math.PI/2,0,0]} position={[ATLAS_CENTER.x,-4,ATLAS_CENTER.z]} raycast={()=>null}>
      <planeGeometry args={[ATLAS_WIDTH,ATLAS_HEIGHT]}/><meshBasicMaterial color="#5497a4" fog={false} depthTest={false} depthWrite={false}/>
    </mesh>
    <lineSegments renderOrder={-90} geometry={geometry.grid} raycast={()=>null}>
      <lineBasicMaterial color="#71a9b1" fog={false} depthTest={false} depthWrite={false}/>
    </lineSegments>
    {LANDMASSES.map((land,index)=><group key={land.id} name={`undeveloped-${land.id}`}>
      <mesh renderOrder={-80} geometry={geometry.land[index]} rotation={[-Math.PI/2,0,0]} position={[0,-2,0]} raycast={()=>null}>
        <meshBasicMaterial color={land.color} fog={false} depthTest={false} depthWrite={false}/>
      </mesh>
      {showLabels&&land.name&&<Html position={[land.label.x,0,land.label.z]} center style={{pointerEvents:"none"}}>
        <span className="planet-landmark" data-minor={land.areaKm2<10000000}>{land.name}<small>Undeveloped</small></span>
      </Html>}
    </group>)}
    {showIsland&&<group name="inhabited-island-atlas">
      <mesh renderOrder={-70} geometry={geometry.island} rotation={[-Math.PI/2,0,0]} position={[0,-1,0]} raycast={()=>null}>
        <meshBasicMaterial color="#83a879" fog={false} depthTest={false} depthWrite={false}/>
      </mesh>
      <Html position={[ISLAND_CENTER.x,0,ISLAND_CENTER.z]} center>
        <button className="planet-island-marker" aria-label="New Eden · Inhabited island"
          onClick={event=>{event.stopPropagation();onIsland();}}>
          New Eden <small>Inhabited island</small>
        </button>
      </Html>
    </group>}
  </group>;
}
