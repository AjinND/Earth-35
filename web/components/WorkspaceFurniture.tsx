"use client";
import type {Building} from "@/lib/types";
import {enclosedPlace} from "@/lib/city-layout";
import {walkElevation} from "@/lib/island";

/** Render only the physical stations supplied by the venue. No task or stock is invented. */
export default function WorkspaceFurniture({building}:{building:Building}) {
  if(!enclosedPlace(building.type)||!building.workspaces?.length)return null;
  return <group name={`workspaces-${building.id}`}>
    {building.workspaces.map(station=>{
      const desk=station.kind==="desk",counter=station.kind==="counter";
      return <group key={station.id} name={`workspace-${station.id}`} position={[station.x,walkElevation(station.x,station.z),station.z]} rotation={[0,station.heading,0]} userData={{kind:station.kind,stance:{x:station.x,z:station.z},surfaceOffset:.7}}>
        <group position={[0,0,.7]}>
          <mesh receiveShadow castShadow position={[0,.94,0]}><boxGeometry args={[1.35,.12,.65]}/><meshStandardMaterial color={counter?"#bab5a5":desk?"#aa9378":"#999b8a"} roughness={.9}/></mesh>
          {[-.55,.55].flatMap(x=>[-.23,.23].map(z=><mesh key={`${x}-${z}`} castShadow position={[x,.44,z]}><boxGeometry args={[.1,.88,.1]}/><meshStandardMaterial color="#717970" roughness={.9}/></mesh>))}
          {counter&&<mesh position={[0,.46,.23]}><boxGeometry args={[1.2,.84,.1]}/><meshStandardMaterial color="#a29b86" roughness={.9}/></mesh>}
        </group>
        {desk&&<group name={`workspace-chair-${station.id}`}>
          <mesh castShadow position={[0,.75,0]}><boxGeometry args={[.8,.16,.65]}/><meshStandardMaterial color="#8d9c8d" roughness={1}/></mesh>
          <mesh castShadow position={[0,1.12,-.31]}><boxGeometry args={[.8,.64,.1]}/><meshStandardMaterial color="#8d9c8d" roughness={1}/></mesh>
          {[-.3,.3].flatMap(x=>[-.22,.22].map(z=><mesh key={`${x}-${z}`} position={[x,.34,z]}><boxGeometry args={[.08,.68,.08]}/><meshStandardMaterial color="#717970" roughness={.9}/></mesh>))}
        </group>}
      </group>;
    })}
  </group>;
}
