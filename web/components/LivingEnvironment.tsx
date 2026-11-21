"use client";
import {useEffect,useMemo,useRef} from "react";
import {useFrame} from "@react-three/fiber";
import * as THREE from "three";
import type {World} from "@/lib/types";
import {LIVING_LIMITS,livingPlan,livingSpatialKey,wildlifeActive,wildlifePose,type Wildlife} from "@/lib/living-environment";
import {createVegetationTiles} from "@/lib/vegetation-geometry";
import {sharedResourceCache} from "@/lib/shared-resource";
import {createVegetationWindMaterials,updateVegetationWind} from "@/lib/vegetation-wind";
import {useAtmosphericMotion} from "./AtmosphericMotion";

const vegetationCache=sharedResourceCache<string,ReturnType<typeof createVegetationTiles>>(vegetation=>vegetation.dispose());

function Animal({animal}:{animal:Wildlife}) {
  const rabbit=animal.kind==="rabbit",butterfly=animal.kind==="butterfly",gull=animal.kind==="gull";
  const color=rabbit?"#a18c74":butterfly?"#d4a34e":gull?"#ecebd8":"#69798a";
  return <group name={`ambient-${animal.kind}-${animal.id}`} userData={{ambient:true,kind:animal.kind}}>
    <mesh position={[0,rabbit?.3:0,0]} scale={rabbit?[.32,.34,.55]:butterfly?[.04,.06,.25]:[.12,.13,.42]}><icosahedronGeometry args={[1,1]}/><meshStandardMaterial color={color}/></mesh>
    {rabbit?<>
      <mesh position={[0,.56,.38]} scale={[.25,.26,.29]}><icosahedronGeometry args={[1,0]}/><meshStandardMaterial color={color}/></mesh>
      {[-1,1].map(side=><group key={side}><mesh position={[side*.12,.92,.39]} rotation={[.15,0,side*-.12]}><capsuleGeometry args={[.065,.32,2,4]}/><meshStandardMaterial color="#b7a38a"/></mesh><mesh position={[side*.19,.6,.57]}><sphereGeometry args={[.035,5,4]}/><meshBasicMaterial color="#272b27"/></mesh></group>)}
      <mesh position={[0,.37,-.48]}><icosahedronGeometry args={[.17,0]}/><meshStandardMaterial color="#ded4bc"/></mesh>
    </>:<>
      {[-1,1].map(side=><group key={side} name={side===-1?"left-wing":"right-wing"} position={[side*.07,0,0]}>
        <mesh position={[side*(butterfly?.21:.48),0,0]} rotation={[0,side*.24,side*.05]} scale={butterfly?[.3,.025,.27]:[.63,.045,.23]}><icosahedronGeometry args={[1,0]}/><meshStandardMaterial color={color}/></mesh>
        {!butterfly&&<mesh position={[side*.94,0,-.12]} scale={[.35,.025,.14]}><icosahedronGeometry args={[1,0]}/><meshStandardMaterial color={gull?"#52616b":"#43525a"}/></mesh>}
      </group>)}
      {!butterfly&&<mesh position={[0,.04,.43]} rotation={[Math.PI/2,0,0]}><coneGeometry args={[.065,.2,4]}/><meshStandardMaterial color="#ba9d68"/></mesh>}
    </>}
  </group>;
}

