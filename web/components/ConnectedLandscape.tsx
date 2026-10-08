"use client";
import {useEffect,useMemo,useRef} from "react";
import * as THREE from "three";
import {CONNECTED_TRAILS,LANDSCAPES} from "@/lib/landscapes";
import {bridgeElevation,islandElevation} from "@/lib/island";
import {coastalBoulders,createCoastalGeometry,createBridgeDeckGeometry,createTrailGeometry,createLakeShoreGeometry} from "@/lib/landscape-geometry";

export default function ConnectedLandscape() {
  const geometry=useMemo(()=>({coast:createCoastalGeometry(),shore:createLakeShoreGeometry(),trails:CONNECTED_TRAILS.map(trail=>createTrailGeometry(trail.points)),bridges:LANDSCAPES.bridges.map(createBridgeDeckGeometry)}),[]);
  const rocks=useMemo(coastalBoulders,[]),instances=useRef<THREE.InstancedMesh>(null);
  useEffect(()=>()=>{geometry.coast.dispose();geometry.shore.dispose();[...geometry.trails,...geometry.bridges].forEach(g=>g.dispose());},[geometry]);
  useEffect(()=>{
    if(!instances.current)return;const dummy=new THREE.Object3D(),color=new THREE.Color();
    rocks.forEach((rock,index)=>{
      dummy.position.set(rock.x,rock.y,rock.z);dummy.scale.set(rock.sx,rock.sy,rock.sz);dummy.rotation.set(.12,rock.angle,.18);dummy.updateMatrix();
      instances.current!.setMatrixAt(index,dummy.matrix);instances.current!.setColorAt(index,color.set(index%3===0?"#777f78":index%3===1?"#939d90":"#65746c"));
    });instances.current.instanceMatrix.needsUpdate=true;instances.current.computeBoundingSphere();
  },[rocks]);
  return <group name="connected-coast-valley-trails">
    <mesh name="detailed-coastal-terrain" geometry={geometry.coast} receiveShadow><meshStandardMaterial vertexColors flatShading roughness={1} side={THREE.DoubleSide}/></mesh>
    <mesh name="cloudmere-stony-shore" geometry={geometry.shore} receiveShadow><meshStandardMaterial vertexColors roughness={1} side={THREE.DoubleSide}/></mesh>
    <instancedMesh name="coastal-rock-formations" ref={instances} args={[undefined,undefined,rocks.length]}><icosahedronGeometry args={[1,0]}/><meshStandardMaterial roughness={1} flatShading/></instancedMesh>
    {CONNECTED_TRAILS.map((trail,index)=><mesh key={trail.id} name={`trail-${trail.id}`} geometry={geometry.trails[index]} receiveShadow><meshStandardMaterial color={trail.surface==="earth"?"#b29d78":trail.surface==="rock"?"#b7b6a2":"#cfbea0"} roughness={1} side={THREE.DoubleSide}/></mesh>)}
    {LANDSCAPES.bridges.map((bridge,index)=>{
      const deck=bridgeElevation(bridge);
      return <group key={bridge.id} name={bridge.id}>
        <mesh name={`${bridge.id}-deck-and-ramps`} geometry={geometry.bridges[index]} receiveShadow><meshStandardMaterial color="#a58c63" roughness={1} side={THREE.DoubleSide}/></mesh>
        <mesh position={[bridge.x,deck-.13,bridge.z]} castShadow receiveShadow><boxGeometry args={[bridge.length,.26,bridge.width]}/><meshStandardMaterial color="#897452" roughness={1}/></mesh>
        {[-1,1].map(side=><group key={side}>
          <mesh position={[bridge.x,deck+.9,bridge.z+side*(bridge.width/2-.1)]}><boxGeometry args={[bridge.length,.12,.12]}/><meshStandardMaterial color="#897452"/></mesh>
          {[-1,0,1].map(end=><mesh key={end} position={[bridge.x+end*(bridge.length/2-.3),deck+.45,bridge.z+side*(bridge.width/2-.1)]}><boxGeometry args={[.14,.9,.14]}/><meshStandardMaterial color="#897452"/></mesh>)}
        </group>)}
        {[-1,1].map(end=>{
          const x=bridge.x+end*(bridge.length/2-.3),ground=islandElevation(x,bridge.z),height=Math.max(.3,deck-ground);
          return <mesh key={end} position={[x,deck-height/2,bridge.z]} receiveShadow><boxGeometry args={[.65,height,bridge.width-.2]}/><meshStandardMaterial color="#9c9e8b" roughness={1}/></mesh>;
        })}
      </group>;
    })}
  </group>;
}