export default function LivingEnvironment({world}:{world:World}) {
  const key=livingSpatialKey(world.buildings,world.objects),plan=useMemo(()=>livingPlan(world.buildings,world.objects),[key]);
  const vegetation=useMemo(()=>vegetationCache.get(key,()=>createVegetationTiles(plan.plants)),[key,plan]);
  const root=useRef<THREE.Group>(null),animals=useRef<THREE.Group>(null),report=useRef(0),atmosphere=useAtmosphericMotion();
  const materials=useMemo(()=>createVegetationWindMaterials(),[]);
  const visibility=useMemo(()=>({frustum:new THREE.Frustum(),matrix:new THREE.Matrix4(),point:new THREE.Vector3()}),[]);
  useEffect(()=>vegetationCache.retain(key),[key,vegetation]);
  useEffect(()=>()=>materials.dispose(),[materials]);
  useFrame(({camera,size,gl},delta)=>{
    const frame=atmosphere.current;updateVegetationWind(materials,frame);
    const orthographic=camera instanceof THREE.OrthographicCamera;
    const pixels=orthographic?size.height/(camera.top-camera.bottom)*camera.zoom:Infinity;
    visibility.frustum.setFromProjectionMatrix(visibility.matrix.multiplyMatrices(camera.projectionMatrix,camera.matrixWorldInverse));
    let detailTiles=0,visibleAnimals=0;
    for(const child of root.current?.children??[]) {
      const detail=child.getObjectByName("understory");if(!detail)continue;
      const distance=Math.hypot(camera.position.x-child.position.x,camera.position.z-child.position.z);
      detail.visible=orthographic?pixels>=LIVING_LIMITS.detailPixels:distance<LIVING_LIMITS.detailDistance+LIVING_LIMITS.tileSize*.71;
      if(detail.visible&&visibility.frustum.intersectsObject(detail as THREE.Mesh))detailTiles++;
    }
    let signature="";
    for(const [index,animal] of plan.wildlife.entries()) {
      const group=animals.current?.children[index];if(!group)continue;
      const pose=wildlifePose(animal,frame.minute,frame.reducedMotion);
      const distance=Math.hypot(camera.position.x-pose.x,camera.position.z-pose.z);
      group.visible=(orthographic?pixels>=LIVING_LIMITS.detailPixels:distance<LIVING_LIMITS.wildlifeDistance)&&wildlifeActive(animal.kind,world.minute,world.weather);
      if(!group.visible)continue;
      group.position.set(pose.x,pose.y,pose.z);group.rotation.y=pose.heading;
      const left=group.getObjectByName("left-wing"),right=group.getObjectByName("right-wing");
      if(left)left.rotation.z=-pose.wing;if(right)right.rotation.z=pose.wing;
      if(visibility.frustum.containsPoint(visibility.point.copy(group.position)))visibleAnimals++;
      signature+=`${animal.id}:${pose.x.toFixed(3)},${pose.y.toFixed(3)},${pose.z.toFixed(3)},${pose.wing.toFixed(3)};`;
    }
    report.current+=delta;if(report.current>=.3) {
      report.current=0;
      Object.assign(gl.domElement.dataset,{habitatPlants:String(plan.plants.length),habitatTrees:String(plan.plants.filter(p=>!p.detail).length),habitatTiles:String(vegetation.tiles.length),habitatDetailTiles:String(detailTiles),wildlifeCount:String(plan.wildlife.length),wildlifeVisible:String(visibleAnimals),wildlifeMotion:frame.reducedMotion?"reduced":"animated",wildlifePose:signature,
        vegetationTime:materials.uniforms.vegetationSeconds.value.toFixed(3),vegetationWind:`${materials.uniforms.vegetationWind.value.x.toFixed(4)},${materials.uniforms.vegetationWind.value.y.toFixed(4)}`,vegetationMotion:frame.reducedMotion?"reduced":"animated",vegetationWindStrength:materials.uniforms.vegetationStrength.value.toFixed(3)});
    }
  });
  return <group name="living-environment">
    <group ref={root} name="habitat-vegetation">{vegetation.tiles.map(tile=><group key={tile.id} position={[tile.x,0,tile.z]}>
      {tile.canopy&&<mesh name="canopy" geometry={tile.canopy} material={materials.material} customDepthMaterial={materials.depth} customDistanceMaterial={materials.distance} receiveShadow raycast={()=>null}/>}
      {tile.understory&&<mesh name="understory" geometry={tile.understory} material={materials.material} customDepthMaterial={materials.depth} customDistanceMaterial={materials.distance} visible={false} receiveShadow raycast={()=>null}/>}
    </group>)}</group>
    <group ref={animals} name="ambient-wildlife">{plan.wildlife.map(animal=><Animal key={animal.id} animal={animal}/>)}</group>
  </group>;
}
